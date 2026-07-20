plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.devtools.ksp)
}

import java.util.Properties

// 读取可选的 keystore.properties 用于正式签名；不存在则 release 自动使用 debug 签名以便直接安装
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.litereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.litereader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩与资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
            excludes += "org/slf4j/impl/**"
        }
        // 压缩 native 库（.so）以减小 APK 体积
        // useLegacyPackaging = true → 在 APK 内对 .so 做 zip 压缩
        // 代价：安装时系统需解压 .so 到 nativeLibraryDir，启动稍慢
        // 收益：APK 体积显著减小（libpdfium.so 约 8MB → ~3MB）
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.navigation.compose)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Data & Network
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // Image
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    debugImplementation(libs.androidx.ui.tooling)
}

// ==================== Rust 原生库构建 ====================
// 使用 cargo-ndk 编译 Rust 为 arm64-v8a 动态库，并复制到 jniLibs。
// 前置条件：
//   1. rustup target add aarch64-linux-android
//   2. cargo install cargo-ndk
//   3. 配置 ANDROID_NDK_HOME 环境变量
val rustDir = rootProject.file("rust-reader")
val jniLibsDir = file("src/main/jniLibs/arm64-v8a")
val rustOutputDir = File(rustDir, "target/aarch64-linux-android/release")

val buildRustLibrary by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build Rust native library for Android arm64-v8a"
    workingDir = rustDir
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "build", "--release")
    inputs.dir(File(rustDir, "src"))
    inputs.file(File(rustDir, "Cargo.toml"))
    outputs.file(File(rustOutputDir, "liblitereader.so"))
}

val copyRustLibrary by tasks.registering(Copy::class) {
    group = "rust"
    description = "Copy Rust .so to jniLibs"
    from(rustOutputDir)
    into(jniLibsDir)
    include("*.so")
    dependsOn(buildRustLibrary)
}

tasks.named("preBuild") {
    dependsOn(copyRustLibrary)
}