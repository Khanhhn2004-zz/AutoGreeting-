import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

val appDisplayName = "Chào Xe"
val appVersionCode = 2
val appVersionName = "1.0.1"
val buildTimestamp = Date()
val buildTimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
val buildPublishedAt = SimpleDateFormat("yyyy-MM-dd HH:mm 'GMT+7'", Locale.US).apply {
    timeZone = buildTimeZone
}.format(buildTimestamp)
val buildApkPublishedLabel = SimpleDateFormat("ddMMyy-HHmm", Locale.US).apply {
    timeZone = buildTimeZone
}.format(buildTimestamp)
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun localProperty(name: String) = providers.provider {
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
}

val supportLogAppsScriptUrl = providers.gradleProperty("supportLogAppsScriptUrl")
    .orElse(providers.environmentVariable("SUPPORT_LOG_APPS_SCRIPT_URL"))
    .orElse(localProperty("supportLogAppsScriptUrl"))
    .orElse("")
    .get()
val supportLogAppsScriptSecret = providers.gradleProperty("supportLogAppsScriptSecret")
    .orElse(providers.environmentVariable("SUPPORT_LOG_APPS_SCRIPT_SECRET"))
    .orElse(localProperty("supportLogAppsScriptSecret"))
    .orElse("")
    .get()

fun String.toBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.carchatbot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.carchatbot"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "APP_DISPLAY_NAME", "\"$appDisplayName\"")
        buildConfigField("String", "BUILD_PUBLISHED_AT", "\"$buildPublishedAt\"")
        buildConfigField("String", "BUILD_VERSION_LABEL", "\"$appVersionName ($appVersionCode)\"")
        buildConfigField("String", "SUPPORT_LOG_APPS_SCRIPT_URL", supportLogAppsScriptUrl.toBuildConfigString())
        buildConfigField("String", "SUPPORT_LOG_APPS_SCRIPT_SECRET", supportLogAppsScriptSecret.toBuildConfigString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkTestSources = false
        ignoreTestSources = true
        // Compose runtime lint on this branch crashes while parsing newer Kotlin metadata.
        disable += "StateFlowValueCalledInComposition"
    }

    applicationVariants.all {
        val variantName = name
        kotlin.sourceSets {
            getByName(variantName) {
                kotlin.srcDir("build/generated/ksp/$variantName/kotlin")
            }
        }
        outputs.all {
            val apkOutput = this as com.android.build.gradle.api.ApkVariantOutput
            apkOutput.outputFileName =
                "chao-xe-$buildApkPublishedLabel.apk"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Activity + ViewModel
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Data
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.media:media:1.7.0")
    implementation(libs.androidx.work.runtime.ktx)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization.converter)

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // AndroidX
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
}
