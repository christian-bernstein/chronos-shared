package de.christianbernstein.chronos

import org.quartz.Job
import org.quartz.JobExecutionContext

class ReplenishJob: Job {

    override fun execute(context: JobExecutionContext?) {
        ChronosAPI.instance.replenish()
    }
}
