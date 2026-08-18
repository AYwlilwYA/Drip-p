plugins {
    alias(libs.plugins.agp.app)
    // AGP 9 内置 Kotlin，不需要 org.jetbrains.kotlin.android；compose 编译器插件仍要显式 apply。
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        // Expressive 组件（Switch/MotionScheme 等）在 1.5.0-alpha25 仍是实验性 API，全局 opt-in。
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

android {
    namespace = "drip.manager"
    compileSdk = 37

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // AIDL 放工程级 aidl/ 目录，不在 app/src/main/aidl
    sourceSets {
        getByName("main") {
            aidl.srcDirs(rootProject.file("aidl"))
        }
    }

    defaultConfig {
        applicationId = "drip.manager"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose BOM 对齐所有 androidx.compose.* 产物。
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
