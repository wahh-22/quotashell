package dev.wahh.quotashell

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.wahh.quotashell.core.JobExecutions
import dev.wahh.quotashell.core.RefreshPolicy
import dev.wahh.quotashell.core.shouldEnqueueWidgetRefresh

/** Network-constrained periodic and explicit widget refreshes share the guarded application path. */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val token = executions.start(params.jobId)
        (application as QuotaApplication).refresh { _ ->
            if (executions.complete(params.jobId, token)) {
                // Only this execution may finish/cancel the job or change its widget pending state.
                // A stopped run's late callback must leave any newer queued job untouched.
                jobFinished(params, false)
                if (params.jobId == WIDGET_JOB_ID) {
                    getSystemService(JobScheduler::class.java).cancel(WIDGET_JOB_ID)
                }
                QuotaWidget.updateAll(this)
            }
        }
        return executions.isRunning(params.jobId)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        executions.stop(params.jobId)
        if (params.jobId == WIDGET_JOB_ID) {
            getSystemService(JobScheduler::class.java).cancel(WIDGET_JOB_ID)
            QuotaWidget.updateAll(this)
            return false
        }
        return true
    }

    companion object {
        private const val PERIODIC_JOB_ID = 100
        private const val WIDGET_JOB_ID = 101
        private val executions = JobExecutions()

        fun widgetRefreshPending(context: Context): Boolean =
            executions.isRunning(WIDGET_JOB_ID) || context.getSystemService(JobScheduler::class.java).getPendingJob(WIDGET_JOB_ID) != null

        /** Receiver and JobService run on main, making pending-job check + schedule a serialized debounce. */
        fun requestWidgetRefresh(context: Context) {
            if (!shouldEnqueueWidgetRefresh(widgetRefreshPending(context))) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.schedule(
                JobInfo
                    .Builder(WIDGET_JOB_ID, ComponentName(context, RefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
            // Scheduler failure leaves no pending job, so never leaves a stuck Syncing indicator.
            QuotaWidget.updateAll(context)
        }

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
                scheduler.schedule(
                    JobInfo
                        .Builder(PERIODIC_JOB_ID, ComponentName(context, RefreshJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(RefreshPolicy.INTERVAL_MINUTES * 60 * 1000L)
                        .setPersisted(true)
                        .build(),
                )
            }
        }
    }
}
