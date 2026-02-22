plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
        testImplementation("io.mockk:mockk:1.13.12")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
