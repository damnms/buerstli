plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from environment variables (set by the GitHub release
// workflow). Without them, the release build is simply left unsigned.
val keystorePath: String? = System.getenv("BUERSTLI_KEYSTORE")

android {
    namespace = "io.github.damnms.buerstli"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.damnms.buerstli"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.2"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BUERSTLI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUERSTLI_KEY_ALIAS")
                keyPassword = System.getenv("BUERSTLI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a test build live next to the installed release (different signing keys).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = true
        disable += setOf(
            // Dependency updates are proposed by Dependabot and verified by CI instead.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            // The app stores no data at all, so there is nothing to back up or transfer.
            "DataExtractionRules",
        )
    }
    // F-Droid / IzzyOnDroid don't like Google's encrypted dependency blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":drivers:laifen"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
