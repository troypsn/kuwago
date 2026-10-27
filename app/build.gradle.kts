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

val rawApiUrl: String = System.getenv("API_URL")
    ?: localProperties.getProperty("API_URL")
    ?: "https://kwagobackend.onrender.com/"
val apiUrl: String = if (rawApiUrl.endsWith("/")) rawApiUrl else "$rawApiUrl/"

val kwagoApiKey: String = System.getenv("KWAGO_API_KEY")
    ?: localProperties.getProperty("KWAGO_API_KEY")
    ?: ""

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
        
        buildConfigField("String", "API_URL", "\"$apiUrl\"")
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
            isMinifyEnabled = false
            if (releaseKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
