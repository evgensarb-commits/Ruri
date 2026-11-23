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
import org.json.JSONArray
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

    /** Safe WebView view: model keys are replaced by presence flags and never cross the bridge. */
    fun readPublicRuntimeConfig(root: File): String = synchronized(lock) {
        redactRuntimeConfigForWeb(readRuntimeConfig(root))
    }

    internal fun configForBackup(rawConfig: String): String {
        val config = JSONObject(rawConfig.ifBlank { "{}" })
        config.remove("api_key")
        val models = config.optJSONArray("models") ?: JSONArray()
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            model.put("api_key", "")
            model.remove("has_key")
            model.remove("clear_api_key")
        }
        return config.toString(2)
    }

    internal fun searchConfigForBackup(rawConfig: String): String {
        val config = JSONObject(rawConfig.ifBlank { "{}" })
        config.put("serper_key", "")
        config.remove("has_serper_key")
        config.remove("clear_serper_key")
        return config.toString(2)
    }

    internal fun redactRuntimeConfigForWeb(rawConfig: String): String {
        val config = JSONObject(rawConfig)
        val models = config.optJSONArray("models") ?: JSONArray()
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            model.put("has_key", model.optString("api_key").isNotBlank())
            model.put("api_key", "")
        }
        return config.toString()
    }

    fun readRuntimeConfig(root: File): String = synchronized(lock) {
        val config = JSONObject(FileManager.read(root, CONFIG_FILE).ifBlank { "{}" })
        if (stripLegacyNsfwFields(config)) {
            FileManager.write(root, CONFIG_FILE, config.toString(2))
        }
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

    private const val SEARCH_CONFIG_FILE = "core/search.json"
    private const val SEARCH_SECRET_ID = "__search_serper_key__"

    fun readSearchConfig(root: File): JSONObject = synchronized(lock) {
        val config = JSONObject(FileManager.read(root, SEARCH_CONFIG_FILE).ifBlank { "{}" })
        val key = runCatching { readSecrets(root).optString(SEARCH_SECRET_ID) }.getOrDefault("")
        config.put("serper_key", key)
        config.remove("has_serper_key")
        config
    }

    fun readPublicSearchConfig(root: File): String = synchronized(lock) {
        val hasKey = runCatching { readSecrets(root).optString(SEARCH_SECRET_ID).isNotBlank() }.getOrDefault(false)
        searchConfigForWeb(FileManager.read(root, SEARCH_CONFIG_FILE), hasKey)
    }

    internal fun searchConfigForWeb(rawConfig: String, hasKey: Boolean): String {
        val config = JSONObject(rawConfig.ifBlank { "{}" })
        config.put("serper_key", "")
            .put("has_serper_key", hasKey)
        return config.toString()
    }

    fun saveSearchConfig(root: File, rawConfig: String) = synchronized(lock) {
        val config = JSONObject(rawConfig)
        val secrets = readSecrets(root)
        val suppliedKey = config.optString("serper_key")
        if (config.optBoolean("clear_serper_key", false)) {
            secrets.remove(SEARCH_SECRET_ID)
        } else if (suppliedKey.isNotBlank()) {
            secrets.put(SEARCH_SECRET_ID, suppliedKey)
        }
        config.put("serper_key", "")
        config.remove("has_serper_key")
        config.remove("clear_serper_key")
        writeSecrets(root, secrets)
        FileManager.write(root, SEARCH_CONFIG_FILE, config.toString(2))
    }

    fun migrateLegacySearchKey(root: File) = synchronized(lock) {
        val raw = FileManager.read(root, SEARCH_CONFIG_FILE)
        if (raw.isBlank()) return@synchronized
        try {
            val config = JSONObject(raw)
            val legacyKey = config.optString("serper_key")
            if (legacyKey.isBlank()) return@synchronized
            val secrets = readSecrets(root)
            if (secrets.optString(SEARCH_SECRET_ID).isBlank()) {
                secrets.put(SEARCH_SECRET_ID, legacyKey)
                writeSecrets(root, secrets)
            }
            config.put("serper_key", "")
            config.remove("has_serper_key")
            FileManager.write(root, SEARCH_CONFIG_FILE, config.toString(2))
            Log.i(TAG, "Migrated Serper key into encrypted secret storage")
        } catch (e: Exception) {
            Log.e(TAG, "Could not migrate Serper key; original search config was preserved", e)
        }
    }

    fun saveRuntimeConfig(root: File, rawConfig: String) = synchronized(lock) {
        val config = JSONObject(rawConfig)
        stripLegacyNsfwFields(config)
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

            // Never persist provider secrets or UI-only key metadata in core/config.json.
            model.put("api_key", "")
            model.remove("has_key")
            model.remove("clear_api_key")
        }

        writeSecrets(root, secrets)
        FileManager.write(root, CONFIG_FILE, config.toString(2))
        Unit
    }

    /** Removes legacy persistent NSFW capability/probe fields from provider configuration. */
    internal fun stripLegacyNsfwFields(config: JSONObject): Boolean {
        var changed = false
        val models = config.optJSONArray("models") ?: return false
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            val legacyKeys = model.keys().asSequence().filter { it.startsWith("nsfw_") }.toList()
            legacyKeys.forEach { model.remove(it); changed = true }
        }
        return changed
    }

    internal fun legacyKeys(config: JSONObject): JSONObject {
        val imported = JSONObject()
        val models = config.optJSONArray("models") ?: JSONArray()
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            val key = model.optString("api_key")
            if (key.isNotBlank()) imported.put(modelId(model, i), key)
        }
        val topLevelKey = config.optString("api_key")
        if (topLevelKey.isNotBlank()) {
            val current = config.optInt("current", -1)
            val currentModel = models.optJSONObject(current)
            val targetId = currentModel?.let { modelId(it, current) } ?: "routeway"
            if (!imported.has(targetId)) imported.put(targetId, topLevelKey)
        }
        return imported
    }

    internal fun mergeSecretMaps(existing: JSONObject, imported: JSONObject): JSONObject {
        val merged = JSONObject(existing.toString())
        val keys = imported.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            if (merged.optString(id).isBlank() && imported.optString(id).isNotBlank()) {
                merged.put(id, imported.getString(id))
            }
        }
        return merged
    }

    fun migrateLegacyApiKeys(root: File) = synchronized(lock) {
        val raw = FileManager.read(root, CONFIG_FILE)
        if (raw.isBlank()) return@synchronized
        try {
            val config = JSONObject(raw)
            val models = config.optJSONArray("models") ?: JSONArray()
            val imported = legacyKeys(config)
            val secretFile = File(root, SECRET_FILE)
            val existing = readSecrets(root)
            val merged = mergeSecretMaps(existing, imported)
            if (merged.toString() != existing.toString() || (!secretFile.isFile && merged.length() > 0)) {
                Log.i(TAG, "Migrating legacy provider keys to Android Keystore")
                writeSecrets(root, merged)
            }
            var changed = config.has("api_key")
            config.remove("api_key")
            for (i in 0 until models.length()) {
                val model = models.optJSONObject(i) ?: continue
                if (model.has("api_key")) { model.remove("api_key"); changed = true }
            }
            if (changed) FileManager.write(root, CONFIG_FILE, config.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Could not migrate legacy provider configuration; original file was preserved", e)
        }
    }

    /**
     * Reuses the existing Routeway credential for the MiniMax Routeway endpoint.
     * The key remains encrypted in Android Keystore and is never copied into config.json.
     */
    fun migrateRoutewayFallbackKey(root: File, primaryRouteId: String, fallbackRouteId: String) = synchronized(lock) {
        if (primaryRouteId.isBlank() || fallbackRouteId.isBlank() || primaryRouteId == fallbackRouteId) {
            return@synchronized
        }
        try {
            val secrets = readSecrets(root)
            val primaryKey = secrets.optString(primaryRouteId)
            if (primaryKey.isNotBlank() && secrets.optString(fallbackRouteId).isBlank()) {
                secrets.put(fallbackRouteId, primaryKey)
                writeSecrets(root, secrets)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Routeway fallback key migration skipped; encrypted secrets were preserved", e)
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
            Log.e(TAG, "Encrypted API-key store could not be opened. Existing secret data was left untouched.", e)
            throw IllegalStateException(
                "Не удалось расшифровать хранилище API-ключей. Исходные данные сохранены без изменений.",
                e
            )
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
