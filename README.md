# WyzeCam RTSP for Google TV

An Android TV / Google TV app based on [crash101g/wyzecam-rtsp](https://github.com/crash101g/wyzecam-rtsp).
It shows your Wyze cameras' RTSP streams in a live grid. Video goes straight from each camera to the TV
over your home network, with no cloud and no Wyze account.

**Install:** `WyzeCamRTSP-tv.apk`

## Install on the TV
1. On the TV: Settings → System → About → click *Android TV OS build* 7 times (developer mode),
   then Settings → System → Developer options → turn on **USB debugging** (some TVs call it *ADB debugging*).
2. From this PC: `adb connect <tv-ip>` (accept the prompt on the TV), then
   `adb install -r WyzeCamRTSP-tv.apk`
   (Alternative: put the APK on a USB stick or use the "Send Files to TV" / Downloader apps.)

## First run
Turn on RTSP for each camera in the Wyze app (camera → Settings → Advanced Settings → RTSP) and set a
username and password. Use the same login on every camera. Then on the TV choose **Set up from your phone**.
Scan the QR code, type the login, tap **Scan network for cameras** and then **Save to TV**.
You can also type everything with the remote under Settings.

## Remote control
| Where | Key | Action |
|---|---|---|
| Grid | Arrows | pick a camera (blue border) |
| Grid | OK | watch it full screen (HD main stream) |
| Grid | Hold OK / Menu | options: move, rename, hide, stream quality, reconnect, test |
| Grid | ▲ from the top row, or 0 | menu bar: Settings, Phone setup, Cameras, Rearrange, Names, Reconnect all, Show hidden, Scan, Help |
| Grid | 1–9 | jump to that camera |
| Grid | Play/Pause | reconnect all cameras to live |
| Grid | Back ×2 | exit |
| Rearrange | Arrows / OK | move the yellow tile, then OK (or Back) to save the layout |
| Full screen | ◀ ▶ / Channel ± | previous / next camera |
| Full screen | OK / ▲ ▼ | show the name, stream and key hints |
| Full screen | Hold OK / Menu | switch main/substream, sound on/off, rename, reconnect, test |
| Lists | ◀ ▶ on a camera (Manage cameras) | move it up / down in the order |

## How it works
- Media3/ExoPlayer can't play `rtsps://`. A small local proxy (`RtspsProxy.kt`) makes the TLS connection to the
  camera on port 322, answers the camera's Digest login, and carries RTP over the same connection. It also
  sends a TEARDOWN when a stream closes so the camera frees its viewer slot.
- Media3 is much stricter about the camera's SDP than ffmpeg/VLC. The proxy cleans it up (`Sdp.kt`), and if
  the camera leaves the H.264 SPS/PPS out of the SDP, it plays the stream for a moment once to catch them
  from the video and adds them (cached until the app restarts or settings are saved from the phone).
  Without this, every tile shows "io unspecified".
- Debug: `adb logcat -s RtspsProxy CameraTile` shows each camera's SDP and the real reason a stream fails.
- The grid uses the lighter substream (`stream1`) by default, because TVs have a limited number of
  hardware video decoders. Full screen uses the main stream (`stream0`) and falls back to the substream if
  the main stream keeps failing.
- Each tile reconnects on its own. A tile whose video freezes for 8 s is restarted, and every camera
  reconnects every 10 minutes (staggered) to stay live. A dark grey tile = no video right now; it keeps retrying.
- Cameras are recognised by their own identity, not their IP address, so a camera that gets a new IP from
  your router keeps its name, place and settings. Android 10+ doesn't let apps read MAC addresses, so the
  identity is the fingerprint of the camera's TLS key (each Wyze cam makes its own). The MAC is used too on
  older TVs that allow it. When a camera stops answering at its IP, or a different camera answers there, the
  app quietly rescans in the background (at most every 3 minutes, backing off to hourly) and moves it to its
  new address. It never streams one camera under another camera's name.
- The login is stored in the app's private storage on the TV. The phone setup page only runs while that
  screen is open and needs the random key from the QR code.

## Build
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
Signed with `release.keystore` (password in `keystore.properties`). Keep both files: Android only installs
updates signed with the same key. `./gradlew testDebugUnitTest` runs the proxy test against a fake RTSPS camera.
