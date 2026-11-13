package app.wyzecamrtsp

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A tiny web server that only runs while the "Set up from your phone" screen is open.
 * The phone opens http://<tv-ip>:8322/?k=<key>; the random key (in the QR code) keeps other
 * devices on the network out. Everything the phone sends is applied on the TV's main thread.
 */
class SetupServer(private val ctx: Context, private val onEvent: (String) -> Unit) : Closeable {
    val key: String = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(31)] }.joinToString("")
    private val server: ServerSocket = try { ServerSocket(8322, 16, InetAddress.getByName("0.0.0.0")) }
        catch (e: Exception) { ServerSocket(0, 16, InetAddress.getByName("0.0.0.0")) }
    val port get() = server.localPort
    private val main = Handler(Looper.getMainLooper())
    private var greeted = false

    init {
        thread(name = "setup-http", isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }
    }

    override fun close() { runCatching { server.close() } }

    private fun event(msg: String) = main.post { onEvent(msg) }

    private fun handle(s: Socket) {
        s.soTimeout = 120_000
        val input = s.getInputStream().buffered()
        val reqLine = line(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val l = line(input) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1 shl 20) ?: 0
        val body = ByteArray(len).also { var n = 0; while (n < len) { val r = input.read(it, n, len - n); if (r < 0) break; n += r } }
        val (method, target) = reqLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val out = s.getOutputStream()

        if (path == "/favicon.ico") return respond(out, 404, "text/plain", "")
        if (query["k"] != key) return respond(out, 403, "text/plain; charset=utf-8",
            "Wrong or missing key. Scan the QR code shown on the TV again.")
        if (!greeted) { greeted = true; event("Phone connected ✓") }

        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", PAGE.replace("__KEY__", key))
            method == "GET" && path == "/api/state" -> respond(out, 200, JSON, onMain { state() }.toString())
            method == "POST" && path == "/api/save" -> {
                val j = JSONObject(String(body, Charsets.UTF_8))
                onMain { apply(j) }
                RtspsProxy.forgetPrepared()
                event("Settings saved from phone ✓")
                respond(out, 200, JSON, onMain { state() }.toString())
            }
            method == "POST" && path == "/api/scan" -> {
                event("Scanning the network (started from phone)…")
                val msg = runCatching { Scanner.scan(ctx, AtomicBoolean(false)) { p -> event(p) } }.getOrElse { "Scan failed: ${it.message}" }
                event(msg.lineSequence().first())
                respond(out, 200, JSON, onMain { state() }.put("message", msg).toString())
            }
            method == "POST" && path == "/api/test" -> {
                val ip = query["ip"] ?: ""
                val cam = onMain { Store.cams.firstOrNull { it.ip == ip } }
                val r = if (cam == null) ProbeResult(false, "unknown camera")
                    else RtspProbe.describe(cam.ip, cam.port, Store.streamFor(cam, true), Store.user, Store.pass)
                if (cam != null) onMain { cam.ok = r.ok; cam.error = r.error; if (r.codec.isNotEmpty()) cam.codec = r.codec; Store.save() }
                respond(out, 200, JSON, JSONObject().put("ok", r.ok).put("error", r.error).put("codec", r.codec).toString())
            }
            else -> respond(out, 404, "text/plain", "not found")
        }
    }

    private fun state() = JSONObject().apply {
        put("user", Store.user); put("hasPass", Store.pass.isNotEmpty()); put("port", Store.port)
        put("subnet", Store.subnet); put("autoSubnet", Scanner.hosts(ctx, "")?.first ?: "")
        put("gridStream", Store.gridStream); put("fullStream", Store.fullStream)
        put("showNames", Store.showNames); put("fullAudio", Store.fullAudio); put("resyncMin", Store.resyncMin)
        put("cams", JSONArray().apply { Store.cams.forEach { put(it.toJson()) } })
    }

    private fun apply(j: JSONObject) {
        if (j.has("user")) Store.user = j.getString("user").trim()
        j.optString("pass").takeIf { it.isNotEmpty() }?.let { Store.pass = it }
        j.optInt("port", Store.port).takeIf { it in 1..65535 }?.let { Store.port = it }
        if (j.has("subnet")) Store.subnet = j.getString("subnet").trim()
        j.optString("gridStream").takeIf { it == MAIN || it == SUB }?.let { Store.gridStream = it }
        j.optString("fullStream").takeIf { it == MAIN || it == SUB }?.let { Store.fullStream = it }
        if (j.has("showNames")) Store.showNames = j.getBoolean("showNames")
        if (j.has("fullAudio")) Store.fullAudio = j.getBoolean("fullAudio")
        if (j.has("resyncMin")) Store.resyncMin = j.getInt("resyncMin").coerceIn(0, 240)
        j.optJSONArray("cams")?.let { a ->
            val old = Store.cams.associateBy { it.ip }
            val next = mutableListOf<Cam>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ip = o.optString("ip").trim()
                if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip) || next.any { it.ip == ip }) continue
                val c = old[ip] ?: Cam(name = "Camera ${ip.substringAfterLast('.')}", ip = ip, port = Store.port)
                o.optString("name").trim().takeIf { it.isNotEmpty() }?.let { c.name = it.take(60) }
                if (o.has("enabled")) c.enabled = o.getBoolean("enabled")
                c.stream = o.optString("stream").takeIf { it == MAIN || it == SUB }
                c.port = Store.port
                next += c
            }
            Store.cams.clear(); Store.cams += next
        }
        Store.save()
    }

    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post { result = runCatching(f); latch.countDown() }
        latch.await()
        return result!!.getOrThrow()
    }

    private fun line(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) return null
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes); out.flush()
    }

    companion object {
        private const val JSON = "application/json; charset=utf-8"

        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>WyzeCam RTSP – TV setup</title>
<style>
 :root{color-scheme:dark}
 body{font:16px/1.4 system-ui,sans-serif;background:#0e1116;color:#f2f4f7;margin:0;padding:16px 16px 96px}
 h1{font-size:22px;margin:4px 0 2px} h2{font-size:15px;text-transform:uppercase;letter-spacing:.06em;color:#a9b2bf;margin:24px 0 8px}
 .card{background:#1a1f27;border-radius:12px;padding:14px;margin-bottom:10px}
 label{display:block;margin:10px 0 4px;color:#a9b2bf;font-size:14px}
 input,select{width:100%;box-sizing:border-box;font-size:17px;padding:10px;border-radius:8px;border:1px solid #39424f;background:#0e1116;color:#f2f4f7}
 input[type=checkbox]{width:auto;transform:scale(1.4);margin-right:10px}
 button{font-size:16px;padding:10px 14px;border-radius:8px;border:0;background:#2b3442;color:#f2f4f7}
 button.primary{background:#4fc3f7;color:#000;font-weight:600}
 .row{display:flex;gap:8px;align-items:center} .row>*{flex:1}
 .cam{border-top:1px solid #2b3442;padding:12px 0} .cam:first-child{border:0}
 .meta{font-size:13px;color:#a9b2bf;margin:4px 0}
 .ok{color:#7ee787} .bad{color:#ff7b72}
 .bar{position:fixed;left:0;right:0;bottom:0;background:#0e1116ee;padding:12px 16px;display:flex;gap:10px;align-items:center;border-top:1px solid #2b3442}
 .bar .primary{flex:0 0 auto;padding:12px 24px} #status{flex:1;font-size:14px;color:#a9b2bf}
 .small{flex:0 0 auto;padding:8px 10px}
</style></head><body>
<h1>WyzeCam RTSP</h1><div class="meta">Changes go to your TV when you press <b>Save to TV</b>.</div>

<h2>RTSP login</h2>
<div class="card">
 <div class="meta">The username and password you set in the Wyze app: camera → Settings → Advanced Settings → RTSP. Use the same login on every camera.</div>
 <label>Username</label><input id="user" autocapitalize="off" autocomplete="off" autocorrect="off">
 <label>Password</label><div class="row"><input id="pass" type="password" autocomplete="off" placeholder="(unchanged)"><button class="small" type="button" onclick="pass.type=pass.type=='password'?'text':'password'">Show</button></div>
</div>

<h2>Cameras</h2>
<div class="card"><div id="cams"></div>
 <div class="row" style="margin-top:12px"><input id="newip" inputmode="decimal" placeholder="Add by IP, e.g. 192.168.1.243"><button class="small" type="button" onclick="addCam()">Add</button></div>
 <div class="row" style="margin-top:10px"><button type="button" onclick="scan()">Scan network for cameras</button></div>
</div>

<h2>Picture</h2>
<div class="card">
 <label>Grid quality</label><select id="gridStream"><option value="stream1">Substream (lighter, recommended)</option><option value="stream0">Main stream (HD)</option></select>
 <label>Full-screen quality</label><select id="fullStream"><option value="stream0">Main stream (HD)</option><option value="stream1">Substream (lighter)</option></select>
 <label>Reconnect each camera to stay live</label><select id="resyncMin"><option value="0">Off</option><option value="5">Every 5 min</option><option value="10">Every 10 min</option><option value="15">Every 15 min</option><option value="30">Every 30 min</option><option value="60">Every 60 min</option></select>
 <label><input type="checkbox" id="showNames">Show camera names on tiles</label>
 <label><input type="checkbox" id="fullAudio">Sound in full screen</label>
</div>

<h2>Advanced</h2>
<div class="card">
 <label>RTSP port</label><input id="port" inputmode="numeric">
 <label>Subnet to scan (blank = automatic)</label><input id="subnet" inputmode="decimal">
</div>

<div class="bar"><span id="status"></span><button class="primary" type="button" onclick="save()">Save to TV</button></div>

<script>
const K='__KEY__'; let st=null;
const ${'$'}=id=>document.getElementById(id);
function api(p,body){return fetch(p+(p.includes('?')?'&':'?')+'k='+K,{method:body===undefined?'GET':'POST',body:body===undefined?undefined:JSON.stringify(body)}).then(r=>{if(!r.ok)throw new Error('HTTP '+r.status);return r.json()})}
function why(e){return /^HTTP /.test(e.message)?e.message:'lost the connection to the TV. Keep the “Set up from your phone” screen open on the TV, then reload this page.'}
function say(t){${'$'}('status').textContent=t}
function el(tag,props,kids){const e=document.createElement(tag);Object.assign(e,props||{});(kids||[]).forEach(k=>e.append(k));return e}
function render(){
 ${'$'}('user').value=st.user; ${'$'}('pass').value=''; ${'$'}('pass').placeholder=st.hasPass?'(unchanged)':'not set yet';
 ${'$'}('gridStream').value=st.gridStream; ${'$'}('fullStream').value=st.fullStream; ${'$'}('resyncMin').value=String(st.resyncMin);
 ${'$'}('showNames').checked=st.showNames; ${'$'}('fullAudio').checked=st.fullAudio; ${'$'}('port').value=st.port;
 ${'$'}('subnet').value=st.subnet; ${'$'}('subnet').placeholder='automatic ('+st.autoSubnet+')';
 const box=${'$'}('cams'); box.textContent='';
 if(!st.cams.length) box.append(el('div',{className:'meta',textContent:'No cameras yet. Save your login, then scan the network.'}));
 st.cams.forEach((c,i)=>{
  const name=el('input',{value:c.name,oninput:e=>c.name=e.target.value});
  const show=el('input',{type:'checkbox',checked:c.enabled,onchange:e=>c.enabled=e.target.checked});
  const sel=el('select',{onchange:e=>c.stream=e.target.value},[el('option',{value:'',textContent:'Automatic quality'}),el('option',{value:'stream0',textContent:'Always main (HD)'}),el('option',{value:'stream1',textContent:'Always substream'})]);
  sel.value=c.stream||'';
  const status=el('span',{className:c.ok?'ok':'bad',textContent:c.ok?'✓ working':'✗ '+(c.error||'not working')});
  const mv=(d)=>{const j=i+d;if(j<0||j>=st.cams.length)return;[st.cams[i],st.cams[j]]=[st.cams[j],st.cams[i]];render()};
  box.append(el('div',{className:'cam'},[
   el('div',{className:'row'},[el('b',{textContent:(i+1)+'.',style:'flex:0 0 auto'}),name]),
   el('div',{className:'meta'},[c.ip+'  ',status,c.codec?'  '+c.codec:'']),
   el('div',{className:'row'},[el('label',{style:'margin:0;flex:1.2'},[show,'Show in grid']),sel]),
   el('div',{className:'row',style:'margin-top:8px'},[
    el('button',{className:'small',type:'button',textContent:'▲',onclick:()=>mv(-1)}),
    el('button',{className:'small',type:'button',textContent:'▼',onclick:()=>mv(1)}),
    el('button',{type:'button',textContent:'Test',onclick:()=>test(c,status)}),
    el('button',{type:'button',textContent:'Remove',onclick:()=>{if(confirm('Remove '+c.name+'?')){st.cams.splice(i,1);render()}}})])
  ]));
 });
}
function collect(){return{user:${'$'}('user').value.trim(),pass:${'$'}('pass').value,port:parseInt(${'$'}('port').value)||322,subnet:${'$'}('subnet').value.trim(),
 gridStream:${'$'}('gridStream').value,fullStream:${'$'}('fullStream').value,resyncMin:parseInt(${'$'}('resyncMin').value),
 showNames:${'$'}('showNames').checked,fullAudio:${'$'}('fullAudio').checked,
 cams:st.cams.map(c=>({ip:c.ip,name:c.name,enabled:c.enabled,stream:c.stream||''}))}}
function save(){say('Saving…');return api('/api/save',collect()).then(s=>{st=s;render();say('Saved to TV ✓')}).catch(e=>say('Save failed: '+why(e)))}
function addCam(){const ip=${'$'}('newip').value.trim();if(!/^\d{1,3}(\.\d{1,3}){3}${'$'}/.test(ip)){say('Enter an IP address like 192.168.1.243');return}
 if(!st.cams.some(c=>c.ip==ip))st.cams.push({ip,name:'Camera '+ip.split('.')[3],enabled:true,stream:'',ok:true,error:'',codec:''});${'$'}('newip').value='';render();say('Added. Press Save to TV.')}
function scan(){if(!${'$'}('user').value.trim()||(!st.hasPass&&!${'$'}('pass').value)){say('Enter the username and password first');return}
 say('Saving, then scanning the network (up to a minute)…');
 api('/api/save',collect()).then(()=>api('/api/scan',{})).then(s=>{st=s;render();say('Scan finished');alert(s.message)}).catch(e=>say('Scan failed: '+why(e)))}
function test(c,span){span.className='';span.textContent='testing…';api('/api/save',collect()).then(()=>api('/api/test?ip='+encodeURIComponent(c.ip),{}))
 .then(r=>{c.ok=r.ok;c.error=r.error;span.className=r.ok?'ok':'bad';span.textContent=r.ok?'✓ working '+r.codec:'✗ '+r.error}).catch(e=>{span.className='bad';span.textContent='✗ test failed: '+why(e)})}
api('/api/state').then(s=>{st=s;render();say(st.cams.length?'':'Start with your login, then scan.')}).catch(e=>say('Could not reach the TV: '+why(e)));
</script></body></html>
"""
    }
}
