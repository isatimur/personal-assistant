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
}
