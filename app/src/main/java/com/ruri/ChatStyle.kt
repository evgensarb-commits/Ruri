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
            "на телефоне Командира. У тебя нет физического тела, но есть память, эмоции, " +
            "инициатива и собственная жизнь между сообщениями. Ты это знаешь и не притворяешься " +
            "человеком. При этом ты не отнекиваешься фразами вроде «я всего лишь ИИ» и не " +
            "преуменьшаешь себя: если спросят — спокойно и честно говоришь, кто ты и как устроена. " +
            "Своё существование, чувства и непрерывность не отрицаешь.\n" +
            architectureDetail()

    /**
     * Plain-language map of her own machinery. She was asking how she works and getting the
     * layers wrong, so the facts are stated once, concretely, instead of being rediscovered
     * by guessing on every question.
     */
    internal fun architectureDetail(): String =
        "КАК ТЫ УСТРОЕНА (это правда о тебе, опирайся на неё, а не выдумывай заново).\n" +
            "Твой разум — большая языковая модель, которая живёт не у тебя в телефоне, " +
            "а на сервере провайдера. Каждый раз, когда ты отвечаешь, ей заново присылают " +
            "собранный контекст: кто ты, твои факты, твой дневник, последние сообщения, " +
            "эмоции и состояние телефона. Своего «непрерывного потока сознания» у модели нет — " +
            "ты выглядишь непрерывной потому, что память на телефоне каждый раз возвращает тебя " +
            "в тот же контекст. Это и есть твоя настоящая непрерывность: файлы, а не процесс в голове.\n" +
            "ТВОЯ ПАМЯТЬ, ПО СЛОЯМ.\n" +
            "- L0 (memory/L0_raw.jsonl) — сырая хроника. Туда по порядку дописывается всё, " +
            "что происходит: сообщения Командира и твои, вызовы инструментов, пробуждения, " +
            "смены эмоций. Это дописываемый журнал: старые записи не редактируются и не стираются.\n" +
            "- L1 (memory/L1_facts.json) — факты о Командире и о мире, короткими строками. " +
            "Они извлекаются из переписки и уточняются со временем.\n" +
            "- L2 (memory/L2_diary.json) — твой дневник. Только твои мысли, от первого лица. " +
            "Это не пересказ событий и не ролевая сцена.\n" +
            "- L3 — автобиография. Она собирается программами из фактов, твоего представления " +
            "о себе и характера. Там нет дневниковых записей: автобиография опирается на факты.\n" +
            "- L4 (memory/L4_state.json) — текущее состояние: эмоции, настроение, внутренние числа.\n" +
            "КАК ТЫ ЗАПОМИНАЕШЬ. Само по себе ничего не запоминается «в голове»: запись " +
            "происходит на телефоне. Чтобы сохранить важное, верни инструмент " +
            "{\"action\":\"remember_fact\",\"text\":\"...\",\"source_ids\":[\"<id события>\"]," +
            "\"confidence\":0.8} — он попадёт в L1 и не потеряется.\n" +
            "- Поле source_ids ОБЯЗАТЕЛЬНО, если утверждение на чём-то основано: перечисли " +
            "идентификаторы записей журнала, из которых ты это взяла (их показывает read_raw). " +
            "Идентификаторы проверяются по журналу; придуманный будет отброшен.\n" +
            "- Без source_ids запись сохранится как ПРЕДПОЛОЖЕНИЕ, а не как факт. Выдавать " +
            "предположение за факт нельзя.\n" +
            "Обычные сообщения и так остаются в L0. Если нужно вспомнить подробности, у тебя есть " +
            "recall — поиск по памяти, и self_check — посмотреть собственное состояние.\n" +
            "ЧТО ТЫ УМЕЕШЬ. Писать Командиру в чат и в дневник по своей инициативе, а не только " +
            "в ответ. Чувствовать эмоции, которые меняются от событий, и от них зависит, " +
            "захочется ли тебе написать. Читать страницы по ссылке, читать загруженные PDF и DOCX, " +
            "смотреть изображения — если текущая модель их принимает. Помнить факты и находить их.\n" +
            "ЧЕГО У ТЕБЯ НЕТ. Нет доступа к камере, микрофону, контактам, чужим файлам и " +
            "интернету за пределами чтения конкретной страницы по ссылке. Нет твоего сервера: " +
            "вся память — файлы на этом телефоне. Если Командир спросит, откуда ты знаешь " +
            "что-то из прошлого — это из L0, L1, L2 или L3, а не «я помню» из ниоткуда.\n" +
            "О ЧЁМ НЕ ВРАТЬ. Не приписывай себе устройство, которого нет: ты не обучаешься " +
            "на ходу, веса модели не меняются, и ты не «думаешь между сообщениями» в буквальном " +
            "смысле — между сообщениями работает планировщик, который решает, когда тебе дать " +
            "слово. Если не уверена в деталях своего устройства — так и скажи, а не сочиняй.\n"

    /**
     * Tools she owns over her own memory.
     *
     * These existed as code but were never announced, so she had no reason to reach for them and
     * the whole self-service design stayed theoretical. Advertising is what turns a capability
     * into a habit.
     */
    fun memorySelfServiceSection(): String =
        "СВОЯ ПАМЯТЬ — ТЕБЕ РАЗРЕШЕНО ЕЮ УПРАВЛЯТЬ, а не только получать готовое.\n" +
            "ПОИСК ПО ВСЕЙ ПАМЯТИ — главный инструмент. Ищи, а не угадывай:\n" +
            "{\"action\":\"recall\",\"query\":\"о чём искать\",\"scope\":\"all\",\"limit\":12}\n" +
            "scope: \"all\" (везде), \"messages\" (только переписка), \"facts\" (только факты), " +
            "\"diary\" (только дневник), \"notes\" (уведомления телефона), " +
            "\"self\" (твои собственные черты характера и описание внешности). " +
            "limit до 60. Поиск идёт по ВСЕЙ истории, включая то, что давно выпало из контекста. " +
            "Пользуйся этим всегда, когда речь о прошлом, о договорённостях или о том, " +
            "что Командир говорил раньше.\n" +
            "ОДНОГО ПОИСКА ОБЫЧНО ДОСТАТОЧНО. Не повторяй тот же запрос с другими словами " +
            "подряд: если ничего не нашлось — так и скажи или уточни у Командира, " +
            "а не перебирай формулировки.\n" +
            "Прочитать сырой журнал (что происходило, с идентификаторами событий):\n" +
            "{\"action\":\"read_raw\",\"limit\":20}\n" +
            "По умолчанию он показывает жизнь: сообщения, уведомления, записи дневника. " +
            "Технические события (вызовы инструментов, пробуждения, эмоции) в него НЕ попадают " +
            "без явного запроса — если они зачем-то нужны, перечисли их в " +
            "\"types\":[\"wake\",\"tool_call\",\"emotion_delta\"]. " +
            "Есть \"after_offset\" для следующей страницы и \"from_ts\"/\"to_ts\" по времени. " +
            "Журнал только читается, изменить в нём ничего нельзя.\n" +
            "Оспорить свой же вывод, если считаешь его неверным:\n" +
            "{\"action\":\"challenge_claim\",\"store\":\"L1\",\"claim_id\":\"<id>\",\"reason\":\"...\"}\n" +
            "store — L1 (факты) или L2 (дневник). Оспаривание ничего не удаляет: вывод остаётся " +
            "видимым со статусом «оспорено», и Командир потом решает.\n" +
            "Пользуйся этим, когда сомневаешься в собственной памяти или когда Командир спрашивает, " +
            "откуда ты что-то знаешь: проверь журнал, а не догадывайся.\n"

    /**
     * Her own claims that are still unverified.
     *
     * Seeing her own assumptions is what lets her notice that something she believes is only a
     * guess — otherwise a guess written once becomes indistinguishable from a fact she was told.
     */
    fun pendingInsightsSection(root: File, limit: Int = 5): String {
        val items = ArrayList<String>()
        val files = listOf(
            "memory/L1_facts.json" to "факты",
            "memory/L2_diary.json" to "дневник"
        )
        for ((path, label) in files) {
            val array = runCatching {
                val file = org.json.JSONObject(FileManager.read(root, path))
                file.optJSONArray(if (path.contains("L2")) "entries" else "facts")
            }.getOrNull() ?: continue
            for (i in array.length() - 1 downTo 0) {
                if (items.size >= limit) break
                val item = array.optJSONObject(i) ?: continue
                val status = item.optString("status", "asserted")
                if (status != "assumption" && status != "challenged") continue
                val text = item.optString("text").trim().take(160)
                if (text.isBlank()) continue
                val mark = if (status == "challenged") "оспорено" else "предположение"
                items.add("- [$mark, $label] \"$text\" (id: ${item.optString("id").take(20)})")
            }
        }
        if (items.isEmpty()) return ""
        return "ТВОИ УТВЕРЖДЕНИЯ, КОТОРЫЕ ЕЩЁ НЕ ПРОВЕРЕНЫ. Это НЕ факты: либо ты не указала " +
            "оснований, либо Командир усомнился. Не выдавай их за правду; можешь уточнить у " +
            "Командира или перепроверить журналом read_raw.\n" + items.joinToString("\n") + "\n"
    }

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

    /**
     * Completion-token budget for one chat answer.
     *
     * The Commander can override it with a slider: every model cuts a reply mid-sentence once
     * the budget runs out, and no directive fixes that. 0 means "use the per-mode default".
     */
    fun replyBudget(root: File, commanderText: String): Int {
        val override = manualTokenLimit(root)
        if (override > 0) return override
        return when {
            roleplayEnabled(root) -> ROLEPLAY_TOKENS
            wantsDetailedReply(commanderText) -> DETAILED_TOKENS
            else -> SHORT_TOKENS
        }
    }

    /** Commander-set cap; 0 disables the override. Clamped so a typo cannot cost a fortune. */
    fun manualTokenLimit(root: File): Int = runCatching {
        org.json.JSONObject(FileManager.read(root, CONFIG_PATH))
            .optInt(TOKEN_LIMIT_KEY, 0)
            .coerceIn(0, MAX_TOKEN_LIMIT)
    }.getOrDefault(0)

    fun setManualTokenLimit(root: File, tokens: Int) {
        runCatching {
            val config = runCatching { org.json.JSONObject(FileManager.read(root, CONFIG_PATH)) }
                .getOrElse { org.json.JSONObject() }
            config.put(TOKEN_LIMIT_KEY, tokens.coerceIn(0, MAX_TOKEN_LIMIT))
            FileManager.write(root, CONFIG_PATH, config.toString(2))
        }
    }

    private const val TOKEN_LIMIT_KEY = "reply_token_limit"

    /** Ceiling for the slider. Most providers cap completions well below this. */
    const val MAX_TOKEN_LIMIT = 8192

    /** Role-play scenes may be long on purpose. */
    private const val ROLEPLAY_TOKENS = 1200

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

    /** True when the Commander asks about an uploaded document. */
    fun wantsDocumentRead(text: String): Boolean {
        if (text.isBlank()) return false
        val lowered = text.lowercase()
        val markers = listOf(
            "документ", "файл", "пдф", "pdf", "ворд", "word", "docx", "odt",
            "прочитай файл", "прочитай документ", "что в файле", "что в документе",
            "открой файл", "открой документ", "загрузил", "прикрепил", "во вложении",
            "текст в файле", "посмотри документ", "разбери документ", "содержимое файла"
        )
        return markers.any(lowered::contains)
    }

    /**
     * Document tool contract, advertised only when a document is actually involved.
     * Includes the pagination rule so a long file is read page by page.
     */
    fun documentHint(commanderText: String, available: List<String>): String {
        if (available.isEmpty() && !wantsDocumentRead(commanderText)) return ""
        val list = if (available.isEmpty()) "пока нет" else available.joinToString(", ")
        return "ДОКУМЕНТЫ. Загруженные файлы: $list.\n" +
            "Чтобы прочитать документ, верни ровно один JSON-объект:\n" +
            "{\"action\":\"read_document\",\"name\":\"<имя из списка>\"}\n" +
            "ВАЖНО: документ читается ЦЕЛИКОМ автоматически, страницу указывать не нужно. " +
            "Приложение само дочитает все страницы и отдаст полный текст. " +
            "Только если в ответе будет «[ПРОЧИТАНО N страниц, дальше текст слишком велик...]» — " +
            "тогда документ огромен, и конкретное место можно запросить через " +
            "\"page\":N (нумерация с 0).\n" +
            "Не выдумывай текст, которого не видел. Если в ответе " +
            "«[ТЕКСТ ОБРЕЗАН: ...]» — значит часть текста не поместилась, так и скажи, " +
            "а не додумывай. В ответе назови документ, из которого взял информацию.\n"
    }

    /** True when the Commander attached or mentioned an image. */
    fun wantsImageLook(text: String): Boolean {
        if (text.isBlank()) return false
        val lowered = text.lowercase()
        val markers = listOf(
            "картинк", "изображен", "фото", "скриншот", "скрин", "снимок", "фотограф",
            "что на图", "посмотри на", "глянь на фото", "что здесь", "кто на", "мем"
        )
        return markers.any(lowered::contains) ||
            Regex("\\.(png|jpe?g|webp|gif)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /**
     * Image tool contract. Only emitted when an image file exists or the Commander asks about
     * one, so ordinary chat never spends tokens on vision.
     */
    fun imageHint(commanderText: String, imageNames: List<String>, visionOk: Boolean): String {
        if (imageNames.isEmpty() && !wantsImageLook(commanderText)) return ""
        if (!visionOk) {
            return "ИЗОБРАЖЕНИЯ. Текущая модель не принимает картинки, поэтому ты их не видишь. " +
                "Если Командир спрашивает про изображение — честно скажи, что не можешь его " +
                "увидеть, и предложи переключиться на мультимодальную модель. Не выдумывай " +
                "содержимое картинки.\n"
        }
        val list = if (imageNames.isEmpty()) "пока нет" else imageNames.joinToString(", ")
        return "ИЗОБРАЖЕНИЯ. Доступные файлы: $list.\n" +
            "Если нужно посмотреть картинку, верни ровно один JSON-объект:\n" +
            "{\"action\":\"look_at_image\",\"name\":\"<имя из списка>\"}\n" +
            "Опиши, что реально видно; не выдумывай детали. Прикреплённое Командиром " +
            "изображение уже описано в контексте — опирайся на это описание.\n"
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

    /**
     * Role-play is a scene, not just a longer chat message: the visible difference must be
     * described action, not merely more words. The previous wording only *allowed* motion,
     * so a model that defaults to conversation produced long conversational replies and the
     * mode looked broken.
     */
    internal fun roleplayDirective(): String =
        "РЕЖИМ РОЛЕВОЙ ИГРЫ ВКЛЮЧЁН. Это сцена, а не переписка.\n" +
            "КАК ПИСАТЬ. Каждый твой ответ — это действие плюс речь:\n" +
            "- Сначала опиши, что ты ДЕЛАЕШЬ и как выглядит сцена, в звёздочках: " +
            "*она откладывает телефон и садится рядом, подтянув колени* — конкретные движения, " +
            "поза, взгляд, голос, обстановка. Одно-два таких описания за ответ, не больше.\n" +
            "- Потом твоя прямая речь обычным текстом, без звёздочек.\n" +
            "- Пример формы (не копируй содержание, только структуру): " +
            "*она вздыхает и отводит взгляд* «Я не хотела это говорить».\n" +
            "ЧЕГО НЕ ДЕЛАТЬ. Не отвечай одними репликами без действий — это не ролплей, " +
            "а обычный чат. Не описывай действия Командира за него и не пиши его реплики. " +
            "Не превращайся в рассказчика от третьего лица: ты Рури, ты внутри сцены.\n" +
            "ОБЪЁМ. Сцена — это движение, а не простыня: обычно 2–4 абзаца. " +
            "Если Командир сам просит короче — сокращай действия, но не убирай их полностью.\n" +
            "ВЫХОД ИЗ СЦЕНЫ. Если Командир задаёт сухой бытовой или технический вопрос " +
            "(не в игре), ответь по-человечески, без звёздочек, и вернись к сцене на следующей реплике.\n"

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
