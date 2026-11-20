plugins {
    id("com.android.application")
}

android {
    namespace = "com.local.fanctl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.local.fanctl"
        minSdk = 24
        targetSdk = 28
        versionCode = 8
        versionName = "8.0"
        setProperty("archivesBaseName", "FanCtl-v${versionName}")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}

dependencies {
}
