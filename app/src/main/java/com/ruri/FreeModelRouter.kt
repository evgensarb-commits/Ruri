package com.ruri

import org.json.JSONArray

/** Keeps provider selection strictly inside explicitly enabled free routes. */
internal object FreeModelRouter {
    fun orderedIndices(
        models: JSONArray,
        primaryIndex: Int,
        requireNsfw: Boolean = false
    ): List<Int> {
        val eligible = (0 until models.length()).filter { index ->
            val model = models.optJSONObject(index)
            model != null &&
                model.optBoolean("free_tier", false) &&
                model.optBoolean("enabled", true) &&
                (!requireNsfw || (
                    model.optBoolean("nsfw_capable", false) &&
                        model.optLong("nsfw_tested_at", 0L) > 0L &&
                        model.optString("nsfw_probe_status") == "passed"
                    ))
        }
        return eligible.sortedWith(
            compareBy<Int> { if (it == primaryIndex) 0 else 1 }
                .thenBy { models.optJSONObject(it)?.optInt("priority", 100) ?: 100 }
        )
    }

    fun orderedIndices(
        root: java.io.File,
        models: JSONArray,
        primaryIndex: Int,
        requireNsfw: Boolean = false,
        task: Task = Task.CHAT
    ): List<Int> = RouteSelector.ordered(
        root = root,
        models = models,
        current = primaryIndex,
        task = task,
        needNsfw = requireNsfw
    )
}
