import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
/** A file's sha256, read in pieces. */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * [name] in libs/ (not in git), downloaded from [url] and checked against its sha256. A file already there is
 * checked too, at every build, and downloaded again when it isn't the pinned one: a checkout made before a new pin
 * would otherwise build with the old file (an old sherpa-onnx AAR on Nov 15 left the NPU-encoder model unable to load).
 */
fun pinned(name: String, url: String, sha256: String): File = file("libs/$name").also { out ->
    if (out.exists() && sha256Of(out) != sha256) {
        logger.lifecycle("libs/$name isn't the pinned file (sha256 $sha256): downloading it again")
        out.delete()
    }
    if (!out.exists()) {
        check(!url.contains("HF_USER")) { "libs/$name isn't published yet: build it (docs/npu-plan.md) and put it in libs/" }
        out.parentFile.mkdirs()
        val temp = File(out.path + ".part")
        uri(url).toURL().openStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
        val sha = sha256Of(temp)
        check(sha == sha256) { "$name: unexpected sha256 $sha" }
        temp.renameTo(out)
    }
}

// sherpa-onnx (Apache-2.0) runs the streaming models (Zipformer) and Whisper on Qualcomm NPUs. Its release 2.0.1
// with Whisper's NPU settings in Kotlin (upstream #4126), our Whisper turbo fixes, and its 64-bit ARM library built
// with the QNN backend: sherpa-onnx's own AAR project, from build/qnn/sherpa-onnx (docs/npu-plan.md).
val sherpaOnnx = pinned(
    "sherpa-onnx-2.0.1-qnn.aar",
    "https://huggingface.co/hencohen83/sherpa-onnx-qnn-android/resolve/47599a37012cc5457796e059e79909ce547b8095/sherpa-onnx-2.0.1-qnn.aar",
    "8b44fa790a8640cfd4f76ebfcac1dd86fdbaa3bb8922b420ef328bfcb4d347f5",
)

// Qualcomm's runtime for the NPU models, as sherpa-onnx publishes it for QAIRT 2.40 (the version the models are
// compiled with): the CPU side, and the NPU side (Skel) for each Hexagon version the catalog has a model for.
val qnnLibs = pinned(
    "qnn-libs-2.40.0.251030.tar.bz2",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-libs-2.40.0.251030.tar.bz2",
    "1078c0833b1e948518ff433e081eda511475c7fd9a82c7798a862f59b95a7ce5",
)
val qnnJniLibs = layout.buildDirectory.dir("generated/qnn-jni").get().asFile
val unpackQnnLibs = tasks.register<Sync>("unpackQnnLibs") {
    from(tarTree(resources.bzip2(qnnLibs))) {
        include("*/libQnnHtp.so", "*/libQnnSystem.so", "*/libQnnHtpV79Stub.so", "*/libQnnHtpV79Skel.so")
        eachFile { path = "arm64-v8a/$name" }
        includeEmptyDirs = false
    }
    into(qnnJniLibs)
}
tasks.named("preBuild") { dependsOn(unpackQnnLibs) }

dependencies {
    implementation(project(":sharedLogic"))
    // shed on Android: the device key in the Keystore, uploads with WorkManager.
    implementation("com.chencohensolution.shed:android:0.1.0")
    implementation(files(sherpaOnnx))
    // The calls folder looked at now and then for new calls (CallsWorker).
    implementation(libs.androidx.work.runtime)
    implementation(libs.maplibre.android)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // On the device or the emulator: the system's codecs (AAC round trips).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

/** The app's version, kept in version.properties at the repository's root. */
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "com.chencohensolution.transcriptor"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.chencohensolution.transcriptor"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // From version.properties (scripts/release.sh bumps it).
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        // whisper.cpp is built for 64-bit ARM only: every Android 15 phone, and the emulator on Apple silicon.
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The UI Lab app (scripts/ui-lab.sh): the debug build under its own id and name that only opens the lab, so it
        // installs beside Transcriptor without replacing it or its notebooks. Its code is src/debug's.
        create("uiLab") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uilab"
            matchingFallbacks += listOf("debug")
        }
    }
    sourceSets.named("uiLab") {
        kotlin.srcDir("src/debug/kotlin")
        res.srcDir("src/debug/res")
    }
    sourceSets.named("main") { jniLibs.srcDir(qnnJniLibs) }
    packaging {
        // Ktor and OkHttp (shed) bring the same license files.
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Unpacked on install: the NPU loads its part of Qualcomm's runtime (the Skel) from a file.
            useLegacyPackaging = true
            // The Skel is a Hexagon library, not ARM: nothing to strip.
            keepDebugSymbols += "**/libQnnHtpV*Skel.so"
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
