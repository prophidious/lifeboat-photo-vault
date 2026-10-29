package app.lifeboat.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class Item(
    val id: String, val name: String, val mime: String, val size: Long,
    val sha256: String, val taken: Long, val added: Long, val source: String,
    /** "" = encrypted blob inside the vault; otherwise the document URI of a normal visible file in the Lifeboat folder. */
    val loc: String = ""
) {
    val isVideo get() = mime.startsWith("video/")
    val isPlain get() = loc.isNotEmpty()
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("mime", mime)
        .put("size", size).put("sha256", sha256).put("taken", taken).put("added", added).put("source", source)
        .put("loc", loc)

    companion object {
        fun from(o: JSONObject) = Item(
            o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"),
            o.getString("sha256"), o.getLong("taken"), o.getLong("added"), o.optString("source"), o.optString("loc")
        )
    }
}

class VaultUnavailableException(msg: String) : IOException(msg)

/**
 * The vault. Everything inside is encrypted, has no file extension, and lives in the app's
 * private storage (phone) or the app's private folder on the SD card (Android/data/...),
 * which Android's media scanner skips - Google Photos and gallery apps cannot see it.
 */
class Vault private constructor(private val app: Context, private val master: ByteArray, val root: File) {
    private val blobDir = File(root, "blobs")
    private val thumbDir = File(root, "thumbs")
    private val journalDir = File(root, "journal")
    private val manifest = File(root, "manifest.enc")
    private val items = LinkedHashMap<String, Item>()
    private val hashes = HashSet<String>()
    private var pendingJournal = 0
    @Volatile var version = 0
        private set

    init {
        blobDir.mkdirs(); thumbDir.mkdirs(); journalDir.mkdirs()
        load()
    }

