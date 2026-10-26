package app.lifeboat.vault

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Raw, read-only access to a whole disk (SD card through a USB reader, or a disk image file).
 * There is deliberately NO write method anywhere in this interface.
 */
interface BlockSource : Closeable {
    val size: Long
    val sectorSize: Int
    /** Reads [len] bytes (a multiple of [sectorSize]) at sector-aligned [off]. Returns bytes read; throws IOException on bad areas. */
    fun readAligned(off: Long, buf: ByteArray, len: Int): Int
}

class FileBlockSource(f: File, override val sectorSize: Int = 512) : BlockSource {
    private val raf = RandomAccessFile(f, "r")
    override val size: Long = raf.length()
    override fun readAligned(off: Long, buf: ByteArray, len: Int): Int {
        if (off >= size) return 0
        raf.seek(off)
        var n = 0
        while (n < len) { val r = raf.read(buf, n, len - n); if (r < 0) break; n += r }
        return n
    }
    override fun close() = raf.close()
}

/** Byte-granular reads on top of sector-aligned I/O, with a small read-ahead window. Unreadable sectors read as missing. */
class DiskReader(val src: BlockSource) {
    private val ss = src.sectorSize
    private var cacheOff = -1L
    private var cache = ByteArray(0)
    private var cacheLen = 0

    private fun fill(start: Long, want: Int) {
        if (cache.size < want) cache = ByteArray(want)
        cacheOff = start
        cacheLen = try { src.readAligned(start, cache, want) } catch (e: IOException) {
            // A bad spot: retry in small pieces so one bad sector doesn't hide what comes before it.
            var got = 0
            val piece = 64 * 1024
            val tmp = ByteArray(piece)
            while (got < want) {
                val n = minOf(piece, want - got)
                val r = try { src.readAligned(start + got, tmp, n) } catch (_: IOException) { -1 }
                if (r <= 0) break
                System.arraycopy(tmp, 0, cache, got, r)
                got += r
                if (r < n) break
            }
            got
        }
    }

    /** Up to [n] bytes at [off]; shorter at the end of the disk or before a bad area. */
    fun read(off: Long, n: Int): ByteArray {
        if (off < 0 || off >= src.size) return ByteArray(0)
        val start = off - off % ss
        var end = off + n
        end += (ss - end % ss) % ss
        if (cacheOff >= 0 && start >= cacheOff && end <= cacheOff + cacheLen) {
            val s = (off - cacheOff).toInt()
            return cache.copyOfRange(s, s + n)
        }
        val want = maxOf((end - start).toInt(), 1024 * 1024)
        val remain = src.size - start
        val remainAligned = remain + (ss - remain % ss) % ss
        fill(start, minOf(want.toLong(), remainAligned).toInt())
        val s = (off - start).toInt()
        if (s >= cacheLen) return ByteArray(0)
        return cache.copyOfRange(s, minOf(s + n, cacheLen))
    }

    fun copyTo(off: Long, n: Long, out: OutputStream): Long {
        var done = 0L
        while (done < n) {
            val chunk = read(off + done, minOf(4L * 1024 * 1024, n - done).toInt())
            if (chunk.isEmpty()) break
            out.write(chunk)
            done += chunk.size
        }
        return done
    }
}

/**
 * Finds deleted photos and videos by their own internal structure (file carving), the same
 * way PhotoRec and tools/rescue.py do: deleting a file only marks its space as free, the bytes
 * stay on the card until something overwrites them.
 */
class Carver(src: BlockSource) {
    data class Found(val offset: Long, val length: Long, val ext: String)

    val disk = DiskReader(src)
    private val size = src.size
    private val ss = src.sectorSize
    var badBlocks = 0
        private set

