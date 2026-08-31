plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun requiredReleaseEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("正式构建缺少环境变量：$name")

val releaseStoreFile = System.getenv("CFIP_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseStoreFile != null

android {
    namespace = "com.xiaowu7z.cfipoptimizer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaowu7z.cfipoptimizer"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    val releaseSigning = releaseStoreFile?.let { storePath ->
        signingConfigs.create("rrRelease") {
            storeFile = file(storePath)
            storeType = "PKCS12"
            storePassword = requiredReleaseEnv("CFIP_RELEASE_STORE_PASSWORD")
            keyAlias = requiredReleaseEnv("CFIP_RELEASE_KEY_ALIAS")
            keyPassword = requiredReleaseEnv("CFIP_RELEASE_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }

    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.srcDirs("src")
        res.srcDirs("res")
        assets.srcDirs("assets")
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// A release-shaped artifact must never silently fall back to an unsigned or
// debug identity. Debug builds intentionally remain usable without secrets.
tasks.configureEach {
    if (name in setOf("assembleRelease", "bundleRelease", "packageRelease")) {
        doFirst {
            check(hasReleaseSigning) {
                "正式构建必须设置 CFIP_RELEASE_STORE_FILE 及对应的新 PKCS#12 签名环境变量"
            }
        }
    }
}
