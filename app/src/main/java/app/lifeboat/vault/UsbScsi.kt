package app.lifeboat.vault

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The two USB bulk pipes of a card reader. Implemented on Android by [UsbReader]; faked in tests. */
interface BulkTransport {
    /** Sends [len] bytes. Returns bytes sent or <0 on error. */
    fun bulkOut(data: ByteArray, len: Int, timeoutMs: Int): Int
    /** Receives up to [len] bytes into [buf] at [off]. Returns bytes received or <0 on error/stall. */
    fun bulkIn(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int
    fun clearHaltIn()
    fun clearHaltOut()
    fun resetRecovery()
}

/**
 * USB Mass Storage "Bulk-Only Transport" + SCSI, READ-ONLY.
 * The only commands this class can ever send are: TEST UNIT READY, REQUEST SENSE, INQUIRY,
 * READ CAPACITY and READ. There is no write command in here, so the card can't be changed.
 */
class ScsiBlockSource(private val t: BulkTransport, private val lun: Int = 0) : BlockSource {
    override var size: Long = 0; private set
    override var sectorSize: Int = 512; private set
    var vendor: String = ""; private set
    private var tag = 1
    private val csw = ByteArray(13)

    companion object {
        const val MAX_SECTORS_PER_CMD = 256          // 128 KB per READ with 512-byte sectors
        const val TIMEOUT = 5000
        val ALLOWED_OPCODES = setOf(0x00, 0x03, 0x12, 0x25, 0x28, 0x88, 0x9E)
    }

    class ScsiException(msg: String, val sense: Int = -1) : IOException(msg)

    fun init(): ScsiBlockSource {
        val inq = ByteArray(36)
        val n = command(byteArrayOf(0x12, 0, 0, 0, 36, 0), inq, 36)
        if (n >= 32) vendor = String(inq, 8, 24, Charsets.ISO_8859_1).trim().replace(Regex("\\s+"), " ")
        // Card readers often report "not ready" for a moment after plugging in.
        var ready = false
        for (i in 0 until 10) {
            try { command(ByteArray(6), null, 0); ready = true; break } catch (e: ScsiException) {
                requestSense(); Thread.sleep(400)
            }
        }
        if (!ready) throw IOException("The card reader says no card is inserted (or it isn't ready).")
        val cap = ByteArray(8)
        command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), cap, 8)
        var lastLba = be32(cap, 0)
        sectorSize = be32(cap, 4).toInt()
        if (lastLba == 0xFFFFFFFFL) { // > 2 TB
            val c16 = ByteArray(32)
            command(byteArrayOf(0x9E.toByte(), 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0), c16, 32)
            lastLba = (be32(c16, 0) shl 32) or be32(c16, 4)
            sectorSize = be32(c16, 8).toInt()
        }
        if (sectorSize !in listOf(512, 1024, 2048, 4096)) throw IOException("Unsupported sector size $sectorSize")
        size = (lastLba + 1) * sectorSize
        if (size <= 0) throw IOException("The card reader reports an empty card.")
        return this
    }

    private fun be32(b: ByteArray, i: Int) = ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
        ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    fun requestSense(): Int = try {
        val s = ByteArray(18)
        command(byteArrayOf(0x03, 0, 0, 0, 18, 0), s, 18, sense = false)
        ((s[2].toInt() and 0x0f) shl 16) or ((s[12].toInt() and 0xff) shl 8) or (s[13].toInt() and 0xff)
    } catch (e: IOException) { -1 }

    /** Sends one SCSI command and reads its data (if any). Returns data bytes received. */
    @Synchronized
    fun command(cb: ByteArray, data: ByteArray?, len: Int, dataOff: Int = 0, sense: Boolean = true): Int {
        val op = cb[0].toInt() and 0xff
        require(op in ALLOWED_OPCODES) { "Refusing SCSI opcode $op: Lifeboat only reads." }
        val myTag = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x43425355).putInt(myTag).putInt(len)
            .put(if (len > 0) 0x80.toByte() else 0).put(lun.toByte()).put(cb.size.toByte())
            .put(cb).array()
        if (t.bulkOut(cbw, 31, TIMEOUT) != 31) { t.resetRecovery(); throw IOException("Card reader didn't accept a command") }

        var got = 0
        if (len > 0 && data != null) {
            while (got < len) {
                val want = minOf(64 * 1024, len - got)
                val r = t.bulkIn(data, dataOff + got, want, TIMEOUT)
                if (r < 0) { t.clearHaltIn(); break }   // stalled: the status comes next
                got += r
                if (r < want) break                      // short transfer = end of data
            }
        }
        var c = t.bulkIn(csw, 0, 13, TIMEOUT)
        if (c < 0) { t.clearHaltIn(); c = t.bulkIn(csw, 0, 13, TIMEOUT) }
        if (c != 13) { t.resetRecovery(); throw IOException("Card reader stopped responding") }
        val bb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x53425355 || bb.getInt(4) != myTag) { t.resetRecovery(); throw IOException("Card reader got out of sync") }
        when (csw[12].toInt()) {
            0 -> return got
            1 -> throw ScsiException("Read error", if (sense) requestSense() else -1)
            else -> { t.resetRecovery(); throw IOException("Card reader reported a phase error") }
        }
    }

    override fun readAligned(off: Long, buf: ByteArray, len: Int): Int {
        if (off % sectorSize != 0L || len % sectorSize != 0) throw IllegalArgumentException("unaligned read")
        if (off >= size) return 0
        val total = minOf(len.toLong(), size - off).toInt()
        var done = 0
        while (done < total) {
            val lba = (off + done) / sectorSize
            val count = minOf(MAX_SECTORS_PER_CMD, (total - done) / sectorSize)
            val bytes = count * sectorSize
            val cb = if (lba <= 0xFFFFFFFFL) byteArrayOf(
                0x28, 0, (lba ushr 24).toByte(), (lba ushr 16).toByte(), (lba ushr 8).toByte(), lba.toByte(),
                0, (count ushr 8).toByte(), count.toByte(), 0
            ) else byteArrayOf(
                0x88.toByte(), 0, (lba ushr 56).toByte(), (lba ushr 48).toByte(), (lba ushr 40).toByte(), (lba ushr 32).toByte(),
                (lba ushr 24).toByte(), (lba ushr 16).toByte(), (lba ushr 8).toByte(), lba.toByte(),
                (count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte(), 0, 0
            )
            var n = -1
            var last: IOException? = null
            for (attempt in 0 until 2) {
                try { n = command(cb, buf, bytes, done); break } catch (e: IOException) { last = e }
            }
            if (n < 0) throw last ?: IOException("Read failed")
            done += n
            if (n < bytes) break
        }
        return done
    }

    override fun close() {}
}
