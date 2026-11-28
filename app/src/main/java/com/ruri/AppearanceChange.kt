package com.ruri

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Appearance changes she proposes, and the Commander decides.
 *
 * She may not rewrite her own body directly: an unreviewed write would let one bad answer redefine
 * how she looks forever. Instead she files a proposal with a reason and the events behind it, and
 * nothing touches `core/appearance.json` until the Commander approves. Approval writes the field
 * and appends to the appearance history, so any change can be traced back to why it happened.
 *
 * The status vocabulary matches [Provenance] on purpose: pending → approved / rejected, and a
 * rejected proposal is never silently revived by asking again.
 */
object AppearanceChange {

    private const val PATH = "memory/appearance_proposals.json"
    private const val APPEARANCE_PATH = "core/appearance.json"

    /** How many undecided proposals may wait at once. More than this is nagging, not living. */
    const val MAX_PENDING = 5

    /** Bounds on what she may ask for, so a runaway answer cannot store a novel. */
    private const val MAX_VALUE_CHARS = 400
    private const val MAX_REASON_CHARS = 300

    const val STATUS_PENDING = "pending"
    const val STATUS_APPROVED = "approved"
    const val STATUS_REJECTED = "rejected"

    data class Proposal(
        val id: String,
        val field: String,
        val label: String,
        val value: String,
        val reason: String,
        val origin: String,
        val previous: String
    )

    /** Nouns she may change, taken from the single canonical list in [ChatStyle]. */
    fun fields(): List<Pair<String, String>> = ChatStyle.appearanceFields()

    private fun labelFor(field: String): String =
        fields().firstOrNull { it.first == field }?.second ?: field

    private fun read(root: File): JSONObject =
        runCatching { JSONObject(FileManager.read(root, PATH)) }.getOrNull() ?: JSONObject()

    private fun entries(root: File): JSONArray =
        read(root).optJSONArray("entries") ?: JSONArray()

    private fun write(root: File, entries: JSONArray) {
        runCatching {
            val file = read(root)
            file.put("entries", entries)
            file.put("updated_at", System.currentTimeMillis())
            FileManager.write(root, PATH, file.toString(2))
        }
    }

    private fun currentValue(root: File, field: String): String =
        runCatching { JSONObject(FileManager.read(root, APPEARANCE_PATH)).optString(field) }
            .getOrNull().orEmpty().trim()

