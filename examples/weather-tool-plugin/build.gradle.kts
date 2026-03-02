plugins {
    // For a standalone plugin outside this repo, also add:
    // kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "8.3.6"
}

dependencies {
    // For a standalone plugin outside this repo, replace with:
    // implementation("com.assistant:core:VERSION")
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.shadowJar {
    archiveBaseName.set("weather-tool-plugin")
    archiveClassifier.set("")
    mergeServiceFiles()
}
