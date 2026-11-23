import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

fun parseSemverCode(version: String): Int {
    val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore("-")
    val parts = clean.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 1 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return (major * 10000) + (minor * 100) + patch
}

val resolvedVersionName: String = (
    providers.gradleProperty("RELEASE_VERSION_NAME").orNull
        ?: System.getenv("RELEASE_VERSION_NAME")
        ?: "1.0.0"
    ).trim().removePrefix("v").removePrefix("V")

val resolvedVersionCode: Int = (
    providers.gradleProperty("RELEASE_VERSION_CODE").orNull
        ?: System.getenv("RELEASE_VERSION_CODE")
    )?.toIntOrNull() ?: parseSemverCode(resolvedVersionName)

val signingProps = Properties().apply {
    val propsFile = rootProject.file("signing.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

val releaseStorePath = System.getenv("SIGNING_KEYSTORE_PATH") ?: signingProps.getProperty("storeFile")
val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: signingProps.getProperty("storePassword")
val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: signingProps.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: signingProps.getProperty("keyPassword")
val hasReleaseSigning = !releaseStorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseStorePath).exists()

android {
    namespace = "com.personalsecretary.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personalsecretary.app"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
