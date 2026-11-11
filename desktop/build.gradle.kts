import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.compose") version "1.12.2"
}

group = "com.metrolist.desktop"
// Release builds pass -PappVersion from the git tag (see .github/workflows/desktop-release.yml).
version = providers.gradleProperty("appVersion").getOrElse("1.0.0")

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        // Reuse the Android app's YouTube Music API layer verbatim.
        kotlin.srcDir("../innertube/src/main/kotlin")
        // Also reuse the Android app's pure-Kotlin Last.fm and lyrics clients; skip the rest of the Android sources.
        val shared = listOf("com/metrolist/lastfm", "com/metrolist/lrclib", "com/metrolist/kugou", "com/metrolist/music/betterlyrics")
        kotlin.srcDir("../app/src/main/kotlin")
        kotlin.exclude { e ->
            e.file.invariantSeparatorsPath.contains("/app/src/main/kotlin/") &&
                shared.none { it.startsWith(e.path) || e.path.startsWith(it) }
        }
    }
}

// JavaCPP ships one native bundle per platform; only pull the one for the build host.
val javacppPlatform: String =
    run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val a = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x86_64"
        when {
            os.contains("win") -> "windows-$a"
            os.contains("mac") -> "macosx-$a"
            else -> "linux-$a"
        }
    }
val javacppVersion = "1.5.14"
val ffmpegVersion = "8.1.2-$javacppVersion"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(libs.innertubex)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("net.java.dev.jna:jna:5.17.0")
    // Embedded Chromium for Google sign-in; its natives download on first use, not in the installer.
    implementation("me.friwi:jcefmaven:152.0.6")

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.materialKolor)

    implementation("org.bytedeco:javacv:$javacppVersion") { isTransitive = false }
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:$javacppPlatform")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$javacppPlatform")
}

compose.desktop {
    application {
        mainClass = "com.metrolist.desktop.MainKt"
        // Package and run on the JDK 21 LTS toolchain regardless of which JDK launches Gradle.
        javaHome =
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                .get().metadata.installationPath.asFile.absolutePath
        jvmArgs +=
            listOf(
                "-Xmx768m",
                "--enable-native-access=ALL-UNNAMED",
                // Required by JCEF on Java 17+.
                "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-exports=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            )
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Metrolist PC"
            packageVersion = project.version.toString()
            description = "YouTube Music client for desktop (port of Metrolist)"
            vendor = "Metrolist"
            licenseFile.set(project.file("../LICENSE"))
            // The HTTP stacks, JavaCPP and Skiko reach into several JDK modules; bundling all keeps the runtime safe.
            includeAllModules = true
            windows {
                menuGroup = "Metrolist"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f1d9a8e-5b0e-4f3a-9c0c-3d5d7a1e2b11"
                iconFile.set(project.file("icon.ico"))
            }
            linux {
                packageName = "metrolist-pc"
                debMaintainer = "aleksa07@users.noreply.github.com"
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}

tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Searches YouTube Music, resolves a stream and decodes a few seconds of audio."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.metrolist.desktop.SmokeTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
