package app.lifeboat.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Google Takeout puts each photo's real "taken" date in a JSON file next to it
 * (photo.jpg.json, or photo.jpg.supplemental-metadata.json in newer exports).
 */
class Sidecars {
    private val byDir = HashMap<String, MutableList<Pair<String, Long>>>()

    fun addJson(dir: String, fileName: String, text: String) {
        try {
            val o = JSONObject(text)
            val ts = o.optJSONObject("photoTakenTime")?.optString("timestamp")?.toLongOrNull()
                ?: o.optJSONObject("creationTime")?.optString("timestamp")?.toLongOrNull() ?: return
            val list = byDir.getOrPut(dir) { mutableListOf() }
            o.optString("title").takeIf { it.isNotEmpty() }?.let { list += it to ts * 1000 }
            list += fileName.removeSuffix(".json") to ts * 1000
        } catch (_: Exception) {}
    }

    fun lookup(dir: String, name: String): Long? {
        val list = byDir[dir] ?: return null
        fun find(n: String): Long? {
            list.firstOrNull { it.first == n }?.let { return it.second }
            list.firstOrNull { it.first.startsWith("$n.") }?.let { return it.second }       // x.jpg.supplemental-metadata
            list.firstOrNull { it.first.length >= 30 && n.startsWith(it.first) }?.let { return it.second } // truncated names
            return null
        }
        find(name)?.let { return it }
        // Edited copies share the original's sidecar: IMG_1-edited.jpg -> IMG_1.jpg
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        for (suffix in listOf("-edited", "-bearbeitet", "-modifié", "-editado")) {
            if (base.endsWith(suffix)) return find(base.removeSuffix(suffix) + if (ext.isNotEmpty()) ".$ext" else "")
        }
        return null
    }
}

object Importer {

    private data class Meta(val name: String, val size: Long, val modified: Long?)

