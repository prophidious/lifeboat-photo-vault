package app.lifeboat.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class RecoveryTest {

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("carve/$name")!!.readBytes()

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()

    private fun mp4(seed: Int, brand: String = "isom"): ByteArray {
        val ftyp = box("ftyp", (brand + "\u0000\u0000\u0002\u0000" + "isomiso2mp41").toByteArray(Charsets.ISO_8859_1))
        val mdat = box("mdat", Random(seed).nextBytes(300_000))
        val moov = box("moov", box("mvhd", ByteArray(100)))
        return ftyp + mdat + moov
    }

    /** Builds a fake 64 MB "SD card": noise, plus photos/videos at sector boundaries, plus traps. */
    private data class Disk(val file: File, val planted: Map<Long, ByteArray>)

    private fun makeDisk(): Disk {
        val size = 64 * 1024 * 1024
        val buf = Random(42).nextBytes(size)
        val planted = LinkedHashMap<Long, ByteArray>()
        fun put(off: Int, b: ByteArray, record: Boolean = true) {
            System.arraycopy(b, 0, buf, off, b.size); if (record) planted[off.toLong()] = b
        }
        put(512 * 7, res("p1.jpg"))
        put(4 * 1024 * 1024 - 512, res("p2.jpg"))          // straddles a scan block boundary
        put(9 * 1024 * 1024, res("p.png"))
        put(13 * 1024 * 1024, mp4(4))
        put(20 * 1024 * 1024, mp4(5, "qt  "))
        put(30 * 1024 * 1024 + 100, res("p4.jpg"), record = false) // not sector-aligned: real files never start there
        val trunc = res("p3.jpg")
        put(size - trunc.size / 2, trunc.copyOf(trunc.size / 2), record = false) // cut off by the end of the card
        val f = File.createTempFile("card", ".img").apply { deleteOnExit(); writeBytes(buf) }
        return Disk(f, planted)
    }

    private fun carveAll(src: BlockSource): Map<Long, Pair<String, ByteArray>> {
        val c = Carver(src)
        val out = LinkedHashMap<Long, Pair<String, ByteArray>>()
        c.scan(onFound = { f ->
            val bos = ByteArrayOutputStream()
            c.disk.copyTo(f.offset, f.length, bos)
            out[f.offset] = f.ext to bos.toByteArray()
        })
        return out
    }

    private fun checkFound(found: Map<Long, Pair<String, ByteArray>>, d: Disk) {
        for ((off, bytes) in d.planted) {
            val got = found[off] ?: throw AssertionError("missed file at $off; found ${found.keys}")
            assertArrayEquals("file at $off", bytes, got.second)
        }
        assertEquals(".mov", found[20L * 1024 * 1024]!!.first)
        assertEquals(".mp4", found[13L * 1024 * 1024]!!.first)
        assertEquals(".png", found[9L * 1024 * 1024]!!.first)
        val stray = found.keys - d.planted.keys
        // Random noise can, very rarely, look like a header; it must never be a "complete" file of ours.
        assertTrue("unexpected finds: $stray", stray.all { found[it]!!.second.size < 4096 })
    }

    @Test fun carvesPlantedFilesExactly() {
        val d = makeDisk()
        FileBlockSource(d.file).use { checkFound(carveAll(it), d) }
    }

    // ---------- fake USB card reader speaking Bulk-Only Transport + SCSI ----------
    private class FakeReader(val disk: ByteArray, val badLba: Set<Long> = emptySet()) : BulkTransport {
        val opcodes = ArrayList<Int>()
        private var pendingData: ByteArray? = null
        private var dataPos = 0
        private var csw: ByteArray? = null
        private var lastSenseKey = 0

        private fun status(tag: Int, residue: Int, st: Int) = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x53425355).putInt(tag).putInt(residue).put(st.toByte()).array()

        override fun bulkOut(data: ByteArray, len: Int, timeoutMs: Int): Int {
            val bb = ByteBuffer.wrap(data, 0, 31).order(ByteOrder.LITTLE_ENDIAN)
            check(bb.getInt(0) == 0x43425355)
            val tag = bb.getInt(4); val xfer = bb.getInt(8)
            val cb = data.copyOfRange(15, 15 + data[14])
            val op = cb[0].toInt() and 0xff
            opcodes += op
            fun be(i: Int, n: Int): Long { var v = 0L; for (k in 0 until n) v = (v shl 8) or (cb[i + k].toLong() and 0xff); return v }
            var st = 0
            pendingData = when (op) {
                0x12 -> ByteArray(36).also { "FAKE    SD Reader       ".toByteArray().copyInto(it, 8) }
                0x00 -> null
                0x03 -> ByteArray(18).also { it[2] = lastSenseKey.toByte() }
                0x25 -> ByteBuffer.allocate(8).putInt(disk.size / 512 - 1).putInt(512).array()
                0x28 -> {
                    val lba = be(2, 4); val n = be(7, 2).toInt()
                    if ((lba until lba + n).any { it in badLba }) { st = 1; lastSenseKey = 3; null }
                    else disk.copyOfRange((lba * 512).toInt(), ((lba + n) * 512).toInt())
                }
                else -> error("unexpected opcode $op")
            }
            dataPos = 0
            csw = status(tag, if (st == 0) 0 else xfer, st)
            return len
        }

        override fun bulkIn(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
            pendingData?.let { d ->
                if (dataPos < d.size) {
                    val n = minOf(len, d.size - dataPos)
                    System.arraycopy(d, dataPos, buf, off, n); dataPos += n
                    if (dataPos >= d.size) pendingData = null
                    return n
                }
            }
            if (pendingData == null && csw != null && len == 13) { csw!!.copyInto(buf, off); csw = null; return 13 }
            return -1 // stall: device expected to send status instead
        }
        override fun clearHaltIn() { pendingData = null }
        override fun clearHaltOut() {}
        override fun resetRecovery() {}
    }

    @Test fun usbReaderReadsExactBytesAndNeverWrites() {
        val d = makeDisk()
        val bytes = d.file.readBytes()
        val fake = FakeReader(bytes)
        val src = ScsiBlockSource(fake).init()
        assertEquals(bytes.size.toLong(), src.size)
        assertTrue(src.vendor.contains("FAKE"))
        checkFound(carveAll(src), d)
        // Only reading commands were ever sent to the card.
        assertTrue(fake.opcodes.toSet().all { it in setOf(0x00, 0x03, 0x12, 0x25, 0x28) })
    }

    @Test fun badSectorsAreSkippedNotFatal() {
        val d = makeDisk()
        val bytes = d.file.readBytes()
        val bad = setOf(40L * 1024 * 1024 / 512 + 3)   // a bad sector in an area with no planted file
        val src = ScsiBlockSource(FakeReader(bytes, bad)).init()
        try { src.readAligned(40L * 1024 * 1024, ByteArray(4096), 4096); throw AssertionError("should fail") } catch (_: IOException) {}
        val c = Carver(src)
        val found = carveAll(src)
        checkFound(found, d)
    }

    @Test fun refusesWriteCommands() {
        val src = ScsiBlockSource(FakeReader(ByteArray(1 shl 20)))
        for (op in listOf(0x2A, 0x8A, 0xAA, 0x35, 0x04, 0x1B)) {
            try { src.command(byteArrayOf(op.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0), null, 0); throw AssertionError("allowed $op") }
            catch (_: IllegalArgumentException) {}
        }
    }

    /** Optional: compare against tools/rescue.py on a real FAT image (set LIFEBOAT_TEST_IMAGE). */
    @Test fun realImageIfProvided() {
        val p = System.getenv("LIFEBOAT_TEST_IMAGE") ?: return
        val outDir = File(System.getenv("LIFEBOAT_TEST_OUT") ?: return).apply { mkdirs() }
        FileBlockSource(File(p)).use { src ->
            val found = carveAll(src)
            for ((off, v) in found) File(outDir, "$off${v.first}").writeBytes(v.second)
            println("REAL_IMAGE_FOUND ${found.size}")
        }
    }
}
