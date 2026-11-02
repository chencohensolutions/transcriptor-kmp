import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":sharedLogic"))

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
        // -Pabi=arm64-v8a (scripts/ui-lab.sh passes the device's) packs only that processor's native libraries
        // (whisper.cpp, FLAC), for a faster install on one phone. Without it, every ABI.
        (findProperty("abi") as String?)?.let { abis -> ndk { abiFilters += abis.split(",") } }
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
