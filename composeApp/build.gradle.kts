import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.6.11"
}

kotlin {
    jvm("desktop") {
        withJava()
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.ui:ui-desktop:1.6.11")
                implementation("org.jetbrains.compose.foundation:foundation-desktop:1.6.11")
                implementation("org.jetbrains.compose.material3:material3-desktop:1.6.11")
                implementation("org.jetbrains.compose.material:material-icons-core-desktop:1.6.11")
                implementation("org.jetbrains.compose.components:components-resources:1.6.11")
                // 平台相关 Skiko（Linux 用 linux-x64，Windows CI 用 windows-x64）
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                if (isWin) {
                    implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.6.11")
                    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.4")
                } else {
                    implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.6.11")
                    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.4")
                }
                // JSON（ShareLinkParser 用）
                implementation("org.json:json:20240303")
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                // JNA：Windows DWM 圆角窗口（WinRoundedCorners）
                implementation("net.java.dev.jna:jna:5.6.0")
                implementation("net.java.dev.jna:jna-platform:5.6.0")
                // MITM：BouncyCastle（CA 生成/动态签发）
                implementation("org.bouncycastle:bcprov-jdk15on:1.67")
                implementation("org.bouncycastle:bcpkix-jdk15on:1.67")
                // JS 模块：Rhino（沙箱执行用户脚本）
                implementation("org.mozilla:rhino:1.7.15")
            }
        }
    }
}

// Desktop application packaging
compose.desktop {
    application {
        mainClass = "com.muse.moonpc.MainKt"
        nativeDistributions {
            // targetFormats 按平台启用：Linux 本地用 app-image；
            // GitHub Actions Windows 构建时通过 -PtargetFormat=Msi 传入。
            val fmtProp = findProperty("targetFormat")?.toString()?.lowercase()
            when (fmtProp) {
                "exe" -> targetFormats(TargetFormat.Exe)
                "msi" -> targetFormats(TargetFormat.Msi)
                "deb" -> targetFormats(TargetFormat.Deb)
                "dmg" -> targetFormats(TargetFormat.Dmg)
                "appimage", "app-image" -> targetFormats(TargetFormat.AppImage)
                else -> targetFormats(TargetFormat.AppImage)
            }
            packageName = "MoonPC"
            packageVersion = "0.2.9"
            vendor = "Muse"
            description = "Moon PC - desktop proxy client (sing-box core)"
            copyright = "© 2026 Muse"
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                menuGroup = "MoonPC"
                // MSI 升级标识：固定，升级安装时识别同一产品
                upgradeUuid = "48cbabd8-2bbe-40c6-8e3a-96f4588fa96e"
                perUserInstall = true
            }
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
                bundleID = "com.muse.moonpc"
            }
        }
    }
}
