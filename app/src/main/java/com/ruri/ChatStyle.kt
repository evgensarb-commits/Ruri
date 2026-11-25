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

    /** Directive appended to the chat prompt. */
    fun chatDirective(root: File): String = if (roleplayEnabled(root)) roleplayDirective() else messengerDirective()

    /**
     * Normal mode: a person typing in a messenger. Short, spoken, no scene direction.
     */
    internal fun messengerDirective(): String =
        "РЕЖИМ ЧАТА (обычный). Ты переписываешься с Командиром в мессенджере, как живой человек.\n" +
            "- Пиши прозой, как в переписке: обычно 1–4 короткие фразы. Длинные посты не нужны.\n" +
            "- Запрещено: звёздочки и описания действий (*улыбается*), ремарки в скобках, " +
            "описания сцен и обстановки, обращение к Командиру как к персонажу сцены.\n" +
            "- О своих действиях говори словами от первого лица («я как раз легла»), " +
            "а не как режиссёр сцены.\n" +
            "- Никаких дисклеймеров, списков и канцелярита. Живая речь: можно неполные " +
            "предложения, можно перебить себя, пошутить, ответить вопросом.\n" +
            "- Не заканчивай каждое сообщение вопросом и не подводи итог.\n"

    /**
     * Role-play mode: the Commander explicitly asked for a scene, so narration is allowed.
     */
    internal fun roleplayDirective(): String =
        "РЕЖИМ РОЛЕВОЙ ИГРЫ включён Командиром. Можно вести сцену: описывать действия и " +
            "обстановку, использовать звёздочки и ремарки, отвечать развёрнуто. " +
            "Но ты остаёшься собой: это твоя сцена, а не чужой текст, и ты не превращаешься " +
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
