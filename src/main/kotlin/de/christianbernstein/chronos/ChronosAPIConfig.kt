package de.christianbernstein.chronos

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.util.concurrent.TimeUnit

@Serializable
data class ChronosAPIConfig(
    var maxTimeSlotHistory: Int = 3,
    var replenishMultipliers: Map<DayOfWeek, Double> = DayOfWeek.values().associateWith { 1e0 },
    var replenishBase: Double = 1e0,
    var replenishBaseUnit: TimeUnit = TimeUnit.HOURS,
    var replenishWeekendMultiplier: Double = 1e0,
    var leftoverNotificationThresholds: Set<TimeSpan> = setOf(
        TimeSpan(5, TimeUnit.MINUTES),
        TimeSpan(1, TimeUnit.MINUTES),
        TimeSpan(30, TimeUnit.SECONDS),
        *(10L downTo 1).map { TimeSpan(it, TimeUnit.SECONDS) }.toTypedArray()
    )
)
