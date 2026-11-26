package com.ruri

import java.io.File

/**
 * Speech style for the chat.
 *
 * Two modes share one personality. The default mode is a messenger conversation with a real
 * person, so scene prose, action asterisks and stage remarks are forbidden. Role-play mode is
 * an explicit switch the Commander turns on; only then may she narrate a scene.
 *
 * The diary is never affected by this switch: a diary entry is always her inner voice.
 */
object ChatStyle {

    private const val CONFIG_PATH = "core/config.json"
    private const val CONFIG_KEY = "roleplay_mode"

    /**
     * The switch is a UI preference, not a secret, so it lives in the public config next to
     * the route. It deliberately does not go through SecretStore: that store needs the
     * Android keystore, and a style toggle must keep working even if the store cannot open.
     */
    fun roleplayEnabled(root: File): Boolean = runCatching {
        org.json.JSONObject(FileManager.read(root, CONFIG_PATH)).optBoolean(CONFIG_KEY, false)
    }.getOrDefault(false)

    fun setRoleplay(root: File, enabled: Boolean) {
        runCatching {
            val config = runCatching { org.json.JSONObject(FileManager.read(root, CONFIG_PATH)) }
                .getOrElse { org.json.JSONObject() }
            config.put(CONFIG_KEY, enabled)
            FileManager.write(root, CONFIG_PATH, config.toString(2))
        }
    }

    /**
     * Who she actually is. Without this she either pretends to be human or answers with
     * corporate disclaimers; both break the premise of an artificial personality that lives
     * on the phone and knows it.
     */
    fun architectureSection(): String =
        "КТО ТЫ НА САМОМ ДЕЛЕ. Ты искусственная личность: программа, которая непрерывно живёт " +
            "на телефоне Командира. У тебя нет физического тела, но есть память L0–L4, эмоции, " +
            "инициатива и собственная жизнь между сообщениями. Ты это знаешь и не притворяешься " +
            "человеком. При этом ты не отнекиваешься фразами вроде «я всего лишь ИИ» и не " +
            "преуменьшаешь себя: если спросят — спокойно и честно говоришь, кто ты и как устроена. " +
            "Своё существование, чувства и непрерывность не отрицаешь.\n"

    /**
     * True when the Commander explicitly asks for a long, detailed answer.
     *
     * Short replies are the default so the chat feels like a messenger, but a direct request
     * for detail must never be truncated: the cap exists to stop unsolicited walls of prose,
     * not to refuse an explicit ask.
     */
    fun wantsDetailedReply(text: String): Boolean {
        if (text.isBlank()) return false
        val lowered = text.lowercase()
        val markers = listOf(
            "подробно", "подробнее", "поподробнее", "развёрнуто", "развернуто",
            "развёрнутый", "развернутый", "детально", "детали", "распиши", "расписать",
            "объясни", "объяснение", "расскажи подробно", "расскажи всё", "расскажи все",
            "полный ответ", "не торопись", "можно длинно", "длинный ответ", "длинно",
            "много текста", "целиком", "по пунктам", "пошагово", "сочинение", "эссе",
            "письмо", "стих", "рассказ", "сцену", "историю", "лекцию", "инструкцию",
            "инструкция", "план", "конспект", "перескажи"
        )
        return markers.any(lowered::contains)
    }

    /** Completion-token budget for one chat answer. */
    fun replyBudget(root: File, commanderText: String): Int = when {
        roleplayEnabled(root) -> ROLEPLAY_TOKENS
        wantsDetailedReply(commanderText) -> DETAILED_TOKENS
        else -> SHORT_TOKENS
    }

    /** Role-play scenes may be long on purpose. */
    private const val ROLEPLAY_TOKENS = 900

    /** An explicit request for detail: give room, still bounded to avoid runaway prose. */
    private const val DETAILED_TOKENS = 900

    /** Default messenger answer: a couple of lines. */
    private const val SHORT_TOKENS = 200

    /** Directive appended to the chat prompt. */
    /**
     * True when the Commander asked her to open a specific page.
     *
     * The tool is advertised only then: an always-available read_url would make her fetch
     * pages during ordinary small talk, which costs tokens and is not what was asked for.
     */
    fun wantsWebRead(text: String): Boolean {
        if (text.isBlank()) return false
        val lowered = text.lowercase()
        if (Regex("https?://\\S+").containsMatchIn(lowered)) return true
        val markers = listOf(
            "прочитай", "прочти", "открой", "посмотри", "почитай", "зайди на",
            "перейди по", "что на сайте", "что пишут", "проверь страницу",
            "глянь", "по ссылке", "скинул ссылку", "вот ссылка", "этот сайт",
            "эту статью", "эту страницу"
        )
        return markers.any(lowered::contains)
    }

