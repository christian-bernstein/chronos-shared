package de.christianbernstein.chronos

import java.io.File

interface ChronosAPIBridge {
    fun getAllActiveUsers(): List<String>
    fun getWorkingDirectory(): File

    fun onSessionMarkedAsExpired(event: SessionMarkedAsExpiredEvent) = run { }
    fun onSessionTimeThresholdReached(event: SessionLeftoverTimeThresholdReachedEvent) = run { }
}
