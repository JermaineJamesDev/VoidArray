import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":engine"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.yunjam.eztransfer.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // Install directory and Start Menu name, so it is the display name rather than the package id.
            packageName = "EzTransfer"
            packageVersion = "1.0.0"
            // From :desktopApp:suggestRuntimeModules; jlink drops modules it cannot detect statically.
            // jdk.crypto.ec is added by hand: TLS loads it through a service provider, which jdeps cannot see,
            // and without it handshakes lose the ECDHE key exchange.
            modules("java.instrument", "java.management", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                // Must never change once a build has shipped, or new MSIs install side by side
                // instead of upgrading existing installs.
                upgradeUuid = "57f7a572-32d4-4a75-9972-af03b5644e4c"
                menuGroup = "EzTransfer"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
