plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sovopr.xlite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sovopr.xlite"
        minSdk = 24
        targetSdk = 34
        val appVersion = (System.getenv("XLITE_VERSION") ?: "1.0.31").removePrefix("v")
        versionName = appVersion
        val envCode = System.getenv("XLITE_VERSION_CODE")?.toIntOrNull()
        versionCode = if (envCode != null && envCode > 27) envCode else 27
        val gitSha = (System.getenv("XLITE_GIT_SHA") ?: "local").take(7)
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val ksPath = System.getenv("XLITE_KEYSTORE_FILE")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("XLITE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XLITE_KEY_ALIAS")
                keyPassword = System.getenv("XLITE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
