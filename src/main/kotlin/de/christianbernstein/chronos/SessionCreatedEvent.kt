package de.christianbernstein.chronos

class SessionCreatedEvent(user: User, val session: UserSession, val availableSessionTimeInSec: Long): UserEvent(user = user, eventID = "SessionCreatedEvent")