    companion object {
        private const val PREFS = "lifeboat"
        @Volatile private var inst: Vault? = null

        fun location(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("location", "phone") ?: "phone"

        fun setLocation(ctx: Context, loc: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("location", loc).commit()
        }

        fun sdRoot(ctx: Context): File? {
            val dirs = ctx.getExternalFilesDirs(null)
            if (dirs.size < 2) return null
            val d = dirs[1] ?: return null
            return if (Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED) File(d, "vault") else null
        }

        fun rootFor(ctx: Context, loc: String): File? =
            if (loc == "sd") sdRoot(ctx) else File(ctx.filesDir, "vault")

        fun get(ctx: Context): Vault = inst ?: synchronized(this) {
            inst ?: run {
                val a = ctx.applicationContext
                val root = rootFor(a, location(a))
                    ?: throw VaultUnavailableException("Your vault is on the SD card, but the SD card isn't available. Put it back in and reopen Lifeboat.")
                Vault(a, MasterKey.getOrCreate(a), root).also { inst = it }
            }
        }

        fun reset() = synchronized(this) { inst = null }

        /** "plain" = normal visible files in the Lifeboat folder (default), "enc" = encrypted hidden vault. */
        fun mode(ctx: Context): String {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("mode", null)?.let { return it }
            // Upgrades from v0.2 keep their encrypted vault until the user converts it.
            val old = listOf(File(ctx.filesDir, "vault"), sdRoot(ctx)).any { r -> r != null && File(r, "blobs").list()?.isNotEmpty() == true }
            val m = if (old) "enc" else "plain"
            p.edit().putString("mode", m).commit()
            return m
        }
        fun setMode(ctx: Context, m: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", m).commit() }

        fun plainDir(ctx: Context) = File(ctx.cacheDir, "plain").apply { mkdirs() }
        fun wipePlain(ctx: Context) { File(ctx.cacheDir, "plain").deleteRecursively() }
    }

    fun masterCopy(): ByteArray = master.copyOf()
    fun count() = synchronized(items) { items.size }
    fun totalBytes() = synchronized(items) { items.values.sumOf { it.size } }
    fun list(): List<Item> = synchronized(items) { items.values.sortedByDescending { it.taken } }
    fun find(id: String): Item? = synchronized(items) { items[id] }
    fun contains(sha: String) = synchronized(items) { sha in hashes }
    fun blobFile(id: String) = File(blobDir, id)
    fun thumbFile(id: String) = File(thumbDir, id)
    fun manifestFile() = manifest

    // ---------- index ----------
    private fun load() {
        if (manifest.exists()) {
            val arr = JSONArray(String(Crypto.decryptBytes(master, manifest.readBytes()), Charsets.UTF_8))
            for (i in 0 until arr.length()) put(Item.from(arr.getJSONObject(i)))
        }
        // Items added since the last full index save (survives crashes / phone dying mid-import).
        journalDir.listFiles()?.forEach { f ->
            try { put(Item.from(JSONObject(String(Crypto.decryptBytes(master, f.readBytes()), Charsets.UTF_8)))) } catch (_: Exception) {}
        }
        blobDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun put(item: Item) { items[item.id] = item; hashes += item.sha256 }

    private fun saveManifestLocked() {
        val arr = JSONArray()
        items.values.forEach { arr.put(it.toJson()) }
        manifest.writeAtomic(Crypto.encryptBytes(master, arr.toString().toByteArray(Charsets.UTF_8)))
        journalDir.listFiles()?.forEach { it.delete() }
        pendingJournal = 0
    }

    fun flush() = synchronized(items) { saveManifestLocked() }

    // ---------- add ----------
    enum class Added { NEW, DUPLICATE }

    /**
     * Copies a photo/video into the vault: encrypt -> decrypt again and compare SHA-256 with the
     * original bytes -> only then it counts as saved. Duplicates (same bytes) are skipped.
     */
    fun add(name: String, mimeHint: String?, takenHint: Long?, fallbackTaken: Long?, source: String, open: () -> InputStream): Added {
        if (mode(app) == "plain") return addPlain(name, mimeHint, takenHint, fallbackTaken, source, open)
        val id = Crypto.random(16).hex()
        val tmp = File(blobDir, "$id.tmp")
        val plain = File(plainDir(app), id)
        try {
            val enc = open().use { ins ->
                FileOutputStream(tmp).use { fos ->
                    val bos = BufferedOutputStream(fos, 1 shl 16)
                    val r = Crypto.encrypt(master, ins.buffered(1 shl 16), bos)
                    bos.flush(); fos.fd.sync(); r
                }
            }
            if (contains(enc.sha256)) { tmp.delete(); return Added.DUPLICATE }

            val dec = FileInputStream(tmp).buffered(1 shl 16).use { ins ->
                FileOutputStream(plain).buffered(1 shl 16).use { Crypto.decrypt(master, ins, it) }
            }
            if (dec.sha256 != enc.sha256 || dec.size != enc.size) throw IOException("Verification failed for $name")

            val mime = mimeHint?.takeIf { isMediaMime(it) } ?: Media.sniffMime(plain, name)
            val taken = takenHint ?: Media.dateTaken(plain, mime) ?: fallbackTaken ?: System.currentTimeMillis()
            Media.thumbnail(plain, mime)?.let { saveThumb(id, it) }

            if (!tmp.renameTo(File(blobDir, id))) throw IOException("Could not store $name")
            val item = Item(id, name, mime, enc.size, enc.sha256, taken, System.currentTimeMillis(), source)
            synchronized(items) {
                put(item)
                File(journalDir, id).writeAtomic(Crypto.encryptBytes(master, item.toJson().toString().toByteArray(Charsets.UTF_8)))
                if (++pendingJournal >= 100) saveManifestLocked()
                version++
            }
            return Added.NEW
        } catch (e: Exception) {
            tmp.delete(); File(thumbDir, id).delete()
            throw e
        } finally {
            plain.delete()
        }
    }

    private fun store(): PlainStore = PlainStore.current(app)
        ?: throw IOException("Pick your Lifeboat folder first (More > Lifeboat folder)")

    /** Plain mode: the photo becomes a normal file in the Lifeboat folder, read back and fingerprint-checked. */
    private fun addPlain(name: String, mimeHint: String?, takenHint: Long?, fallbackTaken: Long?, source: String, open: () -> InputStream): Added {
        val st = store()
        val id = Crypto.random(16).hex()
        val plain = File(plainDir(app), id)
        var doc: android.net.Uri? = null
        try {
            val res = open().use { ins -> FileOutputStream(plain).use { fos -> val r = copyHash(ins, fos); fos.fd.sync(); r } }
            if (contains(res.sha256)) return Added.DUPLICATE
            val mime = mimeHint?.takeIf { isMediaMime(it) } ?: Media.sniffMime(plain, name)
            val taken = takenHint ?: Media.dateTaken(plain, mime) ?: fallbackTaken ?: System.currentTimeMillis()
            val d = st.create(taken, name).also { doc = it }
            st.write(d) { out -> FileInputStream(plain).use { it.copyTo(out, 1 shl 16) } }
            if (st.sha256(d) != res.sha256) throw IOException("Verification failed for $name")
            Media.thumbnail(plain, mime)?.let { saveThumb(id, it) }
            val item = Item(id, name, mime, res.size, res.sha256, taken, System.currentTimeMillis(), source, d.toString())
            record(item)
            return Added.NEW
        } catch (e: Exception) {
            doc?.let { st.delete(it) }; File(thumbDir, id).delete()
            throw e
        } finally {
            plain.delete()
        }
    }

    private fun record(item: Item) = synchronized(items) {
        put(item)
        File(journalDir, item.id).writeAtomic(Crypto.encryptBytes(master, item.toJson().toString().toByteArray(Charsets.UTF_8)))
        if (++pendingJournal >= 100) saveManifestLocked()
        version++
    }

    fun encryptedCount() = synchronized(items) { items.values.count { !it.isPlain } }

    /** Turns every encrypted vault item into a normal visible file in the Lifeboat folder. */
    fun convertToPlain(r: Reporter): String {
        val st = store()
        val todo = synchronized(items) { items.values.filter { !it.isPlain } }
        r.total(todo.size)
        for (it in todo) {
            if (r.cancelled) break
            r.current(it.name)
            val tmp = File(plainDir(app), "conv-${it.id}")
            var doc: android.net.Uri? = null
            try {
                if (!decryptToFile(it, tmp)) throw IOException("damaged in the vault (didn't match its fingerprint)")
                val d = st.create(it.taken, it.name).also { x -> doc = x }
                st.write(d) { out -> FileInputStream(tmp).use { ins -> ins.copyTo(out, 1 shl 16) } }
                if (st.sha256(d) != it.sha256) throw IOException("copy didn't match the original")
                record(it.copy(loc = d.toString()))
                File(blobDir, it.id).delete()
                r.step()
            } catch (e: Exception) {
                doc?.let { x -> st.delete(x) }
                r.failed(it.name, e)
            } finally { tmp.delete() }
        }
        flush()
        val s = Jobs.state
        if (encryptedCount() == 0) setMode(app, "plain")
        return "${s.done - s.failed} photos/videos are now normal files in ${st.label()}." +
            (if (s.failed > 0) "\n\n⚠️ ${s.failed} couldn't be converted and are still safe in the encrypted vault:\n" + s.errors.take(10).joinToString("\n") { "• $it" } else "") +
            "\n\nYou can see them in the Files app and copy them to your laptop over USB. A hidden .nomedia file keeps Google Photos and gallery apps out."
    }

    private fun saveThumb(id: String, b: Bitmap) {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        File(thumbDir, id).writeAtomic(Crypto.encryptBytes(master, bos.toByteArray()))
    }

    fun thumb(id: String): Bitmap? = try {
        val f = File(thumbDir, id)
        if (!f.exists()) null else {
            val b = Crypto.decryptBytes(master, f.readBytes())
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }
    } catch (e: Exception) { null }

    /** Decrypts an item into [out]. Returns true only if the bytes match the original fingerprint. */
    fun decryptTo(item: Item, out: OutputStream): Boolean {
        if (item.isPlain) {
            val ins = app.contentResolver.openInputStream(android.net.Uri.parse(item.loc)) ?: throw IOException("File is missing")
            return ins.buffered(1 shl 16).use { copyHash(it, out) }.sha256 == item.sha256
        }
        val r = FileInputStream(File(blobDir, item.id)).buffered(1 shl 16).use { Crypto.decrypt(master, it, out) }
        return r.sha256 == item.sha256
    }

    fun decryptToFile(item: Item, dest: File): Boolean =
        FileOutputStream(dest).buffered(1 shl 16).use { decryptTo(item, it) }

    fun verify(item: Item): Boolean = try {
        (item.isPlain || File(blobDir, item.id).exists()) && decryptTo(item, NullOut)
    } catch (e: Exception) { false }

    fun delete(ids: Collection<String>) {
        synchronized(items) {
            for (id in ids) {
                items.remove(id)?.let {
                    hashes.remove(it.sha256)
                    if (it.isPlain) PlainStore.deleteDoc(app, android.net.Uri.parse(it.loc))
                }
                File(blobDir, id).delete(); File(thumbDir, id).delete(); File(journalDir, id).delete()
            }
            saveManifestLocked()
            version++
        }
    }
}
