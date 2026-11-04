// Root build file: only declares plugin versions, nothing is applied here.
// Keep the Kotlin and Compose-plugin versions in lockstep — they share the
// same compiler artefact since Kotlin 2.0.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
