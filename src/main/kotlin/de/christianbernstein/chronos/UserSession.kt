package de.christianbernstein.chronos

import java.time.Instant

data class UserSession(
    val id: String,
    val start: Instant,
    val estimatedTimeRemainingInSeconds: Long
)
