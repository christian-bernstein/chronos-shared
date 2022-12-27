package de.christianbernstein.chronos

class SessionMarkedAsExpiredEvent(user: User, val session: UserSession): UserEvent(user = user, eventID = "SessionMarkedAsExpiredEvent")
