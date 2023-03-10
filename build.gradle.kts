import org.jetbrains.kotlin.com.google.gson.Gson
import org.jetbrains.kotlin.com.google.gson.GsonBuilder
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue
import java.time.Instant

plugins {
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.7.20"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("java")
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    // https://mvnrepository.com/artifact/org.quartz-scheduler/quartz
    implementation("org.quartz-scheduler:quartz:2.3.2")

    // https://mvnrepository.com/artifact/org.slf4j/slf4j-simple
    testImplementation("org.slf4j:slf4j-simple:2.0.6")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}

/**
 * Update the version / build-result file
 */
tasks.create("incrementVersion") {
    group = "chronos"
    doLast {
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val fileName = "chronos.version.json"
        val chronosFile = File(projectDir, fileName)
        val creationTimestamp = Instant.now().epochSecond
        var buildNumber = 0
        // Read version file
        chronosFile.exists().ifTrue {
            val content = chronosFile.readText()
            val kvs = gson.fromJson(content, HashMap::class.java)
            buildNumber = (kvs["buildNumber"] as Double? ?: 0).toInt()
        }
        // Write version file
        chronosFile.writeText(gson.toJson(mapOf<String, Any>(
            "buildNumber" to buildNumber + 1,
            "creationTimestamp" to creationTimestamp
        )))
    }
}

/**
 * Execute "jar"-task after "incrementVersion"-task
 */
tasks.getByName("jar") {
    dependsOn(tasks.getByName("incrementVersion"))
}
