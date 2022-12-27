package de.christianbernstein.chronos

data class UpdateResult<T>(
    val data: T? = null,
    val success: Boolean = true,
    val code: Int = 0
)
