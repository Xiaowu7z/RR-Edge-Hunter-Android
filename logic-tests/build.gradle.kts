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
                "com/xiaowu7z/cfipoptimizer/CloudflareDns.kt",
                "com/xiaowu7z/cfipoptimizer/engine/CfRanges.kt",
                "com/xiaowu7z/cfipoptimizer/engine/AuthorizedHost.kt",
                "com/xiaowu7z/cfipoptimizer/engine/CandidatePool.kt",
                "com/xiaowu7z/cfipoptimizer/engine/DnsOverride.kt",
                "com/xiaowu7z/cfipoptimizer/engine/TimingListener.kt",
                "com/xiaowu7z/cfipoptimizer/engine/ProbeEngine.kt",
                "com/xiaowu7z/cfipoptimizer/engine/IpPipeline.kt",
                "IpSourcesTest.kt",
                "AuthorizedHostTest.kt",
                "CandidatePoolTest.kt",
                "IpPipelineRankTest.kt",
                "FixedDnsTest.kt",
                "CloudflareDnsTest.kt"
            )
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
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

val candidatePoolTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies bounded Cloudflare candidate sampling for direct-IP mode."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("CandidatePoolTestKt")
}

val ipPipelineRankTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies video-stability ranking is never overridden by POP labels."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("IpPipelineRankTestKt")
}

val cloudflareDnsTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies safe two-phase Cloudflare A/AAAA synchronization."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("CloudflareDnsTestKt")
}

tasks.named("check") {
    dependsOn(
        ipSourcesTest,
        authorizedHostTest,
        candidatePoolTest,
        ipPipelineRankTest,
        fixedDnsTest,
        cloudflareDnsTest
    )
}
