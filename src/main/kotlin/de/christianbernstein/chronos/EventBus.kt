package de.christianbernstein.chronos

import kotlin.reflect.KClass

class EventBus {

    val listeners: MutableMap<KClass<*>, MutableList<IEventListener<out Event>>> = mutableMapOf()

    inline infix fun <reified T : Event> register(listener: IEventListener<T>) {
        val eventClass = T::class
        val eventListeners: MutableList<IEventListener<out Event>> = listeners.getOrPut(eventClass) { mutableListOf() }
        eventListeners.add(listener)
    }

    inline infix fun <reified T: Event> fire(event: T) = listeners[event::class]
        ?.asSequence()
        ?.filterIsInstance<IEventListener<T>>()
        ?.forEach { it.handle(event) }

    fun fireUnknown(event: Event) = listeners[event::class]
        ?.asSequence()
        ?.filterIsInstance<IEventListener<Event>>()
        ?.forEach { it.handle(event) }

    inline operator fun <reified T : Event> plus(listener: IEventListener<T>) = this.register(listener)

    inline operator fun <reified T : Event> plus(crossinline listener: (event: T) -> Unit) = this.register(object :
        IEventListener<T> {
        override fun handle(event: T) = listener(event)
    })

    @JvmName("unknown_add")
    inline operator fun plus(crossinline listener: (event: Event) -> Unit) = this.register(object :
        IEventListener<Event> {
        override fun handle(event: Event) = listener(event)
    })
}
