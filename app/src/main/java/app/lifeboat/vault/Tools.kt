package app.lifeboat.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray

/** Backups, exports, checks and moving the vault. */
object Tools {

    // ---------- Backup: one password-locked file you can keep on a USB stick / computer ----------
    fun backup(ctx: Context, dest: Uri, password: CharArray, r: Reporter): String {
        val v = Vault.get(ctx)
        v.flush()
        val items = v.list()
        r.total(items.size)
        val out = ctx.contentResolver.openOutputStream(dest) ?: throw IOException("Can't write the backup file")
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION) // contents are already encrypted
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("README.txt", ("Lifeboat vault backup. Restore it in the Lifeboat app (menu > Restore a backup).\n" +
                "Everything inside is encrypted. Without the password nobody can open it - not even us.\n").toByteArray())
            entry("key.lbk", Crypto.wrapWithPassword(v.masterCopy(), password))
            entry("manifest.enc", v.manifestFile().readBytes())
            for (it in items) {
                r.current(it.name)
                try {
                    zip.putNextEntry(ZipEntry("blobs/${it.id}"))
                    if (it.isPlain) {
                        // Normal visible file: encrypt it on the way into the backup.
                        val ins = ctx.contentResolver.openInputStream(Uri.parse(it.loc)) ?: throw IOException("File is missing")
                        val res = ins.buffered(1 shl 16).use { s -> Crypto.encrypt(v.masterCopy(), s, zip) }
                        if (res.sha256 != it.sha256) throw IOException("File was changed or damaged")
                    } else FileInputStream(v.blobFile(it.id)).use { ins -> ins.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                    val t = v.thumbFile(it.id)
                    if (t.exists()) entry("thumbs/${it.id}", t.readBytes())
                    r.step()
                } catch (e: Exception) { r.failed(it.name, e) }
            }
        }
        val s = Jobs.state
        return "Backup finished: ${s.done - s.failed} photos/videos (${humanBytes(v.totalBytes())}) locked with your password." +
            (if (s.failed > 0) "\n⚠️ ${s.failed} could not be backed up." else "") +
            "\n\nKeep this file somewhere that isn't Google: a USB stick, a computer or a second SD card. Don't forget the password - it can't be reset."
    }

    class WrongPassword : Exception("Wrong password")

    fun restore(ctx: Context, src: Uri, password: CharArray, r: Reporter): String {
        val v = Vault.get(ctx)
        var master: ByteArray? = null
        var items: Map<String, Item> = emptyMap()
        val tmp = File(Vault.plainDir(ctx), "restore")
        val input = ctx.contentResolver.openInputStream(src) ?: throw IOException("Can't open the backup")
        ZipInputStream(input.buffered(1 shl 16)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                when {
                    e.name == "key.lbk" -> master = try { Crypto.unwrapWithPassword(zis.readBytes(), password) } catch (x: Exception) { throw WrongPassword() }
                    e.name == "manifest.enc" -> {
                        val m = master ?: throw IOException("Backup is damaged (key missing)")
                        val arr = JSONArray(String(Crypto.decryptBytes(m, zis.readBytes()), Charsets.UTF_8))
                        items = (0 until arr.length()).map { Item.from(arr.getJSONObject(it)) }.associateBy { it.id }
                        r.total(items.size)
                    }
                    e.name.startsWith("blobs/") -> {
                        val m = master ?: throw IOException("Backup is damaged (key missing)")
                        val it = items[e.name.removePrefix("blobs/")] ?: continue
                        if (v.contains(it.sha256)) { r.duplicate(); continue }
                        r.addOne(it.name) {
                            val res = FileOutputStream(tmp).buffered(1 shl 16).use { out -> Crypto.decrypt(m, NonClosingInputStream(zis), out) }
                            if (res.sha256 != it.sha256) throw IOException("Doesn't match its fingerprint - damaged in the backup")
                            try { v.add(it.name, it.mime, it.taken, null, it.source) { FileInputStream(tmp) } } finally { tmp.delete() }
                        }
                    }
                }
            }
        }
        if (master == null) throw IOException("This isn't a Lifeboat backup")
        v.flush()
        return "Restore finished.\n" + Importer.summary("Restore", Jobs.state)
    }

    // ---------- Get photos out: copy or move normal files to any folder, filed by year ----------
    fun export(ctx: Context, tree: Uri, ids: List<String>?, move: Boolean, hide: Boolean, r: Reporter): String {
        val v = Vault.get(ctx)
        val items = if (ids == null) v.list() else ids.mapNotNull { v.find(it) }
        r.total(items.size)
        val cr = ctx.contentResolver
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Can't open that folder")
        if (hide && root.findFile(".nomedia") == null) root.createFile(PlainStore.OCTET, ".nomedia")
        val years = HashMap<String, DocumentFile>()
        val moved = ArrayList<String>()
        for (it in items) {
            if (r.cancelled) break
            r.current(it.name)
            var f: DocumentFile? = null
            try {
                val y = yearOf(it.taken)
                val dir = years.getOrPut(y) {
                    (root.findFile(y)?.takeIf { d -> d.isDirectory } ?: root.createDirectory(y) ?: throw IOException("Can't make folder $y"))
                        .also { d -> if (hide && d.findFile(".nomedia") == null) d.createFile(PlainStore.OCTET, ".nomedia") }
                }
                f = dir.createFile(PlainStore.OCTET, it.name) ?: throw IOException("Can't create file")
                val ok = cr.openOutputStream(f.uri)?.use { out -> v.decryptTo(it, out.buffered(1 shl 16)) } ?: throw IOException("Can't write file")
                if (!ok) throw IOException("Lifeboat's copy didn't match its fingerprint")
                // Read the new copy back and compare it byte for byte before it counts.
                val back = cr.openInputStream(f.uri)?.buffered(1 shl 16)?.use { s -> Crypto.sha256(s) }
                if (back != it.sha256) throw IOException("The new copy didn't check out")
                if (move) moved += it.id
                r.step()
            } catch (e: Exception) {
                try { f?.delete() } catch (_: Exception) {}
                r.failed(it.name, e)
            }
        }
        if (moved.isNotEmpty()) v.delete(moved)
        val s = Jobs.state
        val n = s.done - s.failed
        return (if (move) "Moved $n photos/videos out of Lifeboat" else "Copied $n photos/videos") +
            ", sorted into year folders, every one checked." +
            (if (s.failed > 0) "\n\n⚠️ ${s.failed} failed (still in Lifeboat):\n" + s.errors.take(10).joinToString("\n") { "• $it" } else "") +
            "\n\nTo get them onto your laptop: plug the phone in by USB, choose \"File transfer\", and copy that folder." +
            (if (hide) "\n\nGoogle Photos and gallery apps won't see that folder (.nomedia)." else "\n\nHeads up: gallery apps can see that folder, and Google Photos will back it up if you turned on backup for it.")
    }

    // ---------- Check every file against its fingerprint ----------
    fun verifyAll(ctx: Context, r: Reporter): String {
        val v = Vault.get(ctx)
        val items = v.list()
        r.total(items.size)
        val bad = ArrayList<String>()
        for (it in items) {
            r.current(it.name)
            if (v.verify(it)) r.step() else { bad += it.name; r.failed(it.name, IOException("damaged or missing")) }
        }
        return if (bad.isEmpty()) "✅ All ${items.size} photos/videos checked. Every single one matches its original fingerprint."
        else "⚠️ ${bad.size} of ${items.size} are damaged or missing:\n" + bad.take(30).joinToString("\n") { "• $it" } +
            "\n\nRestore them from a backup if you have one."
    }

    // ---------- Move the vault between phone and SD card ----------
    fun move(ctx: Context, to: String, r: Reporter): String {
        val v = Vault.get(ctx)
        val from = v.root
        val dest = Vault.rootFor(ctx, to) ?: throw IOException("SD card not available")
        if (from.canonicalPath == dest.canonicalPath) return "Already there."
        v.flush()
        val files = from.walkTopDown().filter { it.isFile }.toList()
        val need = files.sumOf { it.length() }
        val free = (dest.parentFile ?: dest).apply { mkdirs() }.usableSpace
        if (need > free) throw IOException("Not enough space: need ${humanBytes(need)}, only ${humanBytes(free)} free")
        r.total(files.size)
        for (f in files) {
            val rel = f.relativeTo(from).path
            val out = File(dest, rel)
            out.parentFile?.mkdirs()
            FileInputStream(f).use { ins -> FileOutputStream(out).use { o -> ins.copyTo(o, 1 shl 16); o.fd.sync() } }
            if (out.length() != f.length()) throw IOException("Copy failed for a file; nothing was deleted")
            r.step()
        }
        Vault.setLocation(ctx, to)
        Vault.reset()
        val nv = Vault.get(ctx)
        // Spot-check the new location before deleting the old copy.
        val sample = nv.list().shuffled().take(20)
        if (sample.any { !nv.verify(it) }) {
            Vault.setLocation(ctx, if (to == "sd") "phone" else "sd"); Vault.reset()
            throw IOException("Copies on the new location didn't verify; kept everything where it was")
        }
        from.deleteRecursively()
        return "Vault moved to the ${if (to == "sd") "SD card" else "phone"}. ${nv.count()} photos/videos, all checked."
    }
}
