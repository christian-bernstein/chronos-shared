package de.christianbernstein.chronos

data class HermesConfig(
    val defaultBusFactory: (busID: String, hermes: Hermes) -> EventBus = { _, _ -> EventBus() }
)