    companion object {
        const val BLOCK = 4 * 1024 * 1024
        const val MAX_IMAGE = 200L * 1024 * 1024
        const val MAX_VIDEO = 16L * 1024 * 1024 * 1024
        private val ISO_BOXES = setOf(
            "ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "meta", "pnot", "PICT", "pdin", "moof",
            "mfra", "sidx", "styp", "emsg", "prft", "junk", "udta", "idat", "iloc"
        )
        private val HEIF = setOf("heic", "heix", "mif1", "msf1", "heim", "heis")

        fun extForBrand(brand: String): String = when {
            brand == "qt  " -> ".mov"
            brand == "avif" -> ".avif"
            brand in HEIF -> ".heic"
            brand.startsWith("3g") -> ".3gp"
            else -> ".mp4"
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun be64(b: ByteArray, i: Int) = (be32(b, i) shl 32) or be32(b, i + 4)

    fun jpegLen(o: Long): Long? {
        val head = disk.read(o, 3)
        if (head.size < 3 || u8(head, 0) != 0xFF || u8(head, 1) != 0xD8 || u8(head, 2) != 0xFF) return null
        var p = o + 2
        while (p - o < MAX_IMAGE) {
            val h = disk.read(p, 4)
            if (h.size < 2 || u8(h, 0) != 0xFF) return null
            val m = u8(h, 1)
            if (m == 0xFF) { p += 1; continue }
            if (m == 0xD9) return p + 2 - o
            if (m in 0xD0..0xD7 || m == 0x01) { p += 2; continue }
            if (h.size < 4) return null
            val seg = be16(h, 2)
            if (seg < 2) return null
            p += 2 + seg
            if (m == 0xDA) { // compressed image data: walk to the next real marker
                var found = false
                while (!found && p - o < MAX_IMAGE) {
                    val buf = disk.read(p, 1024 * 1024)
                    if (buf.size < 2) return null
                    var i = 0
                    while (i + 1 < buf.size) {
                        if (u8(buf, i) != 0xFF) { i++; continue }
                        val nb = u8(buf, i + 1)
                        if (nb == 0x00 || nb in 0xD0..0xD7) { i += 2; continue }
                        if (nb == 0xFF) { i += 1; continue }
                        p += i; found = true; break
                    }
                    if (!found) p += maxOf(buf.size - 1, 1)
                }
                if (!found) return null
            }
        }
        return null
    }

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    fun pngLen(o: Long): Long? {
        if (!disk.read(o, 8).contentEquals(PNG_SIG)) return null
        var p = o + 8
        while (p - o < MAX_IMAGE) {
            val h = disk.read(p, 8)
            if (h.size < 8) return null
            val len = be32(h, 0)
            val typ = String(h, 4, 4, Charsets.ISO_8859_1)
            if (!typ.all { it.isLetter() && it.code < 128 }) return null
            p += 12 + len
            if (typ == "IEND") return p - o
        }
        return null
    }

    /** MP4 / MOV / HEIC / 3GP: a chain of boxes. Returns length and brand. */
    fun isoLen(o: Long): Pair<Long, String>? {
        val h = disk.read(o, 12)
        if (h.size < 12 || String(h, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
        val brand = String(h, 8, 4, Charsets.ISO_8859_1)
        var p = o
        val seen = HashSet<String>()
        while (p - o < MAX_VIDEO) {
            val b = disk.read(p, 16)
            if (b.size < 8) break
            var sz = be32(b, 0)
            val typ = String(b, 4, 4, Charsets.ISO_8859_1)
            if (typ !in ISO_BOXES) break
            if (sz == 1L) { if (b.size < 16) break; sz = be64(b, 8) }
            if (sz < 8) break
            seen += typ
            p += sz
        }
        return if ("mdat" in seen && ("moov" in seen || "meta" in seen || "moof" in seen)) (p - o) to brand else null
    }

    private fun candidateAt(c: Long): Found? {
        val head = disk.read(c, 12)
        if (head.size < 12) return null
        return when {
            u8(head, 0) == 0xFF && u8(head, 1) == 0xD8 && u8(head, 2) == 0xFF ->
                jpegLen(c)?.takeIf { it > 1024 }?.let { Found(c, it, ".jpg") }
            u8(head, 0) == 0x89 && head[1] == 'P'.code.toByte() -> pngLen(c)?.let { Found(c, it, ".png") }
            String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> isoLen(c)?.let { Found(c, it.first, extForBrand(it.second)) }
            else -> null
        }?.takeIf { it.offset + it.length <= size }
    }

    /**
     * Scans every sector. [onFound] gets each complete file; [onProgress] gets bytes scanned.
     * Return false from [keepGoing] to stop early.
     */
    fun scan(onFound: (Found) -> Unit, onProgress: (Long) -> Unit = {}, keepGoing: () -> Boolean = { true }) {
        val src = disk.src
        val block = ByteArray(BLOCK)
        var off = 0L
        while (off < size && keepGoing()) {
            val want = minOf(BLOCK.toLong(), size - off).toInt().let { it - it % ss }.coerceAtLeast(ss)
            val n = try { src.readAligned(off, block, want) } catch (e: IOException) { -1 }
            if (n < 0) { badBlocks++; off += want; onProgress(off); continue }   // unreadable area: skip it
            if (n == 0) break
            val cands = ArrayList<Long>()
            var i = 0
            while (i + 12 <= n) {
                val b0 = block[i].toInt() and 0xff
                if ((b0 == 0xFF && (block[i + 1].toInt() and 0xff) == 0xD8 && (block[i + 2].toInt() and 0xff) == 0xFF) ||
                    (b0 == 0x89 && block[i + 1] == 'P'.code.toByte() && block[i + 2] == 'N'.code.toByte() && block[i + 3] == 'G'.code.toByte()) ||
                    (block[i + 4] == 'f'.code.toByte() && block[i + 5] == 't'.code.toByte() && block[i + 6] == 'y'.code.toByte() && block[i + 7] == 'p'.code.toByte())
                ) cands += off + i
                i += ss
            }
            for (c in cands) {
                if (!keepGoing()) return
                candidateAt(c)?.let(onFound)
            }
            off += n
            onProgress(off)
        }
    }
}
