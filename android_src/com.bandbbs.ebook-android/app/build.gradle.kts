import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.whyy.snapnotes"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.whyy.snapnotes"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 可选签名：若根目录存在 keystore.properties 且引用的 jks 文件也存在，则启用 release 签名；否则 debug/release 走默认签名。
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    var hasKeystore = false
    var keystoreProperties: Properties? = null
    if (keystorePropertiesFile.exists()) {
        keystoreProperties = Properties().apply {
            FileInputStream(keystorePropertiesFile).use { load(it) }
        }
        val storeFilePath = keystoreProperties.getProperty("storeFile")
        if (storeFilePath != null && rootProject.file(storeFilePath).exists()) {
            hasKeystore = true
        }
    }
    if (hasKeystore && keystoreProperties != null) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties!!.getProperty("keyAlias")
                keyPassword = keystoreProperties!!.getProperty("keyPassword")
                // 使用 rootProject.file() 确保 storeFile 路径相对于项目根目录解析，
                // 而非 app 模块目录。CI 中 release.jks 在仓库根目录生成。
                storeFile = rootProject.file(keystoreProperties!!.getProperty("storeFile"))
                storePassword = keystoreProperties!!.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/androidx.cardview_cardview.version",
                "META-INF/androidx.versionedparcelable.version"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // 小米穿戴第三方 SDK v1.4 (本地 aar, BLE 通信底层)
    implementation(files("./libs/xms-wearable-lib_1.4_release.aar"))
    implementation(libs.androidx.compose.ui)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)

    implementation("androidx.navigation3:navigation3-runtime:1.0.1")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")

    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")

    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")

    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.3")

    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")

    implementation("com.squareup.okhttp3:okhttp:5.4.0")
implementation(libs.markdown.renderer)
implementation(libs.markdown.renderer.code)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
