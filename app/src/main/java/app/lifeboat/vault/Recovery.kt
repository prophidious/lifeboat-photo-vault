package app.lifeboat.vault

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** A USB card reader / USB stick, talked to directly (no root needed: Android's USB host API). */
class UsbReader private constructor(
    private val conn: UsbDeviceConnection, private val intf: UsbInterface,
    private val inEp: UsbEndpoint, private val outEp: UsbEndpoint
) : BulkTransport {

    companion object {
        private fun massStorage(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50 }

        fun find(ctx: Context): List<UsbDevice> =
            (ctx.getSystemService(UsbManager::class.java)?.deviceList?.values ?: emptyList<UsbDevice>()).filter { massStorage(it) != null }

        fun label(d: UsbDevice): String = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB card reader" }

        fun open(ctx: Context, d: UsbDevice): UsbReader {
            val um = ctx.getSystemService(UsbManager::class.java)
            val intf = massStorage(d) ?: throw IOException("That USB device isn't a card reader.")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val e = intf.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
            }
            if (inEp == null || outEp == null) throw IOException("Card reader is missing its data pipes.")
            val conn = um.openDevice(d) ?: throw IOException("Android didn't allow access to the card reader.")
            // force=true: take it from Android's own driver while we read (it gets it back when we're done).
            if (!conn.claimInterface(intf, true)) { conn.close(); throw IOException("Couldn't take over the card reader.") }
            return UsbReader(conn, intf, inEp, outEp)
        }
    }

    override fun bulkOut(data: ByteArray, len: Int, timeoutMs: Int) = conn.bulkTransfer(outEp, data, 0, len, timeoutMs)
    override fun bulkIn(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) = conn.bulkTransfer(inEp, buf, off, len, timeoutMs)
    private fun clearHalt(ep: UsbEndpoint) { conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 2000) }
    override fun clearHaltIn() = clearHalt(inEp)
    override fun clearHaltOut() = clearHalt(outEp)
    override fun resetRecovery() {
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, 2000) // Bulk-Only Mass Storage Reset
        clearHaltIn(); clearHaltOut()
    }

    fun close() {
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        conn.close()
    }
}

object Recovery {

    /** Scans the whole card in the USB reader and puts every photo/video it finds into the vault. */
    fun fromUsb(ctx: Context, device: UsbDevice, r: Reporter): String {
        val reader = UsbReader.open(ctx, device)
        try {
            r.current("Connecting to the card…")
            val src = ScsiBlockSource(reader).init()
            return scan(ctx, src, "${UsbReader.label(device)} · ${humanBytes(src.size)} card", r)
        } finally {
            reader.close()
        }
    }

    fun scan(ctx: Context, src: BlockSource, what: String, r: Reporter): String {
        val v = Vault.get(ctx)
        val carver = Carver(src)
        val tmpDir = Vault.plainDir(ctx)
        var added = 0; var dupes = 0; var failed = 0; var found = 0
        var stopReason: String? = null
        val t0 = System.currentTimeMillis()
        Jobs.update { it.copy(pct = true, total = 1000, done = 0) }

        carver.scan(
            onFound = found@{ f ->
                found++
                if (v.root.usableSpace < 300L * 1024 * 1024 || ctx.cacheDir.usableSpace < f.length + 100L * 1024 * 1024) {
                    stopReason = "Your phone ran out of space. Free some up, then run Recover again. Photos already found are kept, and the ones found again are skipped."
                    return@found
                }
                val tmp = File(tmpDir, "carve-${f.offset}${f.ext}")
                try {
                    val got = FileOutputStream(tmp).buffered(1 shl 16).use { carver.disk.copyTo(f.offset, f.length, it) }
                    if (got == f.length) {
                        val name = "recovered_${java.lang.Long.toHexString(f.offset)}${f.ext}"
                        when (v.add(name, guessMime(name), null, null, "recovered") { FileInputStream(tmp) }) {
                            Vault.Added.NEW -> added++
                            Vault.Added.DUPLICATE -> dupes++
                        }
                    }
                } catch (e: Exception) {
                    failed++   // a damaged leftover that isn't really a photo; skip it
                } finally { tmp.delete() }
                val a = added; val d = dupes
                Jobs.update { it.copy(added = a, dupes = d) }
            },
            onProgress = { pos ->
                val pm = (pos * 1000 / src.size).toInt().coerceIn(0, 1000)
                val a = added
                Jobs.update { it.copy(done = pm, current = "Found $a photos & videos so far · ${humanBytes(pos)} of ${humanBytes(src.size)} scanned") }
            },
            keepGoing = { stopReason == null && !r.cancelled }
        )
        v.flush()
        val mins = (System.currentTimeMillis() - t0) / 60000
        return buildString {
            append("Scanned: $what")
            append(if (mins > 0) " in $mins min.\n\n" else ".\n\n")
            append("✅ $added photos & videos recovered, checked and put in the vault.")
            if (dupes > 0) append("\n$dupes were already in the vault (skipped).")
            if (carver.badBlocks > 0) append("\n⚠️ ${carver.badBlocks} damaged areas of the card couldn't be read and were skipped.")
            stopReason?.let { append("\n\n⚠️ Stopped early: $it") }
            append("\n\nRecovered files are named \"recovered_…\" and sorted by the date inside them. Some may be small thumbnails or partly overwritten. Long-press anything you don't want to delete it.")
            if (added == 0 && stopReason == null) append("\n\nNothing came back. The deleted photos have probably been overwritten. Try a computer with PhotoRec as a second opinion.")
            append("\n\nLifeboat only read the card. Nothing on it was changed.")
        }
    }
}
