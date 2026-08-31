package com.xiaowu7z.cfipoptimizer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed opt-in token storage.
 *
 * The UI may keep a [CloudflareApiToken] in memory for a session. It should call
 * [save] only when the user explicitly enables “在本机安全保存”. SharedPreferences
 * contains AES-GCM ciphertext only; encryption keys never leave Android Keystore.
 */
object CloudflareTokenStore {
    const val SECURITY_NOTICE =
        "API Token 不会写入测速历史或导出；仅在你选择保存时使用 Android Keystore 加密存放。"

    private const val KEY_ALIAS = "rr_edge_hunter_cf_dns_token_v1"
    private const val PREFS = "cf_dns_private"
    private const val TOKEN = "token_ciphertext_v1"
    private const val FORMAT_VERSION: Byte = 1
    private val aad = "com.xiaowu7z.cfipoptimizer/cf-dns-token/v1".toByteArray(Charsets.UTF_8)

    fun save(context: Context, rawToken: String) =
        save(context, CloudflareApiToken.parse(rawToken))

    fun save(context: Context, token: CloudflareApiToken) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val encrypted = token.use { cipher.doFinal(it.toByteArray(Charsets.UTF_8)) }
        val iv = cipher.iv
        require(iv.size in 12..16) { "无法安全保存 API Token" }
        val packed = ByteArray(2 + iv.size + encrypted.size)
        packed[0] = FORMAT_VERSION
        packed[1] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 2, iv.size)
        System.arraycopy(encrypted, 0, packed, 2 + iv.size, encrypted.size)
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(TOKEN, encoded).commit()
        if (!saved) throw IllegalStateException("无法安全保存 API Token")
    }

    fun load(context: Context): CloudflareApiToken? {
        return try {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(TOKEN, null) ?: return null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size < 3 || packed[0] != FORMAT_VERSION) return clearAndNull(context)
            val ivSize = packed[1].toInt() and 0xff
            if (ivSize !in 12..16 || packed.size <= 2 + ivSize) return clearAndNull(context)
            val iv = packed.copyOfRange(2, 2 + ivSize)
            val encrypted = packed.copyOfRange(2 + ivSize, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(encrypted)
            try {
                CloudflareApiToken.parse(String(plaintext, Charsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            clearAndNull(context)
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN).commit()
    }

    private fun clearAndNull(context: Context): CloudflareApiToken? {
        clear(context)
        return null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
