package de.christianbernstein.chronos

class SessionLeftoverTimeThresholdReachedEvent(user: User, val session: UserSession, val threshold: TimeSpan): UserEvent(user = user, eventID = "SessionLeftoverTimeThresholdReachedEvent")
