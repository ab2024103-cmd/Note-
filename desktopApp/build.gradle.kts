import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":shared"))
}

compose.desktop {
    application {
        mainClass = "com.notepadpro.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "NotePadPro"
            packageVersion = "1.0.0"
            description = "NotePad Pro - lightweight rich-text notes for desktop & Android"
            vendor = "NotePadPro"
            windows {
                menuGroup = "NotePad Pro"
                perUserInstall = true
                shortcut = true
                dirChooser = true
            }
            includeAllModules = true
        }
    }
}
