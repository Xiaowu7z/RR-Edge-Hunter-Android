plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs("../app/src", "../test")
            kotlin.include(
                "com/xiaowu7z/cfipoptimizer/IpAddress.kt",
                "com/xiaowu7z/cfipoptimizer/CloudflareDns.kt",
                "CloudflareDnsTest.kt"
            )
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

val logicRuntimeClasspath = sourceSets["main"].runtimeClasspath

val cloudflareDnsTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies the only RR-specific feature: safe Cloudflare A/AAAA synchronization."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("CloudflareDnsTestKt")
}

tasks.named("check") {
    dependsOn(cloudflareDnsTest)
}
