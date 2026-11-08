import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "live.hanab.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "live.hanab.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.8"
    }
    // Release signing comes from keystore.properties (gitignored; see keystore.properties.example).
    // Without it the release build is unsigned and cannot be installed.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidsvg)
    testImplementation("junit:junit:4.13.2")
}

// The offline-play library (Rust) is built by native/build.ps1 or native/build.sh and packaged from the jniLibs
// folders. Build it as part of every Gradle build, so the app never ships a library older than its Kotlin: Gradle
// skips the task when the Rust sources, lock file and toolchain files have not changed since the last build.
// Without cargo installed the task is skipped (offline play is then unavailable), and -PskipNative skips it.
val nativeDir = rootProject.file("native")
val hasCargo = try {
    ProcessBuilder("cargo", "--version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0
} catch (_: java.io.IOException) {
    false
}
val buildNative by tasks.registering(Exec::class) {
    description = "Builds the offline-play library (native/offline) for the phone, and for emulators in debug builds."
    val windows = System.getProperty("os.name").startsWith("Windows")
    onlyIf {
        if (!hasCargo) logger.warn("cargo not found: the offline-play library is not built (install rustup from https://rustup.rs).")
        hasCargo && !providers.gradleProperty("skipNative").isPresent
    }
    inputs.dir(nativeDir.resolve("offline/src"))
    inputs.files(
        nativeDir.resolve("offline/Cargo.toml"), nativeDir.resolve("offline/Cargo.lock"),
        nativeDir.resolve("offline/rust-toolchain.toml"), nativeDir.resolve("offline/.cargo/config.toml"),
        nativeDir.resolve("build.ps1"), nativeDir.resolve("build.sh"),
    ).withPropertyName("buildFiles")
    outputs.files(
        file("src/main/jniLibs/arm64-v8a/libhanab_offline.so"),
        file("src/debug/jniLibs/x86_64/libhanab_offline.so"),
    )
    workingDir = rootProject.projectDir
    if (windows) {
        commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", nativeDir.resolve("build.ps1").path)
    } else {
        commandLine("bash", nativeDir.resolve("build.sh").path)
    }
}
tasks.named("preBuild") { dependsOn(buildNative) }
