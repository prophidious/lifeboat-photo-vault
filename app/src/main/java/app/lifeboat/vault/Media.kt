package app.lifeboat.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object Media {

    fun sniffMime(f: File, name: String): String {
        guessMime(name)?.takeIf { isMediaMime(it) }?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        o.outMimeType?.let { return it }
        return try {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(f.path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) } finally { r.release() }
        } catch (e: Exception) { null } ?: "application/octet-stream"
    }

    /** Date the photo/video was taken, from EXIF or video metadata. */
    fun dateTaken(f: File, mime: String): Long? = try {
        if (mime.startsWith("video/")) videoDate(f) else exifDate(f)
    } catch (e: Exception) { null }

    private fun exifDate(f: File): Long? {
        val exif = ExifInterface(f.path)
        val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        val ms = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s.trim())?.time ?: return null
        return ms.takeIf { plausible(it) }
    }

    private fun videoDate(f: File): Long? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
            for (p in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
                try {
                    val fmt = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val ms = fmt.parse(s)?.time ?: continue
                    if (plausible(ms)) return ms
                } catch (_: Exception) {}
            }
            return null
        } finally { r.release() }
    }

    private fun plausible(ms: Long) = ms > 315532800000L && ms < System.currentTimeMillis() + 86400000L * 2 // after 1980

    fun decodeSampled(f: File, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        return rotateForExif(f, bmp)
    }

    private fun rotateForExif(f: File, bmp: Bitmap): Bitmap = try {
        val exif = ExifInterface(f.path)
        val deg = exif.rotationDegrees
        val flip = exif.isFlipped
        if (deg == 0 && !flip) bmp else {
            val m = Matrix()
            if (flip) m.postScale(-1f, 1f)
            m.postRotate(deg.toFloat())
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    } catch (e: Exception) { bmp }

    fun videoFrame(f: File, size: Int): Bitmap? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
                ?: r.frameAtTime
        } finally { r.release() }
    } catch (e: Exception) { null }

    fun thumbnail(f: File, mime: String, size: Int = 360): Bitmap? {
        val b = (if (mime.startsWith("video/")) videoFrame(f, size) else decodeSampled(f, size)) ?: return null
        val side = minOf(b.width, b.height)
        val cropped = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side > size) Bitmap.createScaledBitmap(cropped, size, size, true) else cropped
    }
}
