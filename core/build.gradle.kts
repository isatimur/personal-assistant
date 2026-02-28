import com.google.protobuf.gradle.id

plugins {
    kotlin("plugin.serialization")
    id("com.google.protobuf")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.cronutils:cron-utils:9.2.1")
    // gRPC
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-netty-shaded:1.63.0")
    implementation("io.grpc:grpc-stub:1.63.0")
    implementation("io.grpc:grpc-protobuf:1.63.0")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.3")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation("io.grpc:grpc-testing:1.63.0")
    // grpc-inprocess is in implementation (not testImplementation) because
    // AgentGrpcServer.startInProcess() — a test seam — uses InProcessServerBuilder.
    implementation("io.grpc:grpc-inprocess:1.63.0")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.63.0" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc") {}
                id("grpckt") {}
            }
            task.builtins {
                id("kotlin") {}
            }
        }
    }
}