    private fun meta(ctx: Context, uri: Uri): Meta {
        var name = uri.lastPathSegment ?: "photo"
        var size = -1L
        var modified: Long? = null
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it)?.let { n -> name = n } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                        ?.let { if (!c.isNull(it)) modified = c.getLong(it).takeIf { v -> v > 0 } }
                }
            }
        } catch (_: Exception) {}
        return Meta(name, size, modified)
    }

    /** Photos/videos picked one by one (system picker; Google Photos shows up there too). */
    fun importUris(ctx: Context, uris: List<Uri>, r: Reporter): String {
        val v = Vault.get(ctx)
        r.total(uris.size)
        for (u in uris) {
            val m = meta(ctx, u)
            val mime = ctx.contentResolver.getType(u)
            if (!isMedia(mime, m.name)) { r.skipped(); continue }
            r.addOne(m.name) {
                v.add(m.name, mime, null, m.modified, "picked") {
                    ctx.contentResolver.openInputStream(u) ?: throw IOException("Can't open")
                }
            }
        }
        v.flush()
        return summary("Picked photos", Jobs.state)
    }

    // ---------- whole folder (e.g. SD card DCIM, or an unzipped Takeout folder) ----------
    private data class Doc(val uri: Uri, val dir: String, val name: String, val mime: String?, val modified: Long?)

    fun importTree(ctx: Context, tree: Uri, r: Reporter): String {
        val v = Vault.get(ctx)
        val media = ArrayList<Doc>()
        val sidecars = Sidecars()
        r.current("Looking through the folder…")
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val stack = ArrayDeque<Pair<String, String>>()   // docId, path
        stack.add(rootId to "")
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        while (stack.isNotEmpty()) {
            val (docId, path) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                    val mod = if (c.isNull(3)) null else c.getLong(3).takeIf { it > 0 }
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name != ".thumbnails" && name != ".trashed") stack.add(id to "$path/$name")
                        continue
                    }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    if (name.endsWith(".json", true)) {
                        try {
                            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                                val bytes = ins.readBytes()
                                if (bytes.size < 1_000_000) sidecars.addJson(path, name, String(bytes, Charsets.UTF_8))
                            }
                        } catch (_: Exception) {}
                    } else if (isMedia(mime, name) && !name.startsWith(".trashed-")) {
                        media += Doc(uri, path, name, mime, mod)
                    }
                }
            }
        }
        r.total(media.size)
        for (d in media) {
            r.addOne(d.name) {
                v.add(d.name, d.mime, sidecars.lookup(d.dir, d.name), d.modified, "folder") {
                    ctx.contentResolver.openInputStream(d.uri) ?: throw IOException("Can't open")
                }
            }
        }
        v.flush()
        return summary("Folder", Jobs.state)
    }

    // ---------- Google Takeout .zip files, straight from Downloads, no unzipping ----------
    fun importTakeout(ctx: Context, zips: List<Uri>, r: Reporter): String {
        val v = Vault.get(ctx)
        // Pass 1: read the date sidecars and count photos (sidecars can come after the photo in the zip).
        val sidecars = Sidecars()
        var total = 0
        zips.forEachIndexed { i, z ->
            r.current("Reading Google's file ${i + 1} of ${zips.size}…")
            ZipInputStream(ctx.contentResolver.openInputStream(z) ?: throw IOException("Can't open zip")).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    val full = e.name
                    val name = full.substringAfterLast('/')
                    val dir = full.substringBeforeLast('/', "")
                    if (name.endsWith(".json", true)) {
                        val bos = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (bos.size() < 1_000_000) { val n = zis.read(buf); if (n < 0) break; bos.write(buf, 0, n) }
                        sidecars.addJson(dir, name, bos.toString("UTF-8"))
                    } else if (isMedia(null, name)) total++
                }
            }
        }
        r.total(total)
        // Pass 2: pull every photo/video into the vault.
        for (z in zips) {
            ZipInputStream(ctx.contentResolver.openInputStream(z) ?: throw IOException("Can't open zip")).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    val full = e.name
                    val name = full.substringAfterLast('/')
                    if (!isMedia(null, name)) continue
                    val dir = full.substringBeforeLast('/', "")
                    val fallback = e.time.takeIf { it > 0 }
                    r.addOne(name) {
                        v.add(name, guessMime(name), sidecars.lookup(dir, name), fallback, "takeout") { NonClosingInputStream(zis) }
                    }
                }
            }
        }
        v.flush()
        return summary("Google Takeout", Jobs.state) +
            if (Jobs.state.failed == 0 && Jobs.state.added + Jobs.state.dupes > 0)
                "\n\nEverything is verified and inside the wall. You can delete the Takeout zip files from Downloads now to free space."
            else ""
    }

    // ---------- "Is it safe to delete these in Google Photos?" ----------
    fun checkSafe(ctx: Context, uris: List<Uri>, r: Reporter): String {
        val v = Vault.get(ctx)
        r.total(uris.size)
        val missing = ArrayList<String>()
        var safe = 0
        for (u in uris) {
            val name = meta(ctx, u).name
            r.current(name)
            try {
                val sha = ctx.contentResolver.openInputStream(u)?.use { Crypto.sha256(it.buffered()) } ?: throw IOException("Can't open")
                if (v.contains(sha)) safe++ else missing += name
                r.step()
            } catch (e: Exception) { r.failed(name, e); missing += name }
        }
        return buildString {
            append("$safe of ${uris.size} are safely in your vault (exact, verified copies).")
            if (missing.isEmpty()) append("\n\n✅ It's safe to delete these in Google Photos.")
            else {
                append("\n\n⚠️ DON'T delete these yet. They are NOT in the vault:\n")
                missing.take(30).forEach { append("• $it\n") }
                if (missing.size > 30) append("…and ${missing.size - 30} more\n")
                append("\nTip: Google sometimes hands out slightly altered copies, so a photo you did save can still show up here. When in doubt, add it again — duplicates are skipped automatically.")
            }
        }
    }

    fun summary(what: String, s: Jobs.State): String = buildString {
        append("${s.added} new photos/videos saved and verified.")
        if (s.dupes > 0) append("\n${s.dupes} were already in the vault (skipped).")
        if (s.skipped > 0) append("\n${s.skipped} files weren't photos or videos (skipped).")
        if (s.failed > 0) {
            append("\n\n⚠️ ${s.failed} could not be saved:\n")
            s.errors.take(10).forEach { append("• $it\n") }
        }
    }
}
