package com.ruri

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Keeps provider API keys encrypted with an AES-GCM key held by Android Keystore.
 * The encrypted payload lives in the app's private files directory; the key
 * itself is non-exportable and is never included in a RU-R1 archive.
 */
object SecretStore {
    private const val TAG = "RuriSecrets"
    private const val KEY_ALIAS = "ru-r1.api-secrets.v1"
    private const val SECRET_FILE = "core/secrets.enc.json"
    private const val CONFIG_FILE = "core/config.json"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private val lock = Any()

    fun readRuntimeConfig(root: File): String = synchronized(lock) {
        val config = JSONObject(FileManager.read(root, CONFIG_FILE).ifBlank { "{}" })
        val secrets = readSecrets(root)
        val models = config.optJSONArray("models")
        if (models != null) {
            for (i in 0 until models.length()) {
                val model = models.optJSONObject(i) ?: continue
                val id = modelId(model, i)
                model.put("api_key", secrets.optString(id, ""))
            }
        }
        config.toString()
    }

    fun saveRuntimeConfig(root: File, rawConfig: String) = synchronized(lock) {
        val config = JSONObject(rawConfig)
        val models = config.optJSONArray("models")
            ?: throw IllegalArgumentException("В конфигурации отсутствует список моделей")
        val secrets = readSecrets(root)

        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            val id = modelId(model, i)
            model.put("id", id)

            if (model.optBoolean("clear_api_key", false)) {
                secrets.remove(id)
            } else {
                val suppliedKey = model.optString("api_key")
                if (suppliedKey.isNotBlank()) secrets.put(id, suppliedKey)
            }

            // Never persist provider secrets in core/config.json.
            model.put("api_key", "")
            model.remove("clear_api_key")
        }

        writeSecrets(root, secrets)
        FileManager.write(root, CONFIG_FILE, config.toString(2))
        Unit
    }

    fun migrateLegacyApiKeys(root: File) = synchronized(lock) {
        val raw = FileManager.read(root, CONFIG_FILE)
        if (raw.isBlank()) return@synchronized
        try {
            val config = JSONObject(raw)
            val models = config.optJSONArray("models") ?: return@synchronized
            val hasPlaintextKey = (0 until models.length()).any { index ->
                models.optJSONObject(index)?.optString("api_key")?.isNotBlank() == true
            }
            if (hasPlaintextKey) {
                Log.i(TAG, "Migrating existing provider keys to Android Keystore")
                saveRuntimeConfig(root, raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not migrate legacy provider configuration; original file was preserved", e)
        }
    }

    private fun modelId(model: JSONObject, index: Int): String {
        val explicit = model.optString("id").trim()
        if (explicit.isNotEmpty()) return explicit
        val name = model.optString("name", "model_$index").lowercase()
        return name.replace(Regex("[^a-z0-9_-]"), "_").ifBlank { "model_$index" }
    }

    private fun readSecrets(root: File): JSONObject {
        val raw = FileManager.read(root, SECRET_FILE)
        if (raw.isBlank()) return JSONObject()
        return try {
            val packet = JSONObject(raw)
            require(packet.optInt("version", 0) == 1) { "Unsupported secret-store version" }
            val iv = Base64.decode(packet.getString("iv"), Base64.NO_WRAP)
            val encrypted = Base64.decode(packet.getString("ciphertext"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted API-key store could not be opened. Keys were not exposed.", e)
            JSONObject()
        }
    }

    private fun writeSecrets(root: File, secrets: JSONObject) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(secrets.toString().toByteArray(Charsets.UTF_8))
        val packet = JSONObject()
            .put("version", 1)
            .put("algorithm", TRANSFORMATION)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        FileManager.write(root, SECRET_FILE, packet.toString(2))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
