package app.lifeboat.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random

class CryptoTest {
    private val master = Crypto.random(32)

    private fun roundTrip(size: Int) {
        val data = Random(size).nextBytes(size)
        val enc = ByteArrayOutputStream()
        val r1 = Crypto.encrypt(master, ByteArrayInputStream(data), enc)
        val dec = ByteArrayOutputStream()
        val r2 = Crypto.decrypt(master, ByteArrayInputStream(enc.toByteArray()), dec)
        assertArrayEquals(data, dec.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data).hex(), r1.sha256)
        assertEquals(r1.sha256, r2.sha256)
        assertEquals(size.toLong(), r2.size)
    }

    @Test fun roundTrips() {
        for (s in listOf(0, 1, 1000, Crypto.CHUNK - 1, Crypto.CHUNK, Crypto.CHUNK + 1, 3 * Crypto.CHUNK, 3 * Crypto.CHUNK + 12345)) roundTrip(s)
    }

    private fun mustFail(bytes: ByteArray, key: ByteArray = master) {
        try { Crypto.decrypt(key, ByteArrayInputStream(bytes), ByteArrayOutputStream()); fail("should fail") } catch (e: Exception) {}
    }

    @Test fun detectsTamperTruncationWrongKey() {
        val data = Random(1).nextBytes(2 * Crypto.CHUNK + 10)
        val enc = ByteArrayOutputStream().also { Crypto.encrypt(master, ByteArrayInputStream(data), it) }.toByteArray()
        mustFail(enc.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        val firstChunkEnd = 4 + 16 + 1 + 4 + Crypto.CHUNK + 16
        mustFail(enc.copyOf(firstChunkEnd))            // dropped the tail chunks
        mustFail(enc.copyOf(enc.size - 5))
        mustFail(enc, Crypto.random(32))
    }

    @Test fun passwordWrap() {
        val w = Crypto.wrapWithPassword(master, "correct horse".toCharArray())
        assertArrayEquals(master, Crypto.unwrapWithPassword(w, "correct horse".toCharArray()))
        try { Crypto.unwrapWithPassword(w, "wrong".toCharArray()); fail() } catch (e: Exception) {}
    }

    @Test fun takeoutSidecars() {
        val s = Sidecars()
        s.addJson("Takeout/Google Photos/Photos from 2014", "IMG_0042.JPG.supplemental-metadata.json",
            """{"title":"IMG_0042.JPG","photoTakenTime":{"timestamp":"1400000000"}}""")
        s.addJson("Takeout/Google Photos/Photos from 2014", "VID_9.mp4.json",
            """{"title":"VID_9.mp4","photoTakenTime":{"timestamp":"1400000100"}}""")
        assertEquals(1400000000000L, s.lookup("Takeout/Google Photos/Photos from 2014", "IMG_0042.JPG"))
        assertEquals(1400000000000L, s.lookup("Takeout/Google Photos/Photos from 2014", "IMG_0042-edited.JPG"))
        assertEquals(1400000100000L, s.lookup("Takeout/Google Photos/Photos from 2014", "VID_9.mp4"))
        assertTrue(s.lookup("Takeout/Google Photos/Photos from 2014", "nope.jpg") == null)
    }
}
