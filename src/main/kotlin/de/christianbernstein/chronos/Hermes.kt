package de.christianbernstein.chronos

@Suppress("MemberVisibilityCanBePrivate")
class Hermes(val config: HermesConfig = HermesConfig()) {

    private val busses: MutableMap<String, EventBus> = mutableMapOf()

    fun registerBus(busID: String, bus: EventBus = this.config.defaultBusFactory(busID, this)): Hermes {
        this.busses[busID] = bus
        return this
    }

    fun hasBus(busID: String): Boolean {
        return this.busses.contains(busID)
    }

    fun bus(busID: String, busAction: ((bus: EventBus) -> Unit)? = null): EventBus? {
        this.busses[busID].also {
            it ?: return@bus null
            busAction?.invoke(it)
            return@bus it
        }
    }
}