    /**
     * Files a proposal.
     *
     * @return a message for her, explaining what happened in plain words.
     */
    fun propose(
        root: File,
        field: String,
        value: String,
        reason: String,
        origin: String,
        sourceIds: Collection<String> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): String {
        val key = field.trim().lowercase()
        val known = fields().firstOrNull { it.first == key }
            ?: return "Такого поля внешности нет. Доступны: " +
                fields().joinToString(", ") { it.first } + "."

        val text = SecretRedactor.redactText(value.trim()).take(MAX_VALUE_CHARS)
        if (text.length < 2) return "Изменение не отправлено: пустое описание."
        if (text.contains(SecretRedactor.PLACEHOLDER)) return "Изменение не отправлено: в тексте секрет."

        val why = SecretRedactor.redactText(reason.trim()).take(MAX_REASON_CHARS)
        val entries = entries(root)

        // Already decided the same way: do not ask again.
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("field") != key) continue
            if (item.optString("value").trim() != text) continue
            when (item.optString("status")) {
                STATUS_PENDING -> return "Это изменение уже ждёт решения Командира."
                STATUS_REJECTED -> return "Командир это уже отклонил; повторно не предлагаю."
                STATUS_APPROVED -> return "Это уже твоя внешность — менять нечего."
            }
        }

        if (currentValue(root, key) == text) {
            return "Ты уже так выглядишь, менять нечего."
        }

        val pending = (0 until entries.length()).count {
            entries.optJSONObject(it)?.optString("status") == STATUS_PENDING
        }
        if (pending >= MAX_PENDING) {
            return "Слишком много нерешённых предложений ($pending). Дождись ответа Командира."
        }

        val verified = Provenance.verifiedSources(root, sourceIds)
        val id = "app-" + UUID.randomUUID().toString()
        entries.put(JSONObject()
            .put("id", id)
            .put("ts", now)
            .put("field", key)
            .put("label", known.second)
            .put("value", text)
            .put("previous", currentValue(root, key))
            .put("reason", why)
            .put("origin", origin)
            .put("source_event_ids", JSONArray(verified))
            .put("status", STATUS_PENDING))
        write(root, entries)

        runCatching {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "appearance_proposed")
                .put("proposal_id", id)
                .put("field", key)
                .put("value", text)
                .put("origin", origin)
                .put("status", STATUS_PENDING))
        }
        return "Предложение отправлено Командиру: ${known.second}. Он решит, станет ли это твоей внешностью."
    }

    /** Undecided proposals, newest first. */
    fun pending(root: File): JSONArray {
        val out = JSONArray()
        val entries = entries(root)
        for (i in entries.length() - 1 downTo 0) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("status") != STATUS_PENDING) continue
            out.put(JSONObject(item.toString()))
        }
        return out
    }

    /** Everything ever proposed, newest first, for the history view. */
    fun all(root: File): JSONArray {
        val out = JSONArray()
        val entries = entries(root)
        for (i in entries.length() - 1 downTo 0) {
            entries.optJSONObject(i)?.let { out.put(JSONObject(it.toString())) }
        }
        return out
    }

    /**
     * Applies an approved proposal to her real appearance.
     *
     * The previous value goes into the appearance history, so an approved change can be reviewed
     * and reversed later instead of quietly becoming permanent.
     */
    fun approve(root: File, id: String, now: Long = System.currentTimeMillis()): Boolean {
        val entries = entries(root)
        var target: JSONObject? = null
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("id") != id) continue
            if (item.optString("status") != STATUS_PENDING) return false
            target = item
            break
        }
        val proposal = target ?: return false
        val field = proposal.optString("field")
        val value = proposal.optString("value")

        val appearance = runCatching { JSONObject(FileManager.read(root, APPEARANCE_PATH)) }
            .getOrElse { JSONObject() }
        val previous = appearance.optString(field).trim()
        appearance.put(field, value)
        appearance.put("updated_at", now)

        val history = appearance.optJSONArray("history") ?: JSONArray()
        history.put(JSONObject()
            .put("ts", now)
            .put("field", field)
            .put("from", previous)
            .put("to", value)
            .put("reason", proposal.optString("reason"))
            .put("origin", proposal.optString("origin"))
            .put("proposal_id", id)
            .put("source_event_ids", proposal.optJSONArray("source_event_ids") ?: JSONArray()))
        appearance.put("history", history)
        FileManager.write(root, APPEARANCE_PATH, appearance.toString(2))

        proposal.put("status", STATUS_APPROVED).put("decided_at", now)
        write(root, entries)
        runCatching {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "appearance_changed")
                .put("proposal_id", id)
                .put("field", field)
                .put("from", previous)
                .put("to", value)
                .put("status", STATUS_APPROVED))
        }
        return true
    }

    /** Refuses a proposal. It stays on record, so the same idea is not proposed again. */
    fun reject(root: File, id: String, reason: String = "", now: Long = System.currentTimeMillis()): Boolean {
        val entries = entries(root)
        var target: JSONObject? = null
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("id") != id) continue
            if (item.optString("status") != STATUS_PENDING) return false
            target = item
            break
        }
        val proposal = target ?: return false
        proposal.put("status", STATUS_REJECTED).put("decided_at", now)
        if (reason.isNotBlank()) {
            proposal.put("reject_reason", SecretRedactor.redactText(reason).take(MAX_REASON_CHARS))
        }
        write(root, entries)
        runCatching {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "appearance_rejected")
                .put("proposal_id", id)
                .put("field", proposal.optString("field"))
                .put("status", STATUS_REJECTED))
        }
        return true
    }

    /** Undoes an applied change, putting the previous value back. */
    fun revert(root: File, proposalId: String, now: Long = System.currentTimeMillis()): Boolean {
        val entries = entries(root)
        var target: JSONObject? = null
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("id") != proposalId) continue
            if (item.optString("status") != STATUS_APPROVED) return false
            target = item
            break
        }
        val proposal = target ?: return false
        val field = proposal.optString("field")
        val previous = proposal.optString("previous")

        val appearance = runCatching { JSONObject(FileManager.read(root, APPEARANCE_PATH)) }
            .getOrElse { JSONObject() }
        appearance.put(field, previous)
        appearance.put("updated_at", now)
        val history = appearance.optJSONArray("history") ?: JSONArray()
        history.put(JSONObject()
            .put("ts", now)
            .put("field", field)
            .put("from", proposal.optString("value"))
            .put("to", previous)
            .put("reason", "откат Командиром")
            .put("proposal_id", proposalId))
        appearance.put("history", history)
        FileManager.write(root, APPEARANCE_PATH, appearance.toString(2))

        proposal.put("status", STATUS_REJECTED)
            .put("decided_at", now)
            .put("revert_reason", "откат Командиром")
        write(root, entries)
        runCatching {
            FileManager.appendEvent(root, JSONObject()
                .put("type", "appearance_changed")
                .put("proposal_id", proposalId)
                .put("field", field)
                .put("to", previous)
                .put("status", "reverted"))
        }
        return true
    }
}
