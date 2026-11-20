package com.ruri

import org.json.JSONArray
import java.io.File

/** Keeps provider selection strictly inside explicitly enabled free routes. */
internal object FreeModelRouter {
    fun orderedIndices(root: File, models: JSONArray, current: Int, task: Task): List<Int> =
        RouteSelector.ordered(root = root, models = models, current = current, task = task)
}
