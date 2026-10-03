import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// Pure-Kotlin protocol models and the UI-facing controller contract. Multiplatform so the Compose UI in
// :shared can use it from commonMain; the JVM-only :engine implements it.
kotlin {
    jvm {
        // 17 rather than 21 so the JVM-only :engine (which targets 17 for Android) can consume this variant.
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    android {
        namespace = "io.github.jermainejamesdev.voidarray.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
    }
}
