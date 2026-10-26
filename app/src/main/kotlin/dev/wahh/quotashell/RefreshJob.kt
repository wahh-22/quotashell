package dev.wahh.quotashell

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.wahh.quotashell.core.RefreshPolicy

/** Native periodic job; Android may delay it for Doze/battery/network policy. */
class RefreshJob : JobService() {
    private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        active = params
        (application as QuotaApplication).refresh {
            if (active === params) {
                active = null
                jobFinished(params, false)
            }
        }
        return active != null
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active = null
        return true
    }

    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(100) == null) {
                scheduler.schedule(
                    JobInfo
                        .Builder(100, ComponentName(context, RefreshJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(RefreshPolicy.INTERVAL_MINUTES * 60 * 1000L)
                        .setPersisted(true)
                        .build(),
                )
            }
        }
    }
}
