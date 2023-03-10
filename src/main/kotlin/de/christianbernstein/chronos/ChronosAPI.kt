package de.christianbernstein.chronos

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

import de.christianbernstein.chronos.ActionMode.Companion.ifReaction
import kotlinx.serialization.json.Json
import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.io.path.readText
import kotlin.math.max

@Suppress("RedundantVisibilityModifier", "MemberVisibilityCanBePrivate")
class ChronosAPI(var bridge: ChronosAPIBridge) {

    companion object {
        public val console: Contractor = Contractor("console", bypass = true)

        public lateinit var instance: ChronosAPI
    }

    private var sessions: HashMap<String, UserSession> = HashMap()

    private val sessionFutures: HashMap<String, ScheduledFuture<*>> = HashMap()

    private val sessionLeftoverTimeNotificationsExecutors: IDHashMap<ScheduledExecutorService> = HashMap()

    private val exclusionTable: IDHashMap<Boolean> = HashMap()

    private var cachedConfig: ChronosAPIConfig? = null

    private var scheduler: Scheduler = StdSchedulerFactory().also { it.initialize(
        // Configure the scheduler factory
        Properties().also { properties ->
            // The thread count has to be set manually in code or a config file
            properties.setProperty("org.quartz.threadPool.threadCount", 1.toString())
        }
    ) }.scheduler

    private val hermes: Hermes = with(Hermes(HermesConfig())) {
        registerBus("api")
    }

    private val json: Json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    private var isGlobalTimerActive: Boolean = true

    init {
        instance = this
        this.loadConfigIntoCache()
    }

    public fun start() {
        this.scheduler.start()
        this.startReplenishJob()
        // TODO: Implement more start logic
    }

    public fun shutdown() {
        this.scheduler.shutdown(false)

        this.sessionLeftoverTimeNotificationsExecutors.forEach { (_, u) -> u.shutdownNow() }
        this.sessionLeftoverTimeNotificationsExecutors.clear()

        this.sessionFutures.forEach { (_, u) -> u.cancel(false)}
        this.sessionFutures.clear()

        // TODO: Implement more shutdown logic
    }

    private fun isWeekend(dayOfWeek: DayOfWeek): Boolean {
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
    }

    public fun getTimeLeft(id: String): Duration {
        val session = this.sessions[id]
        if (session != null) {
            return Duration.between(Instant.now(), session.start.plusSeconds(session.estimatedTimeRemainingInSeconds))
        }
        return Duration.ofSeconds(requireNotNull(this.getUserFromID(id)).slotsInSeconds.sum())
    }

    private fun startLeftoverTimeNotifications(session: UserSession) = with(this.loadConfig(true)) {
        val estimatedExpirationTime: Instant = session.start.plusSeconds(session.estimatedTimeRemainingInSeconds)

        this@ChronosAPI.sessionLeftoverTimeNotificationsExecutors[session.id] = Executors.newSingleThreadScheduledExecutor().also { executor ->
            this.leftoverNotificationThresholds.forEach {
                val secondsTilNotification = Duration.between(Instant.now(), estimatedExpirationTime.minus(it.measurand, it.unit.toChronoUnit())).seconds
                if (secondsTilNotification < 0) return@forEach

                executor.schedule({
                    SessionLeftoverTimeThresholdReachedEvent(
                        user = requireNotNull(this@ChronosAPI.getUserFromID(session.id)),
                        session = session,
                        threshold = it
                    ).also { event ->
                        this@ChronosAPI.apiBus() fire event
                        this@ChronosAPI.bridge.onSessionTimeThresholdReached(event)
                    }
                }, secondsTilNotification, TimeUnit.SECONDS)
            }
        }
    }

    private fun stopLeftoverTimeNotifications(session: UserSession) = requireNotNull(this.sessionLeftoverTimeNotificationsExecutors.remove(session.id)).shutdownNow()

