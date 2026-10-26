package app.lifeboat.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault file format "LBV1" (streaming, works for multi-GB videos with constant memory):
 *   "LBV1" | fileId(16) | chunk*      chunk = flag(1: 1 = last) | len(4) | AES-256-GCM(ciphertext+tag)
 * Each file gets its own key = HMAC-SHA256(master, "lifeboat-file" | fileId).
 * Nonce = flag(4) | chunkIndex(8). AAD = fileId | chunkIndex | flag, so chunks cannot be
 * reordered, dropped or truncated without detection.
 */
object Crypto {
    const val CHUNK = 1 shl 20
    private val MAGIC = "LBV1".toByteArray()
    private val KEYMAGIC = "LBK1".toByteArray()
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    class Result(val sha256: String, val size: Long)

    private fun fileKey(master: ByteArray, fileId: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        mac.update("lifeboat-file".toByteArray())
        mac.update(fileId)
        return SecretKeySpec(mac.doFinal(), "AES")
    }

    private fun nonce(i: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).putInt(if (last) 1 else 0).putLong(i).array()

    private fun aad(fileId: ByteArray, i: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(fileId.size + 9).put(fileId).putLong(i).put((if (last) 1 else 0).toByte()).array()

    fun encrypt(master: ByteArray, input: InputStream, output: OutputStream): Result {
        val out = DataOutputStream(output)
        val fileId = random(16)
        val key = fileKey(master, fileId)
        val md = MessageDigest.getInstance("SHA-256")
        out.write(MAGIC)
        out.write(fileId)
        var cur = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var curLen = readFully(input, cur)
        var i = 0L
        var total = 0L
        while (true) {
            val nextLen = if (curLen == CHUNK) readFully(input, next) else 0
            val last = nextLen == 0
            md.update(cur, 0, curLen)
            total += curLen
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(i, last)))
            c.updateAAD(aad(fileId, i, last))
            val ct = c.doFinal(cur, 0, curLen)
            out.writeByte(if (last) 1 else 0)
            out.writeInt(ct.size)
            out.write(ct)
            if (last) break
            val t = cur; cur = next; next = t
            curLen = nextLen
            i++
        }
        out.flush()
        return Result(md.digest().hex(), total)
    }

    fun decrypt(master: ByteArray, input: InputStream, output: OutputStream): Result {
        val ins = DataInputStream(input)
        val magic = ByteArray(4)
        ins.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Lifeboat vault file")
        val fileId = ByteArray(16)
        ins.readFully(fileId)
        val key = fileKey(master, fileId)
        val md = MessageDigest.getInstance("SHA-256")
        var i = 0L
        var total = 0L
        while (true) {
            val flag = ins.read()
            if (flag < 0) throw IOException("Vault file is cut short")
            val last = flag == 1
            val len = ins.readInt()
            if (len < 16 || len > CHUNK + 16) throw IOException("Vault file is damaged")
            val ct = ByteArray(len)
            ins.readFully(ct)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(i, last)))
            c.updateAAD(aad(fileId, i, last))
            val pt = c.doFinal(ct)
            md.update(pt)
            output.write(pt)
            total += pt.size
            if (last) break
            i++
        }
        output.flush()
        return Result(md.digest().hex(), total)
    }

    fun encryptBytes(master: ByteArray, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size + 64)
        encrypt(master, ByteArrayInputStream(data), bos)
        return bos.toByteArray()
    }

    fun decryptBytes(master: ByteArray, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        decrypt(master, ByteArrayInputStream(data), bos)
        return bos.toByteArray()
    }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            md.update(buf, 0, r)
        }
        return md.digest().hex()
    }

    // ---- Password protection for backups ----
    private fun pbkdf2(password: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(password, salt, iter, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun wrapWithPassword(master: ByteArray, password: CharArray): ByteArray {
        val salt = random(16)
        val iter = 210_000
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, pbkdf2(password, salt, iter), GCMParameterSpec(128, iv))
        c.updateAAD(KEYMAGIC)
        val ct = c.doFinal(master)
        return ByteBuffer.allocate(4 + 16 + 4 + 12 + ct.size)
            .put(KEYMAGIC).put(salt).putInt(iter).put(iv).put(ct).array()
    }

    /** Throws javax.crypto.AEADBadTagException when the password is wrong. */
    fun unwrapWithPassword(blob: ByteArray, password: CharArray): ByteArray {
        val bb = ByteBuffer.wrap(blob)
        val magic = ByteArray(4).also { bb.get(it) }
        if (!magic.contentEquals(KEYMAGIC)) throw IOException("Not a Lifeboat backup key")
        val salt = ByteArray(16).also { bb.get(it) }
        val iter = bb.int
        val iv = ByteArray(12).also { bb.get(it) }
        val ct = ByteArray(bb.remaining()).also { bb.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, pbkdf2(password, salt, iter), GCMParameterSpec(128, iv))
        c.updateAAD(KEYMAGIC)
        return c.doFinal(ct)
    }
}
