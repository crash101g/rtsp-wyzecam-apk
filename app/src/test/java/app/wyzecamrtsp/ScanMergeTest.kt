package app.wyzecamrtsp

import app.wyzecamrtsp.Scanner.Found
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scan results are matched to saved cameras by identity (MAC / TLS key) first, then by IP. */
class ScanMergeTest {
    private val port = 322

    @Test fun cameraWithNewIpKeepsNameAndSettings() {
        val front = Cam("Front door", "192.168.1.10", key = "ka", stream = MAIN, enabled = false)
        val cams = mutableListOf(front)
        val moves = Scanner.merge(cams, listOf(Found("192.168.1.20", true, key = "ka")), port)
        assertEquals(1, cams.size)
        assertEquals("192.168.1.20", front.ip)
        assertEquals("Front door", front.name)
        assertEquals(MAIN, front.stream)
        assertFalse(front.enabled)
        assertEquals(listOf("Front door: 192.168.1.10 → 192.168.1.20"), moves)
    }

    @Test fun twoCamerasSwappingAddressesAreBothFollowed() {
        val a = Cam("Garage", "192.168.1.10", key = "ka")
        val b = Cam("Yard", "192.168.1.11", key = "kb")
        val cams = mutableListOf(a, b)
        val moves = Scanner.merge(cams, listOf(Found("192.168.1.10", true, key = "kb"),
            Found("192.168.1.11", true, key = "ka")), port)
        assertEquals("192.168.1.11", a.ip)
        assertEquals("192.168.1.10", b.ip)
        assertEquals(listOf(a, b), cams)          // grid order unchanged
        assertEquals(2, moves.size)
    }

    @Test fun cameraSavedBeforeKeysExistedIsMatchedByIpAndLearnsItsKey() {
        val old = Cam("Porch", "192.168.1.10")
        val cams = mutableListOf(old)
        assertTrue(Scanner.merge(cams, listOf(Found("192.168.1.10", true, key = "kx", mac = "80:48:2c:00:00:01")), port).isEmpty())
        assertEquals(1, cams.size)
        assertEquals("kx", old.key)
        assertEquals("80:48:2c:00:00:01", old.mac)
    }

    @Test fun newCertificateAtTheSameIpIsStillTheSameCamera() {
        val c = Cam("Porch", "192.168.1.10", key = "old")
        val cams = mutableListOf(c)
        Scanner.merge(cams, listOf(Found("192.168.1.10", true, key = "new")), port)
        assertEquals(1, cams.size)
        assertEquals("new", c.key)
        assertTrue(c.ok)
    }

    @Test fun macWinsOverTheTlsKey() {
        val c = Cam("Shed", "192.168.1.10", key = "k1", mac = "80:48:2c:00:00:01")
        val cams = mutableListOf(c)
        Scanner.merge(cams, listOf(Found("192.168.1.30", true, key = "k2", mac = "80:48:2C:00:00:01")), port)
        assertEquals(1, cams.size)
        assertEquals("192.168.1.30", c.ip)
        assertEquals("k2", c.key)
    }

    @Test fun differentMacAtTheOldIpIsADifferentCamera() {
        val c = Cam("Shed", "192.168.1.10", mac = "80:48:2c:00:00:01")
        val cams = mutableListOf(c)
        Scanner.merge(cams, listOf(Found("192.168.1.10", true, mac = "80:48:2c:00:00:02")), port)
        assertFalse(c.ok)
        assertEquals(2, cams.size)
        assertEquals("Camera 10", cams[1].name)
    }

    @Test fun missingCamerasStayOfflineAndBackgroundScansAddNothing() {
        val gone = Cam("Attic", "192.168.1.10", key = "ka")
        val cams = mutableListOf(gone)
        Scanner.merge(cams, listOf(Found("192.168.1.50", true, key = "kz")), port, addNew = false)
        assertEquals(listOf(gone), cams)
        assertFalse(gone.ok)
        assertEquals("192.168.1.10", gone.ip)

        Scanner.merge(cams, listOf(Found("192.168.1.50", true, key = "kz")), port)
        assertEquals(2, cams.size)
        assertEquals("kz", cams[1].key)
    }
}
