package de.christianbernstein.chronos

import java.util.logging.Level

data class Contractor(
    val id: String,
    val log: (msg: Any, level: Level) -> Unit = { msg, _ -> println(msg) },
    val bypass: Boolean,
)
