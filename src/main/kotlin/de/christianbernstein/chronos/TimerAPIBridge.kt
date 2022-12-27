package de.christianbernstein.chronos

import java.io.File

interface TimerAPIBridge {
    fun getAllActiveUsers(): List<String>
    fun getWorkingDirectory(): File
}
