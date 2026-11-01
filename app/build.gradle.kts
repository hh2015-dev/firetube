import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

// Firebase (crash reports, Google sign-in, sync) is optional: the official builds get
// app/google-services.json from a CI secret; without it (e.g. a fork) FireTube builds and runs
// with those features switched off.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Release signing is read from keystore.properties at the repo root (git-ignored); see docs/MAINTAINING.md.
// Without that file, release builds are produced unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.palmerintech.firetube"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Same id as the 1.x app so existing installs upgrade in place.
        applicationId = "com.palmerintech.firetube"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 1.x ended at 95. CI may override with VERSION_CODE.
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 213
        versionName = "2.0.13"
        // Tip jar link (Ko-fi / Buy Me a Coffee / PayPal.me). Empty = every support prompt is hidden.
        buildConfigField("String", "DONATE_URL", "\"https://ko-fi.com/stevepalmerin\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
        // A shared debug key is optional (it's git-ignored); otherwise Android's default one is used.
        rootProject.file("config/keystore/debug.keystore").takeIf { it.exists() }?.let { key ->
            getByName("debug") { storeFile = key }
        }
    }

    buildTypes {
        // Side-by-side test APK: never overwrite a differently signed personal installation.
        debug {
            applicationIdSuffix = ".drivepreview"
            versionNameSuffix = "-drivepreview"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("config/proguard/proguard-rules.pro"),
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.nio / java.time APIs newer than some supported devices.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "mozilla/public-suffix-list.txt")
    }

    lint {
        // Every string needs a translation in each values-xx folder (and nothing extra there).
        error += listOf("MissingTranslation", "ExtraTranslation")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:extractor"))
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.reorderable)
    implementation(libs.androidx.fragment)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.cast)
    implementation(libs.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.palette)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    // Required for Android's first-party, user-consented Drive read-only authorization.
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    implementation(libs.timber)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
