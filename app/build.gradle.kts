plugins { id("com.github.johnrengelman.shadow") version "8.1.1" }
dependencies {
    implementation(project(":core"))
    implementation(project(":channels"))
    implementation(project(":providers"))
    implementation(project(":tools"))
    implementation(project(":memory"))
    implementation("com.charleskorn.kaml:kaml:0.61.0")
}
tasks.shadowJar {
    archiveBaseName.set("assistant")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest { attributes["Main-Class"] = "com.assistant.MainKt" }
}
