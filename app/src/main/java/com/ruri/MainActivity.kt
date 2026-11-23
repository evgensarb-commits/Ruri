package com.ruri

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val chatExecutor = Executors.newSingleThreadExecutor()
    private val routeExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var importFileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var activityResumed = false
    @Volatile private var visionStatusProbeQueued = false
    private var pendingMessageId: String? = null
    @Volatile private var verifiedHFCatalog = JSONArray()
    @Volatile private var verifiedOpenRouterCatalog = JSONArray()
    private val importFileRequestCode = 2002

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileManager.init(filesDir)
        Notifier.init(this)
        pendingMessageId = intent?.getStringExtra("message_id")

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.allowFileAccessFromFileURLs = false
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) web.settings.safeBrowsingEnabled = true
        web.webViewClient = object : WebViewClient() {
            private fun isTrustedLocalAsset(url: String?): Boolean =
                url == "file:///android_asset/index.html"

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val target = request?.url?.toString()
                if (isTrustedLocalAsset(target)) return false
                if (target != null && (target.startsWith("https://") || target.startsWith("http://"))) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!isTrustedLocalAsset(url)) {
                    view?.stopLoading()
                    return
                }
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isTrustedLocalAsset(url)) return
                super.onPageFinished(view, url)
                web.evaluateJavascript("window.startOnboarding && window.startOnboarding()", null)
                openPendingMessageIfReady()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (this@MainActivity.importFileCallback != null) {
                    this@MainActivity.importFileCallback?.onReceiveValue(null)
                }
                this@MainActivity.importFileCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                        }
                    if (intent.action == Intent.ACTION_GET_CONTENT ||
                        intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(intent, importFileRequestCode)
                    true
                } catch (e: Exception) {
                    this@MainActivity.importFileCallback?.onReceiveValue(null)
                    this@MainActivity.importFileCallback = null
                    Toast.makeText(this@MainActivity, "Не удалось открыть выбор файла: ${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Ruri")

        NetState.refresh(this)
        val onboardingDone = runCatching {
            JSONObject(FileManager.read(filesDir, "memory/L4_state.json")).optBoolean("onboarding_done", false)
        }.getOrDefault(false)
        if (!onboardingDone) requestNotificationPermission()
        routeExecutor.execute {
            runCatching {
                val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                if (Probe.claimStartupRefresh(filesDir)) {
                    Probe.refreshReachability(filesDir, config.optJSONArray("models") ?: org.json.JSONArray(), topOnly = false, force = true)
                }
            }.onFailure { android.util.Log.w("Ruri", "Activity startup route refresh failed", it) }
        }
        try { LifeScheduler.ensurePeriodic(this) }
        catch (e: Exception) { android.util.Log.e("Ruri", "Could not schedule periodic life", e) }
        startRuriService()
        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Android API; retained for WebView file chooser compatibility")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMessageId = intent.getStringExtra("message_id")
        openPendingMessageIfReady()
    }

    private fun openPendingMessageIfReady() {
        val id = pendingMessageId ?: return
        if (!::web.isInitialized) return
        pendingMessageId = null
        web.evaluateJavascript(
            "window.openMessageById && window.openMessageById(" + JSONObject.quote(id) + ")",
            null
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == importFileRequestCode) {
            val callback = importFileCallback
            importFileCallback = null
            val result = if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) arrayOf(uri) else null
            } else null
            callback?.onReceiveValue(result)
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        NetState.refresh(this)
        if (OfflineQueue.hasPending(filesDir) || CommanderReplyQueue.hasPending(filesDir)) {
            LifeScheduler.enqueueEvent(this, "commander_reply_resume")
        }
        if (::web.isInitialized) {
            try { Bridge().markCommanderSeen() } catch (_: Exception) {}
            web.evaluateJavascript("window.refreshOnboarding && window.refreshOnboarding()", null)
        }
    }

    override fun onPause() {
        activityResumed = false
        try { BackupPolicy.maybeRolling(this, "pause") }
        catch (e: Exception) { android.util.Log.w("Ruri", "Pause rolling backup failed", e) }
        super.onPause()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun startRuriService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, RuriService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("Ruri", "Foreground service start failed", e)
            Toast.makeText(this, "Не удалось запустить фоновую службу", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshVisionStatusAsync() {
        synchronized(this) {
            if (visionStatusProbeQueued) return
            visionStatusProbeQueued = true
        }
        routeExecutor.execute {
            try {
                NetState.refresh(applicationContext)
                if (NetState.online) Vision.visionRoute(filesDir)
            } catch (e: Exception) {
                android.util.Log.w("Ruri", "Vision route status refresh failed", e)
            } finally {
                visionStatusProbeQueued = false
            }
            val status = when {
                !NetState.online -> "нет сети"
                else -> Vision.cachedVisionRoute(filesDir)
                    ?.let { it.optString("name").ifBlank { it.optString("id") } }
                    ?.takeIf { it.isNotBlank() } ?: "none"
            }
            mainHandler.post {
                if (::web.isInitialized) {
                    web.evaluateJavascript(
                        "window.setVisionStatus && window.setVisionStatus(" + JSONObject.quote(status) + ")",
                        null
                    )
                }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun getDiagnostics(): String = Diagnostics.run(this@MainActivity)

        @JavascriptInterface
        fun forceRefreshRoutes(): String {
            routeExecutor.execute {
                runCatching {
                    NetState.refresh(applicationContext)
                    val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                    val models = config.optJSONArray("models") ?: org.json.JSONArray()
                    Probe.refreshReachability(filesDir, models, topOnly = false, force = true)
                    mainHandler.post { if (::web.isInitialized) web.evaluateJavascript("window.refreshRouteCards && window.refreshRouteCards()", null) }
                }.onFailure { android.util.Log.w("Ruri", "Manual route refresh failed", it) }
            }
            return "OK"
        }

        @JavascriptInterface
        fun hasPostNotificationsPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        @JavascriptInterface
        fun requestPostNotificationsPermission(): String {
            return try { requestNotificationPermission(); "OK" }
            catch (e: Exception) { "ERROR: " + (e.message ?: "permission request failed") }
        }

        @JavascriptInterface
        fun hasNotificationAccess(): Boolean =
            (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

        @JavascriptInterface
        fun saveShelfFile(name: String, base64: String): String {
            return try {
                val safe = name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
                require(safe.isNotBlank() && safe != "." && safe != "..") { "Invalid file name" }
                require(base64.length <= 12 * 1024 * 1024) { "File is too large" }
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                require(bytes.size <= 8 * 1024 * 1024) { "File is too large" }
                val dir = File(filesDir, "shelf").apply { mkdirs() }
                File(dir, safe).writeBytes(bytes)
                ShelfWatcher.markAdded(this@MainActivity, safe)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "shelf save failed") }
        }

        @JavascriptInterface
        fun completeChat(callbackId: String, commanderMessageId: String): String {
            if (!callbackId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return "ERROR: invalid callback"
            if (!commanderMessageId.matches(Regex("[A-Za-z0-9_-]{1,200}"))) return "ERROR: invalid message id"
            if (!ChatEngine.isCommanderMessage(filesDir, commanderMessageId)) return "ERROR: Commander message not found in L0"
            chatExecutor.execute {
                try {
                    CommanderReplyQueue.enqueue(filesDir, commanderMessageId)
                    val reply = ChatEngine.respond(filesDir, commanderMessageId)
                    if (!CommanderReplyQueue.isAnswered(filesDir, commanderMessageId)) {
                        throw IllegalStateException("Ответ не сохранён; сообщение осталось в очереди")
                    }
                    val messageId = ChatEngine.lastSavedReplyId()
                    mainHandler.post {
                        if (!activityResumed && !messageId.isNullOrBlank()) {
                            Notifier.thought(this@MainActivity, reply, messageId)
                        }
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onNativeChatResult(" + JSONObject.quote(callbackId) + "," +
                                JSONObject.quote(reply) + ",null)", null
                        )
                    }
                } catch (e: Exception) {
                    val error = e.message ?: "Ошибка ответа модели"
                    runCatching { CommanderReplyQueue.enqueue(filesDir, commanderMessageId) }
                    runCatching {
                        LifeScheduler.scheduleCommanderReplyRetry(
                            this@MainActivity,
                            CommanderReplyQueue.nextDelayMs(filesDir) ?: 30_000L
                        )
                    }
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onNativeChatResult(" + JSONObject.quote(callbackId) + ",null," +
                                JSONObject.quote(error) + ")", null
                        )
                    }
                }
            }
            return "OK"
        }

        @JavascriptInterface
        fun read(path: String): String = when (path.replace('\\', '/').trimStart('/')) {
            "core/config.json" -> readPublicRuntimeConfig()
            "core/search.json" -> readPublicSearchConfig()
            "core/secrets.enc.json" -> ""
            else -> FileManager.read(filesDir, path)
        }

        @JavascriptInterface
        fun readLastEvents(filter: String, limit: Int): String =
            FileManager.readLastEvents(filesDir, filter, limit).toString()

        @JavascriptInterface
        fun readLastLines(path: String, limit: Int): String =
            JSONArray(FileManager.readLastLines(filesDir, path, maxLines = limit.coerceIn(1, 1000))).toString()

        @JavascriptInterface
        fun readMemoryLayer(layer: String): String = when (layer) {
            "L0" -> JSONArray(FileManager.readLastLines(filesDir, "memory/L0_raw.jsonl", maxLines = 1000).map {
                runCatching { JSONObject(it) }.getOrNull() ?: JSONObject()
            }).toString()
            "L1" -> FileManager.read(filesDir, "memory/L1_facts.json")
            "L2" -> FileManager.read(filesDir, "memory/L2_diary.json")
            "L3" -> FileManager.read(filesDir, "memory/L3_summary.json")
            "L4" -> FileManager.read(filesDir, "memory/L4_state.json")
            "EMO" -> FileManager.read(filesDir, "memory/emotions.json")
            "SELF" -> FileManager.read(filesDir, "memory/self_model.json")
            else -> "{}"
        }

        @JavascriptInterface
        fun readReceipts(): String = Receipts.export(filesDir)

        @JavascriptInterface
        fun markReadByCommander(id: String): String {
            Receipts.readByCommander(filesDir, id)
            return "OK"
        }

        @JavascriptInterface
        fun deleteMessage(id: String): String {
            return try {
                require(id.isNotBlank() && id.length <= 200) { "Invalid message id" }
                Tombstone.add(filesDir, id)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "delete failed") }
        }

        @JavascriptInterface
        fun isMessageDeleted(id: String): Boolean = Tombstone.isDeleted(filesDir, id)

        @JavascriptInterface
        fun quotaSummary(): String {
            return runCatching {
                val models = JSONObject(SecretStore.readRuntimeConfig(filesDir)).optJSONArray("models") ?: org.json.JSONArray()
                (0 until models.length()).mapNotNull { i ->
                    val model = models.optJSONObject(i) ?: return@mapNotNull null
                    if (!model.optBoolean("free_tier", false)) return@mapNotNull null
                    val id = model.optString("id").ifBlank { model.optString("name") }
                    val meta = RouteMeta.route(filesDir, id)
                    val requests = meta.optInt("day_count", 0)
                    val tokens = RouteMeta.tokensToday(filesDir, id)
                    val limit = model.optLong("token_limit_daily", model.optLong("tokens_per_day", 0L))
                    val until = meta.optLong("quota_exhausted_until", 0L)
                    val tokenText = if (limit > 0) " · токены $tokens/$limit" else " · токены $tokens/лимит не задан"
                    val exhausted = if (until > System.currentTimeMillis()) " · exhausted до " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until)) else ""
                    model.optString("name", id) + ": запросы $requests/200" + tokenText + exhausted
                }.joinToString(" · ")
            }.getOrDefault("")
        }

        @JavascriptInterface
        fun visionStatus(): String {
            refreshVisionStatusAsync()
            return Vision.cachedVisionRoute(filesDir)
                ?.let { it.optString("name").ifBlank { it.optString("id") } }
                ?.takeIf { it.isNotBlank() } ?: "проверяю…"
        }

        @JavascriptInterface
        fun reprobeVision(): String {
            routeExecutor.execute {
                runCatching {
                    val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                    val models = config.optJSONArray("models") ?: org.json.JSONArray()
                    for (index in 0 until models.length()) {
                        val model = models.optJSONObject(index) ?: continue
                        val id = model.optString("id").ifBlank { model.optString("name") }
                        if (id.isBlank()) continue
                        RouteMeta.update(filesDir, id) { route ->
                            val caps = route.optJSONObject("caps") ?: JSONObject()
                            caps.remove("vision")
                            route.put("caps", caps)
                            route.remove("vision_probe_at")
                        }
                    }
                    NetState.refresh(applicationContext)
                    val selected = Vision.visionRoute(filesDir)
                    val status = selected?.optString("name")?.ifBlank { selected.optString("id") }
                        ?: if (NetState.online) "none" else "нет сети"
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.setVisionStatus && window.setVisionStatus(" + JSONObject.quote(status) + ")", null
                        )
                    }
                }.onFailure { error ->
                    android.util.Log.w("Ruri", "Manual vision probe failed", error)
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.setVisionStatus && window.setVisionStatus(" +
                                JSONObject.quote("ошибка: " + (error.message ?: "vision probe")) + ")", null
                        )
                    }
                }
            }
            return "OK"
        }

        @JavascriptInterface
        fun readNetworkDiagnostics(): String = NetworkDiagnostics.latest(filesDir)

        @JavascriptInterface
        fun readAutomaticBackupStatus(): String = LocalBackupManager.status(filesDir).toString()

        @JavascriptInterface
        fun createAutomaticBackupNow(): String {
            return try { JSONObject().put("created", true).put("file", LocalBackupManager.createSnapshot(filesDir).name).toString() }
            catch (e: Exception) { "ERROR: " + (e.message ?: "backup failed") }
        }

        @JavascriptInterface
        fun verifyAutomaticBackup(fileName: String): String {
            val safeName = fileName.takeIf { it.matches(Regex("ruri_auto_[0-9]{8}_[0-9]{6}_[0-9]+\\.zip")) }
                ?: return JSONObject().put("valid", false).put("error", "Invalid backup name").toString()
            val result = LocalBackupManager.verify(File(File(filesDir, "backup/automatic"), safeName))
            return JSONObject().put("valid", result.valid).put("error", result.error)
                .put("files", result.files.size).toString()
        }

        @JavascriptInterface
        fun restoreAutomaticBackup(fileName: String): String {
            return try { LocalBackupManager.restore(filesDir, fileName).toString() }
            catch (e: Exception) { "ERROR: " + (e.message ?: "restore failed") }
        }

        @JavascriptInterface
        fun write(path: String, content: String): String {
            return try {
                require(path == "core/character.json") {
                    "Only character settings may be written through the generic WebView bridge"
                }
                FileManager.write(filesDir, path, content)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "write failed") }
        }

        @JavascriptInterface
        fun markOnboardingDone(): String {
            return try {
                FileManager.updateL4State(filesDir) { it.put("onboarding_done", true) }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "onboarding update failed") }
        }

        @JavascriptInterface
        fun readPublicRuntimeConfig(): String {
            return try { SecretStore.readPublicRuntimeConfig(filesDir) }
            catch (e: Exception) { android.util.Log.e("Ruri", "Cannot read public runtime config", e); "{}" }
        }

        @JavascriptInterface
        fun readPublicSearchConfig(): String {
            return try { SecretStore.readPublicSearchConfig(filesDir) }
            catch (e: Exception) { android.util.Log.e("Ruri", "Cannot read public search config", e); "{}" }
        }

        @JavascriptInterface
        fun saveSearchConfig(raw: String): String {
            return try { SecretStore.saveSearchConfig(filesDir, raw); "OK" }
            catch (e: Exception) { "ERROR: " + (e.message ?: "search config save failed") }
        }

        @JavascriptInterface
        fun saveRuntimeConfig(content: String): String {
            return try { SecretStore.saveRuntimeConfig(filesDir, content); "OK" }
            catch (e: Exception) { "ERROR: " + (e.message ?: "config save failed") }
        }

        @JavascriptInterface
        fun saveHFKey(key: String): String {
            return try {
                val value = key.trim()
                require(value.startsWith("hf_") && value.length >= 20) { "Проверь HF token (обычно начинается с hf_)" }
                val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                val models = config.optJSONArray("models") ?: JSONArray()
                val hf = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == "hf" }
                    ?: throw IllegalStateException("Маршрут Hugging Face не найден")
                hf.put("api_key", value).put("enabled", hf.optString("model").isNotBlank())
                SecretStore.saveRuntimeConfig(filesDir, config.toString(2))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "HF token save failed") }
        }

        @JavascriptInterface
        fun saveHFModel(modelsJson: String): String {
            return try {
                val requested = JSONArray(modelsJson)
                require(requested.length() in 1..30) { "Нет проверенных бесплатных RP/NSFW-моделей" }
                val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                val models = config.optJSONArray("models") ?: JSONArray()
                val hf = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == "hf" }
                    ?: throw IllegalStateException("Маршрут Hugging Face не найден")
                require(hf.optString("api_key").isNotBlank()) { "Сначала сохрани HF token" }
                val verified = (0 until verifiedHFCatalog.length()).mapNotNull { verifiedHFCatalog.optJSONObject(it) }
                require(verified.isNotEmpty()) { "Сначала заново загрузи проверенный каталог HF" }
                val selected = mutableListOf<JSONObject>()
                for (i in 0 until requested.length()) {
                    val candidate = requested.optJSONObject(i) ?: continue
                    val modelId = candidate.optString("model")
                    val provider = candidate.optString("provider")
                    val trusted = verified.firstOrNull {
                        it.optString("model") == modelId && it.optString("provider") == provider
                    } ?: throw IllegalArgumentException("Модель отсутствует в проверенном каталоге HF")
                    val freePromo = trusted.optBoolean("is_free_promo")
                    val zeroPriced = trusted.optDouble("price_input", -1.0) == 0.0 &&
                        trusted.optDouble("price_output", -1.0) == 0.0
                    require(trusted.optBoolean("free") && (freePromo || zeroPriced)) {
                        "Разрешены только подтверждённо бесплатные модели"
                    }
                    selected.add(trusted)
                }
                require(selected.isNotEmpty()) { "Список моделей пуст" }
                for (i in models.length() - 1 downTo 0) {
                    if (models.optJSONObject(i)?.optString("id").orEmpty().startsWith("hf_pool_")) models.remove(i)
                }
                hf.put("model", "").put("enabled", false).put("vision", false)
                selected.forEachIndexed { index, candidate ->
                    val modelId = candidate.getString("model")
                    val provider = candidate.getString("provider")
                    val stableId = "hf_pool_" + Integer.toHexString((modelId + "@" + provider).hashCode())
                    val route = JSONObject(hf.toString())
                    route.put("id", stableId)
                        .put("name", "HF · " + modelId + " · " + provider)
                        .put("model", modelId + ":" + provider)
                        .put("api_key", "")
                        .put("api_key_ref", "hf")
                        .put("enabled", true)
                        .put("free_tier", true)
                        .put("nsfw", true)
                        .put("vision", false)
                        .put("priority", index + 2)
                        .put("hf_model", modelId)
                        .put("hf_provider", provider)
                        .put("hf_price_input", candidate.opt("price_input") ?: JSONObject.NULL)
                        .put("hf_price_output", candidate.opt("price_output") ?: JSONObject.NULL)
                        .put("hf_context_length", candidate.optInt("context_length"))
                        .put("hf_score", candidate.optDouble("score"))
                    models.put(route)
                }
                SecretStore.saveRuntimeConfig(filesDir, config.toString(2))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "HF model pool save failed") }
        }

        @JavascriptInterface
        fun fetchHFModels(): String {
            return try {
                val runtime = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                val models = runtime.optJSONArray("models") ?: JSONArray()
                val hf = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == "hf" }
                    ?: throw IllegalStateException("Маршрут Hugging Face не найден")
                val token = hf.optString("api_key")
                require(token.isNotBlank()) { "Сначала сохрани HF token" }
                routeExecutor.execute {
                    val result = try {
                        fun get(url: String): Triple<Int, String, String?> {
                            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
                            try {
                                connection.requestMethod = "GET"
                                connection.setRequestProperty("Authorization", "Bearer " + token)
                                connection.setRequestProperty("Accept", "application/json")
                                connection.connectTimeout = 15000
                                connection.readTimeout = 25000
                                val code = connection.responseCode
                                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                                return Triple(code, body, connection.getHeaderField("Link"))
                            } finally { connection.disconnect() }
                        }

                        // Enumerate every paginated model currently served by any HF Inference Provider.
                        // This is broader than /v1/models, which may expose only a small router subset.
                        val allHubModels = linkedMapOf<String, JSONObject>()
                        var nextUrl: String? = "https://huggingface.co/api/models?inference_provider=all&pipeline_tag=text-generation&limit=100&sort=downloads&expand=tags"
                        val visitedPages = HashSet<String>()
                        while (nextUrl != null && visitedPages.add(nextUrl)) {
                            val (code, body, link) = get(nextUrl)
                            if (code !in 200..299) throw IllegalStateException("HF Hub HTTP " + code)
                            val page = JSONArray(body)
                            for (i in 0 until page.length()) {
                                val model = page.optJSONObject(i) ?: continue
                                val id = model.optString("id").trim()
                                if (id.isNotBlank()) allHubModels.putIfAbsent(id, model)
                            }
                            val nextMatch = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"").find(link.orEmpty())
                            nextUrl = nextMatch?.groupValues?.getOrNull(1)
                        }

                        fun matches(model: JSONObject, mode: String): Boolean {
                            val id = model.optString("id")
                            val name = model.optString("name")
                            val description = model.optString("description")
                            if (HFModelCatalog.isCandidateId(id, mode) ||
                                HFModelCatalog.isCandidateId(name, mode) ||
                                HFModelCatalog.isCandidateId(description, mode)) return true
                            val tags = model.optJSONArray("tags") ?: JSONArray()
                            return (0 until tags.length()).any { i ->
                                HFModelCatalog.isCandidateId(tags.optString(i), mode)
                            }
                        }

                        // Use the router's authoritative live model listing, which already includes
                        // every provider/status/price record. The Hub catalog supplies discovery
                        // names and tags; avoid hundreds of fragile per-model detail requests.
                        val (routerCode, routerBody, _) = get("https://router.huggingface.co/v1/models")
                        if (routerCode !in 200..299) {
                            throw IllegalStateException("HF Inference Providers catalog HTTP " + routerCode)
                        }
                        val routerData = JSONObject(routerBody).optJSONArray("data") ?: JSONArray()
                        val routerModels = linkedMapOf<String, JSONObject>()
                        for (i in 0 until routerData.length()) {
                            val model = routerData.optJSONObject(i) ?: continue
                            val id = model.optString("id").trim()
                            if (id.isNotBlank()) routerModels[id] = model
                        }

                        fun inspect(mode: String): Pair<JSONArray, IntArray> {
                            val hubCandidates = allHubModels.values.filter { matches(it, mode) }
                            val candidateIds = hubCandidates.map { it.optString("id") }.toSet()
                            val found = JSONArray()
                            val matched = routerModels.values.filter { it.optString("id") in candidateIds }
                            var liveCount = 0
                            var freeCount = 0
                            for (routerModel in matched) {
                                val id = routerModel.optString("id")
                                val hubMeta = allHubModels[id]
                                val enriched = JSONObject(routerModel.toString())
                                if (hubMeta != null) {
                                    enriched.put("name", hubMeta.optString("name").ifBlank { id })
                                    enriched.put("description", hubMeta.optString("description"))
                                    enriched.put("tags", hubMeta.optJSONArray("tags") ?: JSONArray())
                                }
                                val parsed = HFModelCatalog.parseDetailed(
                                    JSONObject().put("data", JSONArray().put(enriched)).toString(), mode
                                )
                                liveCount += parsed.liveProviders
                                freeCount += parsed.freeProviders
                                for (j in 0 until parsed.models.length()) {
                                    found.put(parsed.models.getJSONObject(j))
                                }
                            }
                            val sorted = (0 until found.length()).mapNotNull { found.optJSONObject(it) }
                                .sortedByDescending { it.optDouble("score", 0.0) }
                            val output = JSONArray()
                            sorted.take(30).forEach(output::put)
                            return output to intArrayOf(hubCandidates.size, liveCount, freeCount)
                        }

                        var mode = "rp"
                        var (candidates, stats) = inspect(mode)
                        // If there are no confirmed free RP routes, broaden the search to NSFW-tagged
                        // and NSFW-named models; the free-only provider filter remains unchanged.
                        if (candidates.length() == 0) {
                            mode = "nsfw"
                            val fallback = inspect(mode)
                            candidates = fallback.first
                            stats = fallback.second
                        }
                        val statsJson = JSONObject()
                            .put("hub_models_seen", allHubModels.size)
                            .put("models_seen", allHubModels.size)
                            .put("candidate_mode", mode)
                            .put("roleplay_matches", if (mode == "rp") stats[0] else 0)
                            .put("nsfw_matches", if (mode == "nsfw") stats[0] else 0)
                            .put("live_providers", stats[1])
                            .put("free_providers", stats[2])
                        JSONObject().put("ok", true).put("models", candidates).put("stats", statsJson)
                    } catch (error: Exception) {
                        JSONObject().put("ok", false).put("error", error.message ?: "HF Hub discovery failed")
                    }
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onHFModelsResult && window.onHFModelsResult(" + JSONObject.quote(result.toString()) + ")", null
                        )
                    }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "HF model discovery failed") }
        }

        @JavascriptInterface
        fun saveOpenRouterKey(key: String): String {
            return try {
                val value = key.trim()
                require(value.startsWith("sk-or-") && value.length >= 30) {
                    "Проверь OpenRouter API key (обычно начинается с sk-or-v1-)"
                }
                val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                val models = config.optJSONArray("models") ?: JSONArray()
                var route = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == "openrouter" }
                if (route == null) {
                    val template = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                        .firstOrNull { it.optString("id") == "hf" }
                        ?: throw IllegalStateException("Не найден шаблон маршрута для OpenRouter")
                    route = JSONObject(template.toString()).apply {
                        put("id", "openrouter")
                        remove("api_key_ref")
                        remove("hf_model")
                        remove("hf_provider")
                        put("name", "OpenRouter · free NSFW pool")
                        put("model", "")
                        put("base_url", "https://openrouter.ai/api/v1")
                        put("enabled", false)
                        put("vision", false)
                        put("priority", 999)
                    }
                    models.put(route)
                }
                route.put("api_key", value)
                    .put("base_url", "https://openrouter.ai/api/v1")
                    .put("enabled", false)
                    .put("model", "")
                route.remove("api_key_ref")
                SecretStore.saveRuntimeConfig(filesDir, config.toString(2))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "OpenRouter key save failed") }
        }

        @JavascriptInterface
        fun fetchOpenRouterModels(): String {
            return try {
                val runtime = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                val models = runtime.optJSONArray("models") ?: JSONArray()
                val openRouter = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == "openrouter" }
                    ?: throw IllegalStateException("Сначала сохрани OpenRouter API key")
                val token = openRouter.optString("api_key")
                require(token.isNotBlank()) { "Сначала сохрани OpenRouter API key" }
                routeExecutor.execute {
                    val result = try {
                        val connection = (java.net.URL("https://openrouter.ai/api/v1/models").openConnection()
                            as java.net.HttpURLConnection)
                        try {
                            connection.requestMethod = "GET"
                            connection.setRequestProperty("Authorization", "Bearer " + token)
                            connection.setRequestProperty("Accept", "application/json")
                            connection.setRequestProperty("HTTP-Referer", "https://ruri.app")
                            connection.setRequestProperty("X-Title", "Ruri")
                            connection.connectTimeout = 15000
                            connection.readTimeout = 30000
                            val code = connection.responseCode
                            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                            if (code !in 200..299) {
                                val detail = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                                throw IllegalStateException("OpenRouter catalog HTTP " + code +
                                    if (detail.isBlank()) "" else ": " + detail.take(240))
                            }
                            val parsed = OpenRouterCatalog.parseDetailed(body)
                            verifiedOpenRouterCatalog = JSONArray(parsed.models.toString())
                            JSONObject()
                                .put("ok", true)
                                .put("models", parsed.models)
                                .put("stats", parsed.toJson())
                        } finally { connection.disconnect() }
                    } catch (error: Exception) {
                        JSONObject().put("ok", false)
                            .put("error", error.message ?: "OpenRouter catalog discovery failed")
                    }
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onOpenRouterModelsResult && window.onOpenRouterModelsResult(" +
                                JSONObject.quote(result.toString()) + ")", null
                        )
                    }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "OpenRouter model discovery failed") }
        }

        @JavascriptInterface
        fun saveOpenRouterModels(modelsJson: String): String {
            return try {
                val requested = JSONArray(modelsJson)
                require(requested.length() in 1..30) { "Нет подтверждённых бесплатных NSFW/RP-моделей OpenRouter" }
                val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                val models = config.optJSONArray("models") ?: JSONArray()
                val openRouter = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == "openrouter" }
                    ?: throw IllegalStateException("Маршрут OpenRouter не найден")
                require(openRouter.optString("api_key").isNotBlank()) { "Сначала сохрани OpenRouter API key" }
                val verified = (0 until verifiedOpenRouterCatalog.length())
                    .mapNotNull { verifiedOpenRouterCatalog.optJSONObject(it) }
                require(verified.isNotEmpty()) { "Сначала заново загрузи проверенный каталог OpenRouter" }
                val selected = mutableListOf<JSONObject>()
                for (i in 0 until requested.length()) {
                    val requestedModel = requested.optJSONObject(i) ?: continue
                    val modelId = requestedModel.optString("model")
                    val trusted = verified.firstOrNull { it.optString("model") == modelId }
                        ?: throw IllegalArgumentException("Модель отсутствует в проверенном каталоге OpenRouter")
                    require(modelId.endsWith(":free", ignoreCase = true) &&
                        trusted.optBoolean("free") &&
                        trusted.optDouble("price_input", -1.0) == 0.0 &&
                        trusted.optDouble("price_output", -1.0) == 0.0) {
                        "Разрешены только варианты :free с нулевыми ценами input/output"
                    }
                    selected.add(trusted)
                }
                require(selected.isNotEmpty()) { "Список моделей пуст" }
                for (i in models.length() - 1 downTo 0) {
                    if (models.optJSONObject(i)?.optString("id").orEmpty().startsWith("or_pool_")) models.remove(i)
                }
                openRouter.put("model", "")
                    .put("enabled", false)
                    .put("vision", false)
                    .put("base_url", "https://openrouter.ai/api/v1")
                selected.forEachIndexed { index, candidate ->
                    val modelId = candidate.getString("model")
                    val stableId = "or_pool_" + Integer.toHexString(modelId.hashCode())
                    val route = JSONObject(openRouter.toString())
                    route.put("id", stableId)
                        .put("name", "OpenRouter · " + candidate.optString("name", modelId))
                        .put("model", modelId)
                        .put("base_url", "https://openrouter.ai/api/v1")
                        .put("api_key", "")
                        .put("api_key_ref", "openrouter")
                        .put("enabled", true)
                        .put("free_tier", true)
                        .put("nsfw", true)
                        .put("vision", false)
                        .put("priority", index + 50)
                        .put("openrouter_model", modelId)
                        .put("openrouter_free", true)
                        .put("openrouter_price_input", candidate.optDouble("price_input"))
                        .put("openrouter_price_output", candidate.optDouble("price_output"))
                        .put("openrouter_context_length", candidate.optInt("context_length"))
                        .put("openrouter_score", candidate.optDouble("score"))
                    models.put(route)
                }
                SecretStore.saveRuntimeConfig(filesDir, config.toString(2))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "OpenRouter model pool save failed") }
        }

        @JavascriptInterface
        fun saveRouteOrder(json: String): String {
            return try {
                val payload = JSONObject(json)
                val config = JSONObject(SecretStore.readRuntimeConfig(filesDir))
                RouteSelector.applyManualRouteOrder(config, payload)
                SecretStore.saveRuntimeConfig(filesDir, config.toString(2))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "route order save failed") }
        }

        @JavascriptInterface
        fun appendEvent(eventJson: String): String {
            return try {
                val event = JSONObject(eventJson)
                require(event.optString("type") == "message" && event.optString("who") == "commander") {
                    "The WebView may append Commander messages only"
                }
                require(event.optString("text").isNotBlank()) { "Message text is empty" }
                require(event.optString("id").matches(Regex("[A-Za-z0-9_-]{1,200}"))) {
                    "Invalid Commander message id"
                }
                val saved = JSONObject(FileManager.appendEvent(filesDir, event))
                if (event.optString("type") == "message" && event.optString("who") == "commander") {
                    val messageId = saved.optString("id")
                    runCatching { CommanderReplyQueue.enqueue(filesDir, messageId) }
                    runCatching { Receipts.delivered(filesDir, messageId) }
                    runCatching { OfflineQueue.markPending(filesDir, messageId) }
                    runCatching {
                        LifeScheduler.enqueueEvent(this@MainActivity, "commander_messages_pending")
                    }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "event append failed") }
        }

        @JavascriptInterface
        fun list(path: String): String = FileManager.list(filesDir, path)

        @JavascriptInterface
        fun exists(path: String): Boolean = FileManager.exists(filesDir, path)

        @JavascriptInterface
        fun markCommanderSeen(): String {
            return try {
                val latestReply = FileManager.readLastLines(
                    filesDir, "memory/L0_raw.jsonl", maxLines = 500
                ).asReversed().asSequence()
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    .firstOrNull { it.optString("type") == "message" && it.optString("who") == "ruri" }
                val id = latestReply?.optString("id").orEmpty()
                if (id.isNotBlank()) Receipts.readByCommander(filesDir, id)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "receipt update failed") }
        }

        @JavascriptInterface
        fun searchWeb(query: String): String = WebSearch.search(filesDir, query)

        @JavascriptInterface
        fun openNotificationAccessSettings(): String {
            return try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "settings unavailable") }
        }

        @JavascriptInterface
        fun hasUsageAccess(): Boolean = UsageAccessSensor.hasAccess(this@MainActivity)

        @JavascriptInterface
        fun openUsageAccessSettings(): String {
            return try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "usage access settings unavailable") }
        }

        @JavascriptInterface
        fun openUrl(url: String): String {
            return try {
                val uri = Uri.parse(url)
                if (uri.scheme != "https" && uri.scheme != "http") return "ERROR: unsupported URL"
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "cannot open link") }
        }

        @JavascriptInterface
        fun importArchive(json: String): String {
            return try {
                val archive = JSONObject(json)
                LocalBackupManager.createSnapshot(filesDir)
                val allowedRoots = listOf("core/", "memory/", "shelf/", "research/", "skills/", "proposals/")
                val keys = archive.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    val value = archive.opt(path)
                    if (value !is String) continue
                    if (path == "core/secrets.enc.json") continue
                    if (!allowedRoots.any { path.startsWith(it) }) continue
                    when (path) {
                        "memory/L0_raw.jsonl" -> FileManager.replaceL0FromArchive(filesDir, value)
                        "core/config.json" -> SecretStore.saveRuntimeConfig(filesDir, value)
                        else -> FileManager.write(filesDir, path, value)
                    }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "invalid archive") }
        }

        @JavascriptInterface
        fun exportArchive(json: String): String {
            return try {
                val directory = File(cacheDir, "exports")
                if (!directory.exists()) directory.mkdirs()
                val archive = File(directory, "ruri_backup_" + System.currentTimeMillis() + ".json")
                archive.writeText(json, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", archive)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, "Архив Рури", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread {
                    try { startActivity(Intent.createChooser(intent, "Сохранить архив Рури")) }
                    catch (e: Exception) { Toast.makeText(this@MainActivity, "Не удалось открыть экспорт", Toast.LENGTH_LONG).show() }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "export failed") }
        }
    }
}