package com.ruri

import org.json.JSONArray
import java.io.File

/** Keeps provider selection strictly inside explicitly enabled free routes. */
internal object FreeModelRouter {
    fun orderedIndices(root: File, models: JSONArray, current: Int, task: Task): List<Int> =
        // Callers guard NetState.online before asking for candidates; tests and pure routing
        // selection must not depend on Android's process-wide connectivity singleton.
        RouteSelector.ordered(root = root, models = models, current = current, task = task, online = true)
}
