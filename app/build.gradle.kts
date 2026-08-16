// App 模块构建配置
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.doudizhu.game"
    compileSdk = 34

    // AGP 8 起 BuildConfig 默认关闭，显式开启以便备份文件写入 appVersion
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.doudizhu.game"
        minSdk = 24          // 兼容 Android 7.0
        targetSdk = 34
        // 版本号可由 CI 注入：-PappVersionCode=整数 -PappVersionName=字符串；本地不带参数时回退默认值
        val appVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: 1
        val appVersionName = (project.findProperty("appVersionName") as? String)
            ?.takeIf { it.isNotBlank() } ?: "1.0"
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
