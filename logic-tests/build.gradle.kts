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
                "com/xiaowu7z/cfipoptimizer/engine/MaintainedPool.kt",
                "com/xiaowu7z/cfipoptimizer/engine/ReferenceScanner.kt",
                "com/xiaowu7z/cfipoptimizer/engine/NodeRouteTemplate.kt",
                "com/xiaowu7z/cfipoptimizer/engine/XrayNodeConfig.kt",
                "com/xiaowu7z/cfipoptimizer/engine/XrayTemporaryConfigStore.kt",
                "IpSourcesTest.kt",
                "CfRangesCancellationTest.kt",
                "AuthorizedHostTest.kt",
                "CandidatePoolTest.kt",
                "IpPipelineRankTest.kt",
                "FastFunnelTest.kt",
                "SpeedWindowPolicyTest.kt",
                "FixedDnsTest.kt",
                "CloudflareDnsTest.kt",
                "ReferenceScannerTest.kt",
                "NodeRouteParserTest.kt",
                "XrayNodeConfigTest.kt",
                "XrayTemporaryConfigStoreTest.kt"
            )
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.json:json:20240303")
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

val cfRangesCancellationTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies Cloudflare range refresh cancels its active OkHttp call."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("CfRangesCancellationTestKt")
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

val fastFunnelTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies diverse shortlist selection, confirmation backfill, and cancellation."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("FastFunnelTestKt")
}

val speedWindowPolicyTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Rejects redirects and short error bodies and verifies independent speed clocks."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("SpeedWindowPolicyTestKt")
}

val cloudflareDnsTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies safe two-phase Cloudflare A/AAAA synchronization."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("CloudflareDnsTestKt")
}

val referenceScannerTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies maintained-feed parsing and reference-compatible round generation."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("ReferenceScannerTestKt")
}

val nodeRouteParserTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies safe local extraction of VMess/VLESS Argo routing fields."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("NodeRouteParserTestKt")
}

val xrayNodeConfigTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies full-node conversion, candidate substitution, and Xray ping parsing."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("XrayNodeConfigTestKt")
}

val xrayTemporaryConfigStoreTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies active native Xray configs survive stale-file cleanup and are erased on release."
    dependsOn(tasks.named("classes"))
    classpath = logicRuntimeClasspath
    mainClass.set("XrayTemporaryConfigStoreTestKt")
}

tasks.named("check") {
    dependsOn(
        ipSourcesTest,
        cfRangesCancellationTest,
        authorizedHostTest,
        candidatePoolTest,
        ipPipelineRankTest,
        fastFunnelTest,
        speedWindowPolicyTest,
        fixedDnsTest,
        cloudflareDnsTest,
        referenceScannerTest,
        nodeRouteParserTest,
        xrayNodeConfigTest,
        xrayTemporaryConfigStoreTest
    )
}
