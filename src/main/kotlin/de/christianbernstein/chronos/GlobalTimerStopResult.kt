package de.christianbernstein.chronos

data class GlobalTimerStopResult(
    // session-key ~ exception
    val exceptions: Map<String, Exception>
)
