import de.christianbernstein.chronos.ActionMode
import de.christianbernstein.chronos.SessionCreatedEvent
import de.christianbernstein.chronos.SessionLeftoverTimeThresholdReachedEvent
import de.christianbernstein.chronos.SessionMarkedAsExpiredEvent
import suite.chronosTest
import java.time.Duration
import java.time.Instant
import java.util.Scanner

fun main() = chronosTest {

    apiBus() + { it: SessionMarkedAsExpiredEvent ->
        println("User ${it.user.id} :: Session marked as expired, Slots='${it.user.slotsInSeconds.joinToString()}', Session lasted ${Duration.between(it.session.start, Instant.now()).toSeconds()} seconds.")
    }

    apiBus() + { event: SessionCreatedEvent ->
        println("Session created :: Estimated time left: '${event.availableSessionTimeInSec} sec'")
    }

    apiBus() + { event: SessionLeftoverTimeThresholdReachedEvent ->
        println("@${event.id}, you have ${event.threshold.measurand} ${event.threshold.unit.toString().lowercase()} left")
    }

    start()

    updateUser("test") {
        it.slotsInSeconds = listOf(15)
        it
    }

    Scanner(System.`in`).also { scanner ->
        while (true) {
            scanner.nextLine().trim().also { cmd ->
                when (cmd) {
                    "leave" -> {
                        println("Closing session..")
                        executeSessionStop("test", ActionMode.ACTION)
                    }
                    "join" -> {
                        requestJoin("test", {
                            println("Free to join :: Starting session..")
                            executeSessionStart("test")
                        }, {
                            println("Cannot join, no playtime available")
                        })

                    }
                    "replenish" -> {
                        replenish()
                    }
                    "time" -> {
                        println("Estimated time left: '${getTimeLeft("test").seconds} sec'")
                    }
                    else -> {}
                }
            }
        }
    }
}
