import java.util.Properties

plugins {
    id("com.android.application")
}

// ---------- 签名证书 ----------
// 本地：把签名信息写在项目根目录的 keystore.properties（已被 .gitignore 排除）
// CI  ：由 workflow 从仓库 Secrets 注入 RELEASE_KEYSTORE_PATH 等环境变量
// 两者都没有时回退 debug 证书，保证产物仍可直接安装
val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("storeFile", "RELEASE_KEYSTORE_PATH")
val hasReleaseKeystore = !releaseStorePath.isNullOrBlank()
println("签名配置：${if (hasReleaseKeystore) "正式证书（$releaseStorePath）" else "未配置，回退 debug 证书"}")

android {
    namespace = "com.local.fanctl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.local.fanctl"
        minSdk = 24
        targetSdk = 28
        versionCode = 9
        versionName = "8.1"
        setProperty("archivesBaseName", "FanCtl-v${versionName}")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = signingValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
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
            signingConfig =
                if (hasReleaseKeystore) {
                    signingConfigs.getByName("release")   // 正式证书
                } else {
                    signingConfigs.getByName("debug")     // 回退签名，保证产物可直接安装
                }
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
