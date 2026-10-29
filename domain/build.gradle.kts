import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin on purpose: the domain must not know about Android or Bluetooth APIs.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Coroutines core is pure Kotlin (StateFlow for observable brush state).
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
