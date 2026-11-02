import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
// sherpa-onnx (Apache-2.0) runs the streaming models (Zipformer). It isn't on Maven Central: its release AAR is
// downloaded once into libs/ (not in git), pinned to a version and checked against its sha256.
val sherpaOnnx: File = file("libs/sherpa-onnx-2.0.1.aar").also { aar ->
    if (!aar.exists()) {
        aar.parentFile.mkdirs()
        val temp = File(aar.path + ".part")
        uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar").toURL()
            .openStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(temp.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7") { "sherpa-onnx AAR: unexpected sha256 $sha" }
        temp.renameTo(aar)
    }
}

dependencies {
    implementation(project(":sharedLogic"))
    implementation(files(sherpaOnnx))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

android {
    namespace = "com.chencohensolution.transcriptor"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.chencohensolution.transcriptor"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // whisper.cpp is built for 64-bit ARM only: every Android 15 phone, and the emulator on Apple silicon.
        ndk { abiFilters += "arm64-v8a" }
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
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
