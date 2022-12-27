package de.christianbernstein.chronos

import kotlinx.serialization.Serializable

@Serializable
data class UserStorage(
    val users: List<User>
)