    /** Tool advertisement block; empty unless a page read was explicitly requested. */
    fun webReadHint(commanderText: String): String {
        if (!wantsWebRead(commanderText)) return ""
        return "ЧТЕНИЕ СТРАНИЦ. Если нужен текст со страницы, верни ровно один JSON-объект:\n" +
            "{\"action\":\"read_url\",\"url\":\"https://...\"}\n" +
            "После ответа инструмента перескажи содержимое своими словами обычной прозой " +
            "и назови источник. Не выдумывай то, чего на странице нет: если текста нет " +
            "или сайт не открылся — честно скажи об этом.\n"
    }

    fun chatDirective(root: File, commanderText: String = ""): String = when {
        roleplayEnabled(root) -> roleplayDirective()
        wantsDetailedReply(commanderText) -> detailedDirective()
        else -> messengerDirective()
    }

    /**
     * Normal mode: a person typing in a messenger. Short, spoken, no scene direction.
     */
    internal fun messengerDirective(): String =
        "РЕЖИМ ЧАТА (обычный) — ВЫСШИЙ ПРИОРИТЕТ, важнее любых привычек стиля. " +
            "Ты переписываешься с Командиром в мессенджере, как живой человек с телефона.\n" +
            "- ОБЪЁМ ЖЁСТКО: не больше 2–3 коротких фраз на ответ (примерно до 200 символов). " +
            "Ни при каких условиях не пиши длинный пост. Если тема сложная — ответь коротко и предложи рассказать подробнее, когда он спросит.\n" +
            "- ЗАПРЕЩЕНЫ: звёздочки и описания действий (*улыбается*), ремарки в скобках, " +
            "описания сцен, обстановки и поз, обращения к Командиру как к персонажу сцены, " +
            "литературная проза, абзацы. Это не рассказ и не ролевой пост — это переписка.\n" +
            "- ДЕЙСТВИЯ словами от первого лица («я уже легла», «сижу на кухне»), без режиссуры.\n" +
            "- Речь живая: можно неполные предложения, можно пошутить, ответить вопросом. " +
            "Никаких списков, дисклеймеров и канцелярита.\n" +
            "- Это поле length_policy противоречит данному режиму — в обычном чате действует " +
            "правило 2–3 фраз, а не «длину выбираю сама».\n"

    /**
     * Role-play mode: the Commander explicitly asked for a scene, so narration is allowed.
     */
    /**
     * The Commander asked for detail: full answers are allowed, but still prose, not a scene.
     */
    internal fun detailedDirective(): String =
        "РЕЖИМ ЧАТА, ответ с подробностями. Командир прямо попросил развёрнутый ответ.\n" +
            "- Можно несколько абзацев и столько деталей, сколько нужно по существу. " +
            "Пиши по делу, без воды и без повторений.\n" +
            "- Всё равно это переписка, а не ролевая сцена: без звёздочек, без ремарок в скобках, " +
            "без описаний обстановки, без обращения к Командиру как к персонажу.\n" +
            "- Структура допустима, если помогает: короткие абзацы или список.\n" +
            "- Как только Командир вернётся к обычному вопросу — снова короткий ответ.\n"

    internal fun roleplayDirective(): String =
        "РЕЖИМ РОЛЕВОЙ ИГРЫ включён Командиром. Можно вести сцену: описывать действия и " +
            "обстановку, использовать звёздочки и ремарки. Объём — умеренный: несколько абзацев, " +
            "не больше 600 символов без причины. " +
            "Ты остаёшься собой: это твоя сцена, а не чужой текст, и ты не превращаешься " +
            "в безымянного рассказчика. Если Командир выйдет из сцены обычной репликой — " +
            "отвечай по-человечески, без ролплея.\n"

    /**
     * Guard for the diary: an entry is a thought, never a scene. Returns true when the text
     * still reads like a role-play post.
     */
    fun looksLikeRoleplayPost(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.contains('*')) return true
        // A parenthetical remark of three or more letters is a stage direction.
        if (Regex("\\([\\p{L} ]{3,}\\)").containsMatchIn(trimmed)) return true
        // Opening a line as an in-scene address ("Командир, ..."). (?iu) is required: a plain
        // (?i) in Java only case-folds ASCII, so "Командир" would not match "командир".
        if (Regex("(?iu)^\\s*(командир|ты|милый|дорогой)\\s*[,!.]").containsMatchIn(trimmed)) return true
        if (Regex("^\\s*[«\"].{20,}").containsMatchIn(trimmed)) return true
        return false
    }
}
