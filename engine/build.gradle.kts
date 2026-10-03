import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
}

// Networking and file transfer, shared by the Android and desktop apps. A plain JVM module because Kotlin
// does not support a source set shared between JVM and Android targets: Gradle compiles one, but the IDE
// cannot resolve java.* or JVM-only libraries in it.
//
// Targets Java 17 bytecode and API so the Android app can consume it. No toolchain is set because Gradle
// rejects a toolchain combined with explicit source/target compatibility.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Compile against the JDK 17 API so newer JDK methods (absent on Android) cannot be linked by accident.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.tls.certificates)

    testImplementation(libs.kotlin.test)
}
