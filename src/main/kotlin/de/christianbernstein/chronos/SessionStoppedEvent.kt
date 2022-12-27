package de.christianbernstein.chronos

class SessionStoppedEvent(user: User): UserEvent(user = user, eventID = "SessionStoppedEvent")
