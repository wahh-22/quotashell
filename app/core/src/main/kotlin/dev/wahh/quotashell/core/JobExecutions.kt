package dev.wahh.quotashell.core

/** Main-thread execution identities, independent of platform callback parameter objects. */
class JobExecutions {
    private var nextToken = 0L
    private val active = mutableMapOf<Int, Long>()

    fun start(jobId: Int): Long {
        val token = ++nextToken
        active[jobId] = token
        return token
    }

    /** Stop callbacks may carry a different parameter object; only the stable job id matters. */
    fun stop(jobId: Int) {
        active.remove(jobId)
    }

    fun isRunning(jobId: Int): Boolean = jobId in active

    /** Claims completion once. A stopped or superseded execution has no scheduler/UI authority. */
    fun complete(
        jobId: Int,
        token: Long,
    ): Boolean {
        if (active[jobId] != token) return false
        active.remove(jobId)
        return true
    }
}
