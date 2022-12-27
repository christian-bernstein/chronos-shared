package de.christianbernstein.chronos

open class UserEvent(
    val user: User,
    eventID: String
): Event(eventID)
