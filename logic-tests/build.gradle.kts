plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)

    sourceSets {
        main {
            kotlin.srcDirs("../app/src", "../test")
            kotlin.include(
                "com/xiaowu7z/cfipoptimizer/IpSources.kt",
                "com/xiaowu7z/cfipoptimizer/IpSubscription.kt",
                "com/xiaowu7z/cfipoptimizer/engine/CfRanges.kt",
                "com/xiaowu7z/cfipoptimizer/engine/AuthorizedHost.kt",
                "com/xiaowu7z/cfipoptimizer/engine/DnsOverride.kt",
                "IpSourcesTest.kt",
                "AuthorizedHostTest.kt",
                "FixedDnsTest.kt"
            )
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

val logicRuntimeClasspath = sourceSets["main"].runtimeClasspath

val ipSourcesTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs deterministic IP-source and secure subscription checks."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("IpSourcesTestKt")
}

val authorizedHostTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs DNS-snapshot intersection safety checks."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("AuthorizedHostTestKt")
}

val fixedDnsTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies the test-host DNS override cannot resolve unrelated hosts."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("FixedDnsTestKt")
}

tasks.named("check") {
    dependsOn(ipSourcesTest, authorizedHostTest, fixedDnsTest)
}
