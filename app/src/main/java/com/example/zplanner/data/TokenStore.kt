package com.example.zplanner.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class TokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val keyAlias = "zplanner_session_key"

    fun read(): String? = preferences.getString("token", null)?.let(::decrypt)
    fun readEmail(): String = preferences.getString("email", "").orEmpty()

    fun profileKey(email: String): String = MessageDigest.getInstance("SHA-256")
        .digest(email.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun readProfileName(email: String): String? = preferences.getString("profile_name_${profileKey(email)}", null)

    fun saveProfileName(email: String, name: String?) {
        val key = "profile_name_${profileKey(email)}"
        preferences.edit().apply {
            if (name == null) remove(key) else putString(key, name)
        }.apply()
    }

    fun readProfileAvatarUri(email: String): String? = preferences.getString("profile_avatar_${profileKey(email)}", null)

    fun saveProfileAvatarUri(email: String, uri: String) {
        preferences.edit().putString("profile_avatar_${profileKey(email)}", uri).apply()
    }

    fun save(token: String, email: String = "") {
        preferences.edit().putString("token", encrypt(token)).putString("email", email).apply()
    }

    fun clear() {
        // Keep per-account profiles, but remove the active authenticated session.
        preferences.edit().remove("token").remove("email").apply()
    }

    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(keyAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        }
        return store.getKey(keyAlias, null)
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }.getOrNull()
}
