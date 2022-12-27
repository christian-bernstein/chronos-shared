@file:Suppress("SpellCheckingInspection", "unused")

package de.christianbernstein.chronos

import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

@Serializable
data class TimeSpan(
    val measurand: Long,
    val unit: TimeUnit
) {
    fun toSeconds(): Long = this.unit.toSeconds(this.measurand)
}
