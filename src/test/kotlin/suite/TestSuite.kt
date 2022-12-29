package suite

import de.christianbernstein.chronos.ChronosAPI
import de.christianbernstein.chronos.ChronosAPIBridge
import de.christianbernstein.chronos.SessionCreatedEvent
import java.io.File

val baseBridge: ChronosAPIBridge = object : ChronosAPIBridge {
    override fun getAllActiveUsers(): List<String> = listOf("test")
    override fun getWorkingDirectory(): File = File("C:\\dev\\timer_api\\").also { it.mkdirs() }
}

inline fun chronosTest(
    factory: (bridge: ChronosAPIBridge) -> ChronosAPI = { ChronosAPI(it) },
    bridge: ChronosAPIBridge = baseBridge,
    block: ChronosAPI.() -> Unit
): Unit {
    return factory(bridge).block()
}
