import de.christianbernstein.chronos.Contractor
import de.christianbernstein.chronos.SessionMarkedAsExpiredEvent
import de.christianbernstein.chronos.TimerAPI
import de.christianbernstein.chronos.TimerAPIBridge
import java.io.File



fun main() {
    fun main() = with(TimerAPI(object : TimerAPIBridge {
        override fun getAllActiveUsers(): List<String> = listOf("test")
        override fun getWorkingDirectory(): File = File("C:\\dev\\timer_api\\").also { it.mkdirs() }
    })) {

        createUser("test")

        apiBus() + { it: SessionMarkedAsExpiredEvent ->
            println("User ${it.user.id} :: Session marked as expired, Slots='${it.user.slotsInSeconds.joinToString()}'")
        }

        // reduceTimeSlots(
        //     slots = arrayOf(1, 3, 5),
        //     by = 0,
        //     onBleed = { println("Bleeding: $it unit") }
        // ).also { println(it.joinToString()) }

        this.requestJoin(id = "test", { println("User can join"); executeSessionStart("test") }, { error("User cannot join") })

        this.stopGlobalTimer(TimerAPI.console)

        this.startGlobalTimer(Contractor("no_rights", bypass = false))

        this.bridge.getWorkingDirectory()

        return@with
    }
}
