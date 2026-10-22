import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── version counter: versionName = <major>.<build> (build +1 per assemble) ─────
// `major` lives in build.version.properties — set it to 4 to ship 4.1, 4.2, …
// (versionCode = major*1000 + build, so it keeps growing across a major bump)
val versionFile = rootProject.file("build.version.properties")
val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}
var majorNo = versionProps.getProperty("major")?.toIntOrNull() ?: 3
var buildNo = versionProps.getProperty("build")?.toIntOrNull() ?: 0
if (gradle.startParameter.taskNames.any {
        val n = it.lowercase()
        n.contains("assemble") || n.contains("install") || n.contains("bundle")
    }
) {
    buildNo += 1
    if (buildNo > 999) {           // keep versionCodes monotonic across majors
        majorNo += 1
        buildNo = 1
    }
    Properties().apply {
        setProperty("major", majorNo.toString())
        setProperty("build", buildNo.toString())
        versionFile.outputStream().use { store(it, "DVORA version counter: versionName = <major>.<build>") }
    }
}

// private-key signing (credentials in keystore.properties at project root)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dvora.dvora20"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dvora.dvora20"
        minSdk = 24
        targetSdk = 36
        versionCode = majorNo * 1_000 + buildNo
        versionName = "$majorNo.$buildNo"

        ndk {
            abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")   // the bundled yt-dlp/python ABIs
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("dvora") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/dvora-release.jks"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dvora")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dvora")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Rename the output APK to carry its version — dvora-3.X.apk, so release-tag
    // users can tell at a glance an update is up
    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            output?.outputFileName = "dvora-$majorNo.$buildNo.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Networking and Parsing
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.coil.compose)

    // In-app browser: async list downloads
    implementation(libs.kotlinx.coroutines.android)

    // yt-dlp (bundled python) ג€” capture + download media from the in-app browser
    implementation(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)

    // forced dark / light for the in-app browser's WebView
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

