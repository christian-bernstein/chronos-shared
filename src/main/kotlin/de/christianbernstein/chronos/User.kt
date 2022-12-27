package de.christianbernstein.chronos

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    var slotsInSeconds: List<Long> = emptyList(),
    var operator: Boolean = false
)
