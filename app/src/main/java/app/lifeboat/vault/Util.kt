package app.lifeboat.vault

import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ByteArray.hex(): String {
    val chars = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
    }
    return sb.toString()
}

/** Reads until [len] bytes or end of stream. Returns bytes read. */
fun readFully(ins: InputStream, buf: ByteArray, len: Int = buf.size): Int {
    var n = 0
    while (n < len) {
        val r = ins.read(buf, n, len - n)
        if (r < 0) break
        n += r
    }
    return n
}

object NullOut : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

/** Lets a ZipInputStream entry be "closed" by a consumer without closing the zip. */
class NonClosingInputStream(private val inner: InputStream) : InputStream() {
    override fun read(): Int = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
    override fun available(): Int = inner.available()
    override fun close() {}
}

fun File.writeAtomic(bytes: ByteArray) {
    val tmp = File(parentFile, "$name.part")
    FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
    if (!tmp.renameTo(this)) {
        tmp.delete()
        throw IOException("Could not write $name")
    }
}

private val extraMimes = mapOf(
    "heic" to "image/heic", "heif" to "image/heif", "webp" to "image/webp",
    "dng" to "image/x-adobe-dng", "mkv" to "video/x-matroska", "3gp" to "video/3gpp",
    "mts" to "video/mp2t", "m2ts" to "video/mp2t", "avi" to "video/x-msvideo"
)

fun guessMime(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext.isEmpty()) return null
    return extraMimes[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

fun isMediaMime(mime: String?) = mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))

fun isMedia(mime: String?, name: String): Boolean = isMediaMime(mime) || isMediaMime(guessMime(name))

fun humanBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatDate(ms: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
fun yearOf(ms: Long): String = SimpleDateFormat("yyyy", Locale.US).format(Date(ms))

/** Copies [ins] to [out] while computing SHA-256 and size. */
fun copyHash(ins: InputStream, out: OutputStream): Crypto.Result {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 16)
    var total = 0L
    while (true) {
        val r = ins.read(buf)
        if (r < 0) break
        md.update(buf, 0, r); out.write(buf, 0, r); total += r
    }
    out.flush()
    return Crypto.Result(md.digest().hex(), total)
}

fun monthFolder(ms: Long): String = SimpleDateFormat("MM-MMMM", Locale.US).format(Date(ms))
