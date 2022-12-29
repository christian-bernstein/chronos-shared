package de.christianbernstein.chronos

import java.io.File

interface ChronosAPIBridge {
    fun getAllActiveUsers(): List<String>
    fun getWorkingDirectory(): File
}
