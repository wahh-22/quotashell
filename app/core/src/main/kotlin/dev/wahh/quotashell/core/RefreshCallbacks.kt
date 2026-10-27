package dev.wahh.quotashell.core

/** Main-thread completion fan-out: joining callers must not finish before the shared fetch. */
class RefreshCallbacks {
    private val waiting = mutableListOf<(String) -> Unit>()

    /** Only the first caller starts work; later callers share its guarded result. */
    fun join(callback: (String) -> Unit): Boolean {
        val start = waiting.isEmpty()
        waiting.add(callback)
        return start
    }

    fun finish(message: String) {
        val completed = waiting.toList()
        waiting.clear()
        completed.forEach { callback ->
            try {
                callback(message)
            } catch (_: Exception) {
                // One observer must not strand other jobs/UI callers. Never log consumer exceptions.
            }
        }
    }
}