    private fun startReplenishJob() {
        this.scheduler.scheduleJob(
            JobBuilder.newJob(ReplenishJob::class.java).build(),
            TriggerBuilder.newTrigger().startNow().withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(0, 0)).build()
        )
    }

    // TODO: Convert to update()
    // TODO: Fire an event -> SessionTimeReplenishEvent
    fun replenish() = with(this.loadConfig()) {
        val maxHistoryLength = this.maxTimeSlotHistory
        val replenishAmountInSeconds = this@ChronosAPI.getReplenishmentAmount().seconds
        this@ChronosAPI.stopGlobalTimer(console)
        this@ChronosAPI.updateUsers { users ->
            users.forEach { user ->
                user.slotsInSeconds += replenishAmountInSeconds
                val size = user.slotsInSeconds.size
                if (user.slotsInSeconds.size > maxHistoryLength) {
                    val temp = user.slotsInSeconds.toMutableList()
                    temp.subList(0, size - maxHistoryLength).clear()
                    user.slotsInSeconds = temp
                }
            }
            users
        }
        this@ChronosAPI.startGlobalTimer(console)
    }

    fun getReplenishmentAmount(dayOfWeek: DayOfWeek = LocalDate.now().dayOfWeek): Duration = with(this.loadConfig(true)) {
        // Base time per day in
        var baseInSec: Double = this.replenishBaseUnit.toSeconds(this.replenishBase.toLong()).toDouble()
        // Multiplier per day
        baseInSec *= requireNotNull(this.replenishMultipliers[dayOfWeek])
        // Special multiplier for weekends
        if (this@ChronosAPI.isWeekend(dayOfWeek)) baseInSec *= requireNotNull(this.replenishWeekendMultiplier)
        return Duration.of(baseInSec.toLong(), ChronoUnit.SECONDS)
    }

    fun getDefaultConfig(): ChronosAPIConfig = ChronosAPIConfig()

    fun getFile(localPath: String): File = File("${this.bridge.getWorkingDirectory().path}\\$localPath")

    fun getConfigFile(): File = this.getFile("config.json")

    fun getUserStorageFile(): File = this.getFile("users.json")

    private fun createUserForID(id: String): User = User(id, slotsInSeconds = listOf(this.getReplenishmentAmount().toSeconds()), operator = false)

    fun createUser(id: String) {
        // Check if a user with the given id already exists
        if (this.getUserFromID(id) != null) return
        // User doesn't exist :: Create a new user entry in the database
        this.updateUsers {
            it + this.createUserForID(id)
        }
    }

    fun hasUserBeenRegistered(id: String) = this.getUserFromID(id) != null

    fun updateUsers(updater: (users: List<User>) -> List<User>) = this.overwriteUsers(updater(this.loadUsers()))

    fun loadUsers(): List<User> = with(this.getUserStorageFile()) {
        if (this.exists().not()) {
            return@with this@ChronosAPI.overwriteUsers()
        }
        try {
            this@ChronosAPI.json.decodeFromString(UserStorage.serializer(), this.toPath().readText(Charsets.UTF_8)).users
        } catch (e: Exception) {
            e.printStackTrace()
            this@ChronosAPI.overwriteUsers()
        }
    }

    fun overwriteUsers(users: List<User> = emptyList()): List<User> = with(this.getUserStorageFile()) {
        this.createNewFile()
        this.writeText(this@ChronosAPI.json.encodeToString(UserStorage.serializer(), UserStorage(
            users = users
        )), Charsets.UTF_8)
        users
    }

    fun overwriteConfig(config: ChronosAPIConfig = this.getDefaultConfig()): ChronosAPIConfig = with(this.getConfigFile()) {
        println("Overwriting config file '${this.path}'")

        // If at root directory, parent file (folder) will be null
        with(this.parentFile) parent@ {
            if (this == null) return@parent
            this.mkdirs();
        }

        this.createNewFile()

        this.writeText(this@ChronosAPI.json.encodeToString(ChronosAPIConfig.serializer(), config), Charsets.UTF_8)
        config
    }

    fun loadConfigIntoCache() = this.loadConfig(useCachedVersionIfPossible = false).also {
        this.cachedConfig = it
    }

    fun updateConfig(updater: (config: ChronosAPIConfig) -> ChronosAPIConfig) = overwriteConfig(updater(loadConfig()))

    fun loadConfig(useCachedVersionIfPossible: Boolean = true): ChronosAPIConfig = with(this.getConfigFile()) {
        with(this@ChronosAPI.cachedConfig) cache@ {
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
            return@with this@ChronosAPI.overwriteConfig()
        }
        try {
            this@ChronosAPI.json.decodeFromString(ChronosAPIConfig.serializer(), this.toPath().readText(Charsets.UTF_8))
        } catch (e: Exception) {
            e.printStackTrace()
            this@ChronosAPI.overwriteConfig()
        }
    }

    public fun getSlotsOf(id: String): List<Long> = this.getUserFromID(id)?.slotsInSeconds ?: listOf()

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
            return UpdateResult(code = UpdateResultCodes.LACK_OF_PERMISSION.code, success = false)
        }
        return try {
            UpdateResult(logic())
        } catch (exception: Exception) {
            UpdateResult(code = UpdateResultCodes.INTERNAL_ERROR.code, error = exception, success = false)
        }
    }

    /**
     *
     * TODO: Update TAT implementation -> Handle exceptions
     */
    fun stopGlobalTimer(contractor: Contractor): UpdateResult<GlobalTimerStopResult?> = this.update(contractor, arrayOf("stop_global_timer")) {
        this.isGlobalTimerActive = false
        val exceptions: MutableMap<String, Exception> = HashMap()
        this.sessions.forEach { session ->
            val id = session.key
            try {
                this.executeSessionStop(id, ActionMode.ACTION)
            } catch (exception: Exception) {
                exceptions += id to exception
            }
        }
        return@update GlobalTimerStopResult(
            exceptions = exceptions
        )
    }

    fun startGlobalTimer(contractor: Contractor) = this.update(contractor, arrayOf("start_global_timer")) {
        this.isGlobalTimerActive = true
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
        } else {
            onJoinRefused.run()
        }
    }

    public fun setExclusionFlag(id: String, flag: Boolean) {
        this.exclusionTable[id] = flag
    }

    public fun resumeTimerFor(contractor: Contractor, id: String) = update(contractor, arrayOf("resume_timer")) {
        this.setExclusionFlag(id, false)
        this.executeSessionStart(id)
    }

    public fun pauseTimerFor(contractor: Contractor, id: String) = update(contractor, arrayOf("pause_timer")) {
        this.setExclusionFlag(id, true)
        this.executeSessionStop(id, ActionMode.ACTION)
    }

    public fun isExcluded(id: String): Boolean = this.exclusionTable.getOrDefault(id, false)

    /**
     * @return TRUE: Session was started, FALSE: Session wasn't started
     */
    @Suppress("MemberVisibilityCanBePrivate")
    public fun executeSessionStart(id: String, overwriteExclusionFlag: Boolean = false): Boolean = with(requireNotNull(getUserFromID(id))) {
        // TODO: Check if session already present

        // Check if user marked as excluded :: Excluded users cannot start a session unless the overwrite flag is set
        if (this@ChronosAPI.isExcluded(id) && overwriteExclusionFlag.not()) {
            return@with false
        }

        // TODO: Check if still timeslots available
        val timeLeft: Long = this@ChronosAPI.calculateTimeLeft(this)
        val session = UserSession(
            id = id,
            start = Instant.now(),
            estimatedTimeRemainingInSeconds = timeLeft
        )
        this@ChronosAPI.sessions[id] = session
        this@ChronosAPI.sessionFutures[id] = Executors.newSingleThreadScheduledExecutor().schedule({
            this@ChronosAPI.executeSessionStop(id, ActionMode.REACTION)
        }, timeLeft, TimeUnit.SECONDS)

        this@ChronosAPI.startLeftoverTimeNotifications(session)

        // TODO: Move to bridge
        this@ChronosAPI.apiBus() fire SessionCreatedEvent(this@ChronosAPI.getUserFromID(id)!!, session, availableSessionTimeInSec = timeLeft)

        true
    }

    private fun stopTimerFor(id: String) = with(requireNotNull(sessionFutures.remove(id))) {
        this.cancel(false)
    }

    public fun executeSessionStop(id: String, actionMode: ActionMode = ActionMode.ACTION) {
        val session = this.sessions.remove(id) ?: return
        with(session) {
            this@ChronosAPI.stopTimerFor(id)
            updateUser(id) {
                it.slotsInSeconds = this@ChronosAPI.reduceTimeSlots(
                    it.slotsInSeconds.toTypedArray(),
                    Duration.between(this.start, Instant.now()).seconds
                ).toList()
                it
            }

            this@ChronosAPI.stopLeftoverTimeNotifications(this)

            ifReaction(actionMode) {
                SessionMarkedAsExpiredEvent(this@ChronosAPI.getUserFromID(id)!!, this).also { event ->
                    this@ChronosAPI.apiBus() fire event
                    this@ChronosAPI.bridge.onSessionMarkedAsExpired(event)
                }
            }
        }
    }

    private fun checkIfUserCanJoin(user: User): Boolean {
        // IF global timer not active, any user can join
        if (this.isGlobalTimerActive.not()) return true
        // Any operator can join at any time, even having no time left :: TODO: Not time == no session starting
        if (user.operator) return true
        // Default timer check
        return calculateTimeLeft(user) > 0
    }

    private fun calculateTimeLeft(user: User): Long = user.slotsInSeconds.sum()

    @Suppress("MemberVisibilityCanBePrivate")
    public fun apiBus(): EventBus = requireNotNull(this.hermes.bus("api"))

    public fun updateUser(id: String, updater: (user: User) -> User) {
        // If the requested user doesn't exist yet, create a user profile
        if (this.hasUserBeenRegistered(id).not()) createUser(id)
        // Update the stored user
        this.updateUsers { users ->
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

