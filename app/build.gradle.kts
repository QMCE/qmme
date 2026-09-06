import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.hikage)
}

val releaseKeyStorePassword = "android"
val releaseKeyAlias = "key"
val releaseKeyPassword = "android"

android {
    namespace = "rj.qmme"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "rj.qmme"
        minSdk = 23
        targetSdk = 37
        versionCode = 8
        versionName = "0.5.0"
        multiDexEnabled = true
        // qq-sdk.jar 完整版（34543 类）后主 dex 吃紧，MSF 启动期需要保底类清单，
        // 内容对照 QMCE app-new/multidex-proguard.pro 并按 rj.qmme 包名改写。
        multiDexKeepProguard = file("multidex-proguard.pro")
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "armeabi-v7a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("dev") {
            // Using testkey
            storeFile = file("./testkey.jks")
            storePassword = releaseKeyStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
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
        val enableCodeShrinks = false
        // keep 规则暂不生效（未开混淆），但先挂上，将来 enableCodeShrinks=true 时直接可用。
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("dev")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("src/main/keepRules/rules.keep")
            )
        }
        release {
            signingConfig = signingConfigs.getByName("dev")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("src/main/keepRules/rules.keep")
            )
            if (enableCodeShrinks)
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
    // QQ 运行时（手表 QQ 9.0.7.2563 完整提取，来源 QMCE qq-sdk.jar）：
    // 已通过 ASM 把字节码里 4 个宿主类的引用重定向到 rj.qmme.*，
    // 并重新打上 WtLogin/MSF 签名 patch（PkgSignFix）。
    // 契约清单见 work/ktfix-contract.txt，patch 说明见 HANDOVER.md。
    implementation(files("libs/qq-sdk.jar"))

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
    // qq-sdk.jar 官方类的第三方库依赖（jar 内未内嵌实现，缺任一即启动期
    // NoClassDefFoundError）。版本对照 QMCE（跑通同一 jar 的参照项目）：
    // - me.jessyan:autosize —— WatchApplicationDelegate（QmmeApp 的父类）直接
    //   调用 AutoSizeConfig/UnitsManager，Application 实例化即触发；
    // - okhttp3 —— MSF 网络层 119 个类引用（okio 随 okhttp 传递引入）；
    // - commons-lang3 —— 4 个类引用；ProcessLifecycleOwner 4 处引用。
    // 注意 gson 已内嵌在 qq-sdk.jar 里（com/google/gson），不可再外部引入。
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("me.jessyan:autosize:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-lang3:3.17.0")
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
