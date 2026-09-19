plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.vrcx0.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vrcx0.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-p0"
    }

    // The release build is signed with the *debug* keystore on purpose.
    //
    // The point of this configuration is to be installable straight over the
    // debug build on a test device (`adb install -r` keeps the stored server
    // address and tenant token), so that release-vs-debug performance can be
    // compared on the same data with one command. A shipping build wants a real
    // keystore here.
    signingConfigs {
        create("releaseLikeDebug") {
            val androidSdkHome = providers.environmentVariable("ANDROID_SDK_HOME")
            storeFile = androidSdkHome
                .map { File(it, ".android/debug.keystore") }
                .getOrElse(File(System.getProperty("user.home"), ".android/debug.keystore"))
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Was `false`, which made "release" a debug build with a different
            // name: no R8, no resource shrinking. That matters more for Compose
            // than for a view-based UI, because skipping R8 also skips the
            // method-level optimisations the runtime leans on during scroll.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("releaseLikeDebug")
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

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Chosen over a hardcoded NavigationBar so the landscape Rail (P5) is a
    // configuration change rather than a rewrite.
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.3.2")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Address normalization is the one piece of pure logic here whose failure
    // mode is "this device can never reach the server", and it has to stay in
    // step with the Rust implementation on the other side of the wire. It costs
    // one dependency to pin that down.
    testImplementation("junit:junit:4.13.2")
}
