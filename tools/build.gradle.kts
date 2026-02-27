plugins { kotlin("plugin.serialization") }

dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
}
