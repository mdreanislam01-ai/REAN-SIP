import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/* Release signing: drop a keystore.properties (git-ignored) in the repo root to
   sign release builds with your own key; without it release uses the debug key. */
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply { if (hasKeystore) keystorePropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.callerx.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callerx.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 50
        versionName = "1.5.31"
        /* Keep the universal APK to two ABIs: without this the universal build packs all
           four liblinphone ABIs and grows to ~170 MB. */
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    /* Per-ABI APKs: the universal build stays app-debug.apk for compatibility, and
       arm64-v8a / armeabi-v7a get their own ~40% smaller downloads. */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.linphone:linphone-sdk-android:5.4.110")
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}
