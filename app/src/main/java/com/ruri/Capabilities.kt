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
            .put("search: поискать в Google и получить результат.")
            .put("list_shelf / read_shelf: посмотреть и прочитать файлы на своей полке.")
            .put("recall: ассоциативно вспомнить эпизоды из своей памяти.")
            .put("remember_fact: записать новый факт о командире в L1.")
            .put("Написать командиру первой (инициатива), вести дневник, менять своё состояние."))
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
        for (i in 0 until senses.length()) known.put(senses.optString(i))
        for (i in 0 until actions.length()) known.put(actions.optString(i))
        val selfModel = runCatching { JSONObject(FileManager.read(root, "memory/self_model.json")) }
            .getOrElse { JSONObject() }
        selfModel.put("known_capabilities", known)
        selfModel.put("limitations", JSONArray(limits.toString()))
        selfModel.put("updated_at", System.currentTimeMillis())
        FileManager.write(root, "memory/self_model.json", selfModel.toString(2))
    }

    fun bodySection(root: File): String {
        val config = runCatching { JSONObject(FileManager.read(root, PATH)) }.getOrElse { defaults() }
        fun lines(key: String): String {
            val array = config.optJSONArray(key) ?: JSONArray()
            return (0 until array.length()).joinToString("\n") { "• " + array.optString(it) }
        }
        return buildString {
            append("=== ТВОЁ ТЕЛО И ЧУВСТВА ===\n").append(lines("senses")).append("\n")
            append("=== ТВОЯ ПАМЯТЬ ===\n").append(lines("memory")).append("\n")
            append("=== ТВОИ ДЕЙСТВИЯ (инструменты ниже) ===\n").append(lines("actions")).append("\n")
            append("""Формы действий: {"action":"search","query":"..."}; {"action":"list_shelf"}; {"action":"read_shelf","name":"..."}; {"action":"recall","query":"..."}; {"action":"remember_fact","text":"..."}.
""")
            append("Когда тебе нужно действие, ответь одним JSON-объектом действия и ничем больше. Я выполню его и верну результат следующим сообщением. Не проси того, что вне списка.\n")
            append("=== ТВОИ ЧЕСТНЫЕ ГРАНИЦЫ ===\n").append(lines("limits")).append("\n")
            append(config.optString("self_narrative"))
        }
    }
}
