plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
    kotlin("plugin.serialization")
}
dependencies {
    implementation(project(":core"))
    implementation(project(":channels"))
    implementation(project(":providers"))
    implementation(project(":tools"))
    implementation(project(":memory"))
    implementation("com.charleskorn.kaml:kaml:0.55.0")
}
tasks.shadowJar {
    archiveBaseName.set("assistant")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest { attributes["Main-Class"] = "com.assistant.MainKt" }
}
