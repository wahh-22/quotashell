package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test

class JobExecutionsTest {
    // Deliberately retains identity equality, like Android JobParameters.
    private class Parameters(
        val jobId: Int,
    )

    /** Mirrors the thin adapter's scheduler/UI effects without depending on Android. */
    private class Host {
        val executions = JobExecutions()
        val queued = mutableSetOf<Int>()
        val finished = mutableListOf<Int>()
        var syncing = false

        fun start(params: Parameters): () -> Unit {
            val token = executions.start(params.jobId)
            if (params.jobId == 101) syncing = true
            return {
                if (executions.complete(params.jobId, token)) {
                    finished.add(params.jobId)
                    if (params.jobId == 101) {
                        queued.remove(101)
                        syncing = false
                    }
                }
            }
        }

        fun stop(params: Parameters) {
            executions.stop(params.jobId)
            if (params.jobId == 101) {
                queued.remove(101)
                syncing = false
            }
        }

        fun queueWidget() {
            queued.add(101)
            syncing = true
        }
    }

    @Test fun distinctStartAndStopObjectsInvalidateTheSameJobId() {
        val host = Host()
        val start = Parameters(101)
        val stop = Parameters(101)
        assertNotSame(start, stop)
        val complete = host.start(start)
        host.stop(stop)
        assertFalse(host.executions.isRunning(101))
        complete()
        assertTrue(host.finished.isEmpty())
        assertFalse(host.syncing)
    }

    @Test fun staleWidgetCompletionCannotCancelQueuedReplacementOrClearItsSyncing() {
        val host = Host()
        val completeW1 = host.start(Parameters(101))
        host.stop(Parameters(101))
        host.queueWidget() // W2 is pending network; onStartJob has not run yet.
        completeW1()
        assertTrue(host.queued.contains(101))
        assertTrue(host.syncing)
        assertTrue(host.finished.isEmpty())
    }

    @Test fun periodicStopThenLateCompletionIsIgnored() {
        val host = Host()
        val complete = host.start(Parameters(100))
        host.stop(Parameters(100))
        complete()
        assertTrue(host.finished.isEmpty())
        assertFalse(host.executions.isRunning(100))
    }

    @Test fun normalCompletionFinishesOnceAndClearsWidgetSyncing() {
        val host = Host()
        host.queueWidget()
        val complete = host.start(Parameters(101))
        complete()
        complete()
        assertEquals(listOf(101), host.finished)
        assertFalse(host.syncing)
        assertFalse(host.queued.contains(101))
        assertFalse(host.executions.isRunning(101))
    }

    @Test fun newerStartInvalidatesOlderTokenWithoutAffectingOtherJobIds() {
        val jobs = JobExecutions()
        val periodic = jobs.start(100)
        val old = jobs.start(101)
        jobs.stop(101)
        val current = jobs.start(101)
        assertTrue(current > old)
        assertFalse(jobs.complete(101, old))
        assertTrue(jobs.isRunning(101))
        assertTrue(jobs.complete(100, periodic))
        assertTrue(jobs.complete(101, current))
    }

    @Test fun throwingRefreshCallbackDoesNotBlockLaterCallersOrNextRefresh() {
        val callbacks = RefreshCallbacks()
        val messages = mutableListOf<String>()
        callbacks.join { throw IllegalStateException("consumer failed") }
        callbacks.join { messages.add(it) }
        callbacks.finish("complete")
        assertEquals(listOf("complete"), messages)
        assertTrue(callbacks.join { messages.add(it) })
        callbacks.finish("next")
        assertEquals(listOf("complete", "next"), messages)
    }
}
