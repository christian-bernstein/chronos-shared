package de.christianbernstein.chronos

interface IEventListener<T: Event> {
    fun handle(event: T)
}
