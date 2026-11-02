
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

// This branch builds only the installed Counters identity. No generated debug key.
val countersVersion = groovy.json.JsonSlurper().parse(rootProject.file("config/counters-version.json")) as Map<*, *>
val countersCode = (countersVersion["versionCode"] as Number).toInt()
val countersPrevious = (countersVersion["previousVersionCode"] as Number).toInt()
require(countersPrevious >= 215 && countersCode > countersPrevious) { "Increase Counters versionCode before an update" }
providers.environmentVariable("VERSION_CODE").orNull?.let {
    require(it == countersCode.toString()) { "VERSION_CODE disagrees with config/counters-version.json" }
}
// Explicit remote compilation stage: produces UNSIGNED ciphertext only, never an installable update.
val countersUnsigned = providers.gradleProperty("countersUnsignedCompilation").orNull == "true"
if (countersUnsigned) {
    require(System.getenv("GITHUB_ACTIONS") == "true") { "Unsigned compilation is restricted to CI" }
}
fun countersEnv(name: String) = providers.environmentVariable(name).orNull
val verifyCountersSigning by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("tools/counters-signing/signing.py"), "preflight")
}

android {
    namespace = "com.palmerintech.firetube"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Preserve the exact installed Counters applicationId for in-place updates.
        applicationId = "com.palmerintech.firetube.drivefavoritescounterspreview"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Checked-in monotonic Counters version; overrides cannot silently change it.
        versionCode = countersCode
        versionName = "2.0.13"
        // Tip jar link (Ko-fi / Buy Me a Coffee / PayPal.me). Empty = every support prompt is hidden.
        buildConfigField("String", "DONATE_URL", "\"https://ko-fi.com/stevepalmerin\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("counters") {
            storeFile = countersEnv("COUNTERS_KEYSTORE")?.let { rootProject.file(it) }
            storePassword = countersEnv("COUNTERS_STORE_PASSWORD")
            keyAlias = countersEnv("COUNTERS_KEY_ALIAS")
            keyPassword = countersEnv("COUNTERS_KEY_PASSWORD")
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-counterstest"
            signingConfig = if (countersUnsigned) null else signingConfigs.getByName("counters")
        }
        release {
            // Keep both variants compatible with the installed Counters package.
            versionNameSuffix = "-counterstest"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("config/proguard/proguard-rules.pro"),
            )
            signingConfig = if (countersUnsigned) null else signingConfigs.getByName("counters")
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

// Exec has no declared outputs: certificate validation runs even for incremental builds.
if (!countersUnsigned) {
    tasks.configureEach {
        if (name == "preBuild" || name.startsWith("package") || name.startsWith("validateSigning")) {
            dependsOn(verifyCountersSigning)
        }
    }
}
