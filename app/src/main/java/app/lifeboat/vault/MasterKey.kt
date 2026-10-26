package app.lifeboat.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault's master key is random, and is stored on the phone's internal storage wrapped
 * (encrypted) by a key that lives inside the phone's hardware keystore and can never be
 * exported. The master key file never goes to the SD card.
 */
object MasterKey {
    private const val ALIAS = "lifeboat_master_wrap"

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "master.key")

    fun save(ctx: Context, master: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = c.doFinal(master)
        file(ctx).writeAtomic(c.iv + ct)
    }

    private fun load(ctx: Context): ByteArray? {
        val f = file(ctx)
        if (!f.exists()) return null
        val data = f.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return c.doFinal(data, 12, data.size - 12)
    }

    @Synchronized
    fun getOrCreate(ctx: Context): ByteArray {
        load(ctx)?.let { return it }
        val m = Crypto.random(32)
        save(ctx, m)
        return m
    }
}
