plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("com.google.protobuf") version "0.9.5" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
        testImplementation("io.mockk:mockk:1.13.12")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
