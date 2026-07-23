import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.hikage)
}

android {
    namespace = "rj.qmme"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "rj.qmme"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "armeabi-v7a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    // Match the original QQ APK's native-library packaging. Without legacy
    // packaging AGP stores every .so uncompressed, adding roughly 34 MiB to
    // the APK even though the native library set is smaller than the source.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            if (false)
            {
                isMinifyEnabled = true
                //noinspection NotShrinkingResources
                isShrinkResources = false
            } else {
                isMinifyEnabled = false
                //noinspection NotShrinkingResources
                isShrinkResources = false
            }
        }
    }
    // AGP 9 built-in Kotlin aligns its jvmTarget to these Java levels.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Keep qq-core.jar immutable. This derived runtime preserves the Watch JNI
    // ABI. Missing QQNT types are copied as original class files from the
    // matching Watch APK; do not reimplement com.tencent.qqnt.* in source.
    implementation(files("libs/qq-core-watch-runtime.jar"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Hikage: native Android View runtime with Kotlin DSL. Compose is intentionally not used.
    implementation(platform(libs.hikage.bom))
    implementation(libs.hikage.core)
    implementation(libs.hikage.runtime)
    implementation(libs.hikage.runtime.attribute)
    implementation(libs.hikage.extension)
    implementation(libs.hikage.extension.betterandroid)
    implementation(libs.hikage.widget.androidx)
    implementation(libs.hikage.widget.material)
    implementation(platform(libs.betterandroid.android.bom))
    implementation(libs.betterandroid.ui.extension)
    implementation(libs.betterandroid.system.extension)

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
