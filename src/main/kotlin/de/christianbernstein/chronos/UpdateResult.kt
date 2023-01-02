package de.christianbernstein.chronos

data class UpdateResult<T>(
    val data: T? = null,
    val success: Boolean = true,
    val code: Int = UpdateResultCodes.GENERIC_SUCCESS.code,
    val error: Throwable? = null
)
