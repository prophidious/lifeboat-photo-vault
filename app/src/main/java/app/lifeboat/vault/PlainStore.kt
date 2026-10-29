package app.lifeboat.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.io.OutputStream

/**
 * The Lifeboat folder: a normal folder the user picked on the phone or SD card (e.g. "Lifeboat").
 * Photos are plain files sorted into YYYY/MM-Month folders, so they can be seen in the Files app
 * and copied to a laptop. A ".nomedia" file in every folder keeps Google Photos, gallery apps and
 * the media scanner out, so Google never backs them up.
 */
class PlainStore private constructor(private val ctx: Context, val tree: Uri) {
    private val cr = ctx.contentResolver
    private val rootDoc: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val dirs = HashMap<String, Uri>()

    companion object {
        private const val PREFS = "lifeboat"
        @Volatile private var inst: PlainStore? = null
        const val OCTET = "application/octet-stream" // keeps file names exactly as they are

        fun current(ctx: Context): PlainStore? {
            inst?.let { return it }
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("folder", null) ?: return null
            val u = Uri.parse(s)
            val held = ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
            if (!held) return null
            return synchronized(this) { inst ?: PlainStore(ctx.applicationContext, u).also { inst = it } }
        }

        fun set(ctx: Context, tree: Uri): PlainStore {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("folder", tree.toString()).commit()
            return synchronized(this) { PlainStore(ctx.applicationContext, tree).also { inst = it; it.nomedia(it.rootDoc) } }
        }

        fun label(tree: Uri): String {
            val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return "your folder" }
            val vol = id.substringBefore(':')
            val path = id.substringAfter(':', "").ifEmpty { "(top)" }
            return (if (vol == "primary") "Phone" else "SD card") + " › " + path.replace("/", " › ")
        }

        fun deleteDoc(ctx: Context, doc: Uri) { try { DocumentsContract.deleteDocument(ctx.contentResolver, doc) } catch (_: Exception) {} }
    }

    fun label() = label(tree)

    private fun child(parent: Uri, name: String): Uri? {
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        cr.query(q, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    private fun nomedia(dir: Uri) {
        try { if (child(dir, ".nomedia") == null) DocumentsContract.createDocument(cr, dir, OCTET, ".nomedia") } catch (_: Exception) {}
    }

    @Synchronized
    private fun dir(vararg path: String): Uri {
        var cur = rootDoc
        var key = ""
        for (p in path) {
            key += "/$p"
            cur = dirs[key] ?: run {
                val d = child(cur, p) ?: DocumentsContract.createDocument(cr, cur, Document.MIME_TYPE_DIR, p)?.also { nomedia(it) }
                    ?: throw IOException("Can't make folder $p (is the SD card still in?)")
                d.also { dirs[key] = it }
            }
        }
        return cur
    }

    /** New empty file at YYYY/MM-Month/name (Android adds " (1)" if the name is taken). */
    fun create(taken: Long, name: String): Uri {
        val d = dir(yearOf(taken), monthFolder(taken))
        return DocumentsContract.createDocument(cr, d, OCTET, name) ?: throw IOException("Can't create $name")
    }

    fun write(doc: Uri, block: (OutputStream) -> Unit) {
        val os = cr.openOutputStream(doc, "w") ?: throw IOException("Can't write file")
        os.buffered(1 shl 16).use { block(it) }
    }

    fun sha256(doc: Uri): String = (cr.openInputStream(doc) ?: throw IOException("Can't read back")).buffered(1 shl 16).use { Crypto.sha256(it) }

    fun delete(doc: Uri) = deleteDoc(ctx, doc)
}
