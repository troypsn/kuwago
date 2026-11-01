import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val rawApiUrl: String = System.getenv("API_URL")?.ifEmpty { null }
    ?: localProperties.getProperty("API_URL")?.ifEmpty { null }
    ?: "https://kwago-backend.vercel.app/"
val apiUrl: String = if (rawApiUrl.endsWith("/")) rawApiUrl else "$rawApiUrl/"

val rawFallbackApiUrl: String = System.getenv("FALLBACK_API_URL")?.ifEmpty { null }
    ?: localProperties.getProperty("FALLBACK_API_URL")?.ifEmpty { null }
    ?: "https://kwagobackend.onrender.com/"
val fallbackApiUrl: String = if (rawFallbackApiUrl.endsWith("/")) rawFallbackApiUrl else "$rawFallbackApiUrl/"

val kwagoApiKey: String = System.getenv("KWAGO_API_KEY")?.ifEmpty { null }
    ?: localProperties.getProperty("KWAGO_API_KEY")?.ifEmpty { null }
    ?: "773881c32e8fabf37030ae54fce3e60585f3ead8b2d69fd15a1fa749093c3c3d"

android {
    namespace = "com.example.kuwago"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.kuwago"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "API_URL", "\"$apiUrl\"")
        buildConfigField("String", "FALLBACK_API_URL", "\"$fallbackApiUrl\"")
        buildConfigField("String", "BACKEND_CREDENTIAL", "\"$kwagoApiKey\"")
    }

    val releaseKeystoreFile = file("release.keystore")
    signingConfigs {
        if (releaseKeystoreFile.exists()) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "kuwago_release_pass"
                keyAlias = System.getenv("KEY_ALIAS") ?: "kuwago_release"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "kuwago_release_pass"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = false
            if (releaseKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols.clear()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.0")
    
    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    // Local Encrypted Database (Room + SQLCipher)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)

    // ONNX Runtime
    implementation(libs.onnxruntime.android)
    testImplementation(libs.onnxruntime.jvm)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Strip debug symbols and clear DT_RUNPATH ($ORIGIN) on native .so libraries before APK packaging
tasks.matching { it.name.startsWith("strip") && it.name.endsWith("DebugSymbols") }.configureEach {
    doLast {
        val strippedDir = layout.buildDirectory.dir("intermediates/stripped_native_libs").get().asFile
        if (strippedDir.exists()) {
            strippedDir.walkTopDown().filter { it.isFile && it.extension == "so" }.forEach { soFile ->
                try {
                    ProcessBuilder("aarch64-linux-gnu-strip", "--strip-unneeded", soFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                } catch (_: Exception) {
                    // aarch64-linux-gnu-strip not present on local Windows machine; safely skipped
                }
                try {
                    ProcessBuilder("patchelf", "--remove-rpath", soFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                } catch (_: Exception) {
                    // patchelf not present on local Windows machine; safely skipped
                }
            }
        }
    }
}
