plugins { kotlin("plugin.serialization") }

repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":core"))
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("net.dv8tion:JDA:5.2.1")
    // Ktor embedded server (WebChat + WhatsApp webhook)
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    // Slack Bolt with Socket Mode (no public URL required)
    implementation("com.slack.api:bolt:1.44.1")
    implementation("com.slack.api:bolt-socket-mode:1.44.1")
    implementation("jakarta.websocket:jakarta.websocket-api:2.1.1")
    implementation("org.glassfish.tyrus.bundles:tyrus-standalone-client:2.1.5")
}
