package com.ruri

import org.json.JSONArray
import java.io.File

/** Keeps provider selection strictly inside explicitly enabled free routes. */
internal object FreeModelRouter {
    fun orderedIndices(
        models: JSONArray,
        primaryIndex: Int
    ): List<Int> {
        val eligible = (0 until models.length()).filter { index ->
            val model = models.optJSONObject(index)
            model != null &&
                model.optBoolean("free_tier", false) &&
                model.optBoolean("enabled", true)
        }
        return eligible.sortedWith(
            compareBy<Int> { if (it == primaryIndex) 0 else 1 }
                .thenBy { models.optJSONObject(it)?.optInt("priority", 100) ?: 100 }
        )
    }

    fun orderedIndices(
        root: File,
        models: JSONArray,
        primaryIndex: Int,
        task: Task
    ): List<Int> = RouteSelector.orderedIndices(
        root = root,
        models = models,
        current = primaryIndex,
        task = task
    )
}
