package de.christianbernstein.chronos

enum class UpdateResultCodes(val code: Int) {
    GENERIC_SUCCESS(0),
    LACK_OF_PERMISSION(40),
    INTERNAL_ERROR(50)
}
