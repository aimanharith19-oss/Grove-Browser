import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.grove.browser"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.grove.browser"
        minSdk = 29
        targetSdk = 37
        versionCode = 900000018
        versionName = "1.4.1"
    }
    // One APK per CPU type: each is a fraction of a universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    // Same signing key for every build (from the repo secrets), so updates install over the old app.
    val ks = System.getenv("GROVE_KEYSTORE_FILE")
    // Tolerates a pasted label or spaces around the password.
    val ksPass = System.getenv("GROVE_KS_PASS")?.substringAfter("=")?.trim()
    signingConfigs {
        if (ks != null && file(ks).exists()) create("grove") {
            storeFile = file(ks)
            storePassword = ksPass
            keyAlias = "grove"
            keyPassword = ksPass
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("grove") ?: signingConfigs.getByName("debug")
        }
    }
    // Compressed native libs = much smaller download.
    packaging {
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = false }
    androidResources { localeFilters += listOf("en") }
    lint { checkReleaseBuilds = false }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("org.mozilla.geckoview:geckoview:+")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
}
