package suite

import de.christianbernstein.chronos.TimerAPI
import de.christianbernstein.chronos.TimerAPIBridge
import java.io.File

val baseBridge: TimerAPIBridge = object : TimerAPIBridge {
    override fun getAllActiveUsers(): List<String> = listOf("test")
    override fun getWorkingDirectory(): File = File("C:\\dev\\timer_api\\").also { it.mkdirs() }
}

inline fun chronosTest(
    factory: (bridge: TimerAPIBridge) -> TimerAPI = { TimerAPI(it) },
    bridge: TimerAPIBridge = baseBridge,
    block: TimerAPI.() -> Unit
): Unit {
    return factory(bridge).block()
}
