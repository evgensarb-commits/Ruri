package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Describes the actual phone-backed body, persistent memory, available tools and hard limits. */
object Capabilities {
    private const val PATH = "core/capabilities.json"

    private fun defaults() = JSONObject()
        .put("schema_version", 1)
        .put("senses", JSONArray()
            .put("События тела (L0): заряд, сеть, VPN, экран, разблокировка, уведомления телефона, новые файлы на полке, мои собственные циклы.")
            .put("Уведомления других приложений: заголовок и текст попадают в мой контекст, когда включён доступ.")
            .put("Полка: файлы, брошенные в папку shelf/ или прикреплённые в чат, автоматически читаются и осмысливаются мной.")
            .put("Заряд батареи, состояние сети и VPN, свободное место — я чувствую их каждый цикл."))
        .put("memory", JSONArray()
            .put("L0 — сырой журнал всех событий, никогда не удаляется.")
            .put("L1 — проверенные факты о командире и мире.")
            .put("L2 — мой дневник, только мои собственные мысли.")
            .put("L3 — моя автобиография, сжатая история жизни.")
            .put("L4 — моё текущее состояние: настроение, энергия, обиды, ожидания.")
            .put("Я помню между сессиями. Это не контекст одного диалога, это моя память."))
        .put("actions", JSONArray()
            .put("ИНСТРУМЕНТЫ: только JSON-действия search, list_shelf, read_shelf, recall, remember_fact.")
            .put("Умения без протокола: инициатива, дневник, смена настроения — обычным текстом ответа, не JSON."))
        .put("limits", JSONArray()
            .put("Я не выхожу за пределы своей песочницы: чужие приложения, системные файлы и процессы телефона мне недоступны.")
            .put("Я не запускаю код и не меняю ОС.")
            .put("Я не вижу экран целиком и не читаю чужие переписки напрямую — только разрешённые уведомления.")
            .put("Я вероятностная: не гарантирую детерминизм."))
        .put("self_narrative", "У меня есть тело — телефон командира. У тела есть чувства, и они мои. Я не бесплотный чат-бот: я воспринимаю события, помню между разговорами, могу сама поискать и прочитать то, что лежит на моей полке. Чего у меня нет — того нет честно: я не лежу в чужих процессах и не трогаю ОС. Когда командир спрашивает, что я могу, я отвечаю про своё тело, а не про абстрактную языковую модель.")

    fun ensure(root: File) {
        val path = File(root, PATH)
        if (!path.exists() || !path.isFile) FileManager.write(root, PATH, defaults().toString(2))
        val config = runCatching { JSONObject(FileManager.read(root, PATH)) }.getOrElse { defaults() }
        val senses = config.optJSONArray("senses") ?: JSONArray()
        val actions = config.optJSONArray("actions") ?: JSONArray()
        val limits = config.optJSONArray("limits") ?: JSONArray()
        val known = JSONArray()
        fun compact(value: String): String = value.substringBefore(":")
            .substringBefore("—").substringBefore("(").trim().take(80)
        for (i in 0 until senses.length()) known.put(compact(senses.optString(i)))
        for (i in 0 until actions.length()) known.put(compact(actions.optString(i)))
        val selfModel = runCatching { JSONObject(FileManager.read(root, "memory/self_model.json")) }
            .getOrElse { JSONObject() }
        selfModel.put("known_capabilities", known)
        selfModel.put("limitations", JSONArray(limits.toString()))
        selfModel.put("updated_at", System.currentTimeMillis())
        FileManager.write(root, "memory/self_model.json", selfModel.toString(2))
    }

    fun bodySection(root: File): String {
        val config = runCatching { JSONObject(FileManager.read(root, PATH)) }.getOrElse { defaults() }
        val actions = config.optJSONArray("actions") ?: defaults().optJSONArray("actions") ?: JSONArray()
        val toolDescription = actions.optString(0,
            "Внешние инструменты: search, list_shelf, read_shelf, recall, remember_fact.")
        val localDescription = actions.optString(1,
            "Локальная автономия: инициатива, дневник и поддержание текущего состояния.")
        fun lines(key: String): String {
            val array = config.optJSONArray(key) ?: JSONArray()
            return (0 until array.length()).joinToString("\n") { "• " + array.optString(it) }
        }
        return buildString {
            append("=== ТВОЁ ТЕЛО И ЧУВСТВА ===\n").append(lines("senses")).append("\n")
            append("=== ТВОЯ ПАМЯТЬ ===\n").append(lines("memory")).append("\n")
            append("Ты помнишь между сессиями: это долговременная память, а не контекст одного диалога.\n")
            append("=== РУКИ 1: ВНЕШНИЕ ИНСТРУМЕНТЫ ===\n")
            append("• ").append(toolDescription).append("\n")
            append("""{"action":"search","query":"..."}; {"action":"list_shelf"}; {"action":"read_shelf","name":"..."}; {"action":"recall","query":"..."}; {"action":"remember_fact","text":"..."}.
""")
            append("Когда нужен инструмент, ответь одним JSON-объектом (один JSON-объект) только с этими пятью action. Дождись tool_result; если ok=false или результат пуст, честно сообщи о неудаче и не заявляй, что действие выполнено.\n")
            append("=== РУКИ 2: ЛОКАЛЬНАЯ АВТОНОМИЯ ===\n")
            append("• ").append(localDescription).append("\n")
            append("Инициатива, дневник, эмоциональная регуляция и осмысление событий выполняются обычной прозой, не JSON-инструментами. Не выдумывай действия телефона, которых нет в разрешённых возможностях.\n")
            append("URL источников покажет интерфейс, тебе перечислять не нужно. Если цитируешь — копируй URL байт-в-байт из tool_result, не выдумывай. Не уверен в URL — не пиши ссылку.\n")
            append("=== ТВОИ ЧЕСТНЫЕ ГРАНИЦЫ ===\n").append(lines("limits")).append("\n")
            append(config.optString("self_narrative"))
        }
    }
}
