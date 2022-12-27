/*
 * Copyright (c) 2022.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import ActionMode.Companion.ifReaction
import io.ktor.util.date.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.collections.HashMap
import kotlin.io.path.readText
import kotlin.math.max
import kotlin.reflect.KClass

/*
 * Copyright (c) 2022.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

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

@Serializable
data class TimerAPIConfig(
    var maxTimeSlotHistory: Int = 3,
    var replenishMultipliers: Map<DayOfWeek, Double> = DayOfWeek.values().associateWith { 1e0 },
    var replenishBase: Double = 1e0,
    var replenishBaseUnit: TimeUnit = TimeUnit.HOURS,
    var replenishWeekendMultiplier: Double = 1e0
)

@Serializable
data class User(
    val id: String,
    var slotsInSeconds: List<Long> = emptyList(),
    var operator: Boolean = false
)

@Serializable
data class UserStorage(
    val users: List<User>
)

data class UserSession(
    val id: String,
    val start: Instant
)

open class UserEvent(
    val user: User,
    eventID: String
): Event(eventID)

class SessionMarkedAsExpiredEvent(user: User): UserEvent(user = user, eventID = "SessionMarkedAsExpiredEvent")

class SessionStoppedEvent(user: User): UserEvent(user = user, eventID = "SessionStoppedEvent")

enum class ActionMode { ACTION, REACTION;
    companion object {
        fun ifReaction(mode: ActionMode, action: Runnable) = if (mode == REACTION) action.run() else {}
    }
}

data class Contractor(
    val id: String,
    val log: (msg: Any, level: Level) -> Unit = { msg, _ -> println(msg) },
    val bypass: Boolean,
)

data class UpdateResult<T>(
    val data: T? = null,
    val success: Boolean = true,
    val code: Int = 0
)

interface TimerAPIBridge {
    fun getAllActiveUsers(): List<String>
    fun getWorkingDirectory(): File
}

@Suppress("RedundantVisibilityModifier", "MemberVisibilityCanBePrivate")
class TimerAPI(val bridge: TimerAPIBridge) {

    companion object {
        public val console: Contractor = Contractor("console", bypass = true)
    }

    private var sessions: HashMap<String, UserSession> = HashMap()

    private val sessionFutures: HashMap<String, ScheduledFuture<*>> = HashMap()

    private var cachedConfig: TimerAPIConfig? = null

    private val hermes: Hermes = with(Hermes(HermesConfig())) {
        registerBus("api")
    }

    private val json: Json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    init {
        this.loadConfigIntoCache()
    }

    private fun isWeekend(dayOfWeek: DayOfWeek): Boolean {
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
    }

    private fun replenish() = with(this.loadConfig()) {
        val maxHistoryLength = this.maxTimeSlotHistory
        val replenishAmountInSeconds = this@TimerAPI.getReplenishAmount().seconds

        this@TimerAPI.stopGlobalTimer(console)

        this@TimerAPI.updateUsers { users ->
            users.forEach { user ->
                user.slotsInSeconds + replenishAmountInSeconds
                val size = user.slotsInSeconds.size
                if (user.slotsInSeconds.size > maxHistoryLength) {
                    user.slotsInSeconds.toMutableList().subList(0, size - maxHistoryLength).clear()
                }
            }
            users
        }

        this@TimerAPI.startGlobalTimer(console)
    }

    fun getReplenishAmount(dayOfWeek: DayOfWeek = LocalDate.now().dayOfWeek): Duration {
        with(this.loadConfig(true)) {
            // Base time per day in
            var baseInSec: Double = this.replenishBaseUnit.toSeconds(this.replenishBase.toLong()).toDouble()
            // Multiplier per day
            baseInSec *= requireNotNull(this.replenishMultipliers[dayOfWeek])
            // Special multiplier for weekends
            if (this@TimerAPI.isWeekend(dayOfWeek)) baseInSec *= requireNotNull(this.replenishWeekendMultiplier)
            return Duration.of(baseInSec.toLong(), ChronoUnit.SECONDS)
        }
    }

    fun getDefaultConfig(): TimerAPIConfig = TimerAPIConfig()

    fun getFile(localPath: String): File = File("${this.bridge.getWorkingDirectory().path}\\$localPath")

    fun getConfigFile(): File = this.getFile("config.json")

    fun getUserStorageFile(): File = this.getFile("users.json")

    private fun createUserForID(id: String): User = User(id, slotsInSeconds = listOf(this.getReplenishAmount().toSeconds()))

    fun createUser(id: String) {
        // Check if a user with the given id already exists
        if (this.getUserFromID(id) != null) return
        // User doesn't exist :: Create a new user entry in the database
        this.updateUsers {
            it + this.createUserForID(id)
        }
    }

    fun updateUsers(updater: (users: List<User>) -> List<User>) = this.overwriteUsers(updater(this.loadUsers()))

    fun loadUsers(): List<User> = with(this.getUserStorageFile()) {
        if (this.exists().not()) {
            return@with this@TimerAPI.overwriteUsers()
        }
        try {
            this@TimerAPI.json.decodeFromString(UserStorage.serializer(), this.toPath().readText(Charsets.UTF_8)).users
        } catch (e: Exception) {
            e.printStackTrace()
            this@TimerAPI.overwriteUsers()
        }
    }

    fun overwriteUsers(users: List<User> = emptyList()): List<User> = with(this.getUserStorageFile()) {
        this.createNewFile()
        this.writeText(this@TimerAPI.json.encodeToString(UserStorage.serializer(), UserStorage(
            users = users
        )), Charsets.UTF_8)
        users
    }

    fun overwriteConfig(config: TimerAPIConfig = this.getDefaultConfig()): TimerAPIConfig = with(this.getConfigFile()) {
        this.createNewFile()
        this.writeText(this@TimerAPI.json.encodeToString(TimerAPIConfig.serializer(), config), Charsets.UTF_8)
        config
    }

    fun loadConfigIntoCache() = this.loadConfig(useCachedVersionIfPossible = false).also {
        this.cachedConfig = it
    }

    fun updateConfig(updater: (config: TimerAPIConfig) -> TimerAPIConfig) = overwriteConfig(updater(loadConfig()))

    fun loadConfig(useCachedVersionIfPossible: Boolean = true): TimerAPIConfig = with(this.getConfigFile()) {
        with(this@TimerAPI.cachedConfig) cache@ {
            if (useCachedVersionIfPossible && (this != null)) {
                return@cache this
            } else {
                return@cache null
            }
        }.also {
            if (it != null) {
                return@with it
            }
        }

        if (this.exists().not()) {

            println("Create new config")

            return@with this@TimerAPI.overwriteConfig()
        }

        try {


            println("Try to load config from file")

            this@TimerAPI.json.decodeFromString(TimerAPIConfig.serializer(), this.toPath().readText(Charsets.UTF_8))
        } catch (e: Exception) {
            e.printStackTrace()
            this@TimerAPI.overwriteConfig()
        }
    }

    fun arePermissionsGranted(contractor: Contractor, vararg permissions: String): Boolean {
        if (contractor.bypass) return true

        // TODO: Implement
        return false
    }

    fun <T> update(
        contractor: Contractor,
        permissions: Array<String> = emptyArray(),
        logic: () -> T
    ): UpdateResult<T?> {
        if (this.arePermissionsGranted(contractor, *permissions).not()) {
            return UpdateResult(code = 40)
        }
        return UpdateResult(logic())
    }

    fun stopGlobalTimer(contractor: Contractor) = this.update(contractor, arrayOf("stop_global_timer")) {
        println("stopGlobalTimer")

        this.sessions.forEach { session ->
            val id = session.key
            this.executeSessionStop(id, ActionMode.ACTION)
        }
    }

    fun startGlobalTimer(contractor: Contractor) = this.update(contractor, arrayOf("start_global_timer")) {
        println("startGlobalTimer")

        this.bridge.getAllActiveUsers().forEach { user ->
            this.executeSessionStart(user)
        }
    }

    fun requestJoin(id: String, onJoinPermitted: Runnable, onJoinRefused: Runnable) = with(getUserFromID(id)) {
        if (this == null) {
            onJoinRefused.run()
            return
        }
        if (checkIfUserCanJoin(this)) {
            onJoinPermitted.run()
        } else{
            onJoinRefused.run()
        }
    }

    public fun resumeTimerFor(id: String) {
        this.executeSessionStart(id)
    }

    public fun pauseTimerFor(id: String) {
        this.executeSessionStop(id, ActionMode.ACTION)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    public fun executeSessionStart(id: String) = with(requireNotNull(getUserFromID(id))) {
        println("executeSessionStart for '$id'")

        this@TimerAPI.sessions[id] = UserSession(
            id = id,
            start = Instant.now()
        )
        val timeLeft: Long = this@TimerAPI.calculateTimeLeft(this)
        this@TimerAPI.sessionFutures[id] = Executors.newSingleThreadScheduledExecutor().schedule({
            this@TimerAPI.executeSessionStop(id, ActionMode.REACTION)
        }, timeLeft, TimeUnit.SECONDS)
    }

    private fun stopTimerFor(id: String) = with(requireNotNull(sessionFutures.remove(id))) {
        this.cancel(false)
    }

    public fun executeSessionStop(id: String, actionMode: ActionMode = ActionMode.ACTION) = with(requireNotNull(this.sessions.remove(id))) {
        println("executeSessionStop for '$id'")

        this@TimerAPI.stopTimerFor(id)
        updateUser(id) {
            it.slotsInSeconds = this@TimerAPI.reduceTimeSlots(
                it.slotsInSeconds.toTypedArray(),
                Duration.between(this.start, Instant.now()).seconds
            ).toList()
            it
        }
    }.also {
        ifReaction(actionMode) {
            this@TimerAPI.apiBus() fire SessionMarkedAsExpiredEvent(this@TimerAPI.getUserFromID(id)!!)
        }
    }

    private fun checkIfUserCanJoin(user: User): Boolean = calculateTimeLeft(user) > 0

    private fun calculateTimeLeft(user: User): Long = user.slotsInSeconds.sum()

    @Suppress("MemberVisibilityCanBePrivate")
    public fun apiBus(): EventBus = requireNotNull(this.hermes.bus("api"))

    private fun updateUser(id: String, updater: (user: User) -> User) {
        this.updateUsers {  users ->
            var user = requireNotNull(users.find { it.id == id })
            user = updater(user)
            users.filter { it.id != id } + user
        }
    }

    fun reduceTimeSlots(slots: Array<Long>, by: Long, onBleed: ((amount: Long) -> Unit)? = null): Array<Long> {
        var tmpBy = by
        fun reduce(arr: Array<Long>) = arr.filter { it > 0L }.toTypedArray()
        slots.forEachIndexed { index, time ->
            val willReduceTmpBy = tmpBy - time
            val timeReducedByTmpTo = max(time - tmpBy, 0)
            slots[index] = timeReducedByTmpTo
            tmpBy = max(willReduceTmpBy, 0)
            if (tmpBy == 0L) return reduce(slots)
        }
        if (tmpBy > 0 && onBleed != null) onBleed(tmpBy)
        return reduce(slots)
    }

    @Suppress("RedundantNullableReturnType", "MemberVisibilityCanBePrivate")
    public fun getUserFromID(id: String): User? {
        return this.loadUsers().find { it.id == id }
    }
}

open class Event(open val id: String)

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

data class HermesConfig(
    val defaultBusFactory: (busID: String, hermes: Hermes) -> EventBus = { _, _ -> EventBus() }
)

interface IEventListener<T: Event> {
    fun handle(event: T)
}
