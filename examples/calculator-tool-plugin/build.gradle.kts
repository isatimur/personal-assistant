plugins {
    // For a standalone plugin outside this repo, also add:
    // kotlin("jvm") version "1.9.25"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    // For a standalone plugin outside this repo, replace with:
    // implementation("com.assistant:core:VERSION")
    implementation(project(":core"))
}

tasks.shadowJar {
    archiveBaseName.set("calculator-tool-plugin")
    archiveClassifier.set("")
    mergeServiceFiles()
}
