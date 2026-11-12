import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.tsdroid.han"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wslinnn.ts6mobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 22
        versionName = "2.10.0-Han"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing comes from environment variables (see the release
        // workflow) — no passwords live in this repository. Without them the
        // build falls back to the debug key, so local development is unaffected.
        val keystoreFile = file(System.getenv("SIGNING_KEYSTORE_FILE") ?: "${rootDir}/release.keystore")
        val storePass = System.getenv("SIGNING_STORE_PASSWORD")
        val keyAliasName = System.getenv("SIGNING_KEY_ALIAS")
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")
        if (keystoreFile.exists() && storePass != null && keyAliasName != null && keyPass != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // arm64 keeps the APK small — x86_64 is emulator-only, and multi-APK
            // splits would break UpdateChecker (it expects exactly one .apk asset)
            ndk {
                abiFilters += "arm64-v8a"
            }
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // False positive with R8 enabled: lint cannot resolve the androidx
        // superclass chain (ComponentActivity / LifecycleService) in the
        // minified release variant and flags manifest components
        disable += "Instantiatable"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }
}

// Task to build Rust native libraries via cargo-ndk
//
// The NDK location comes from the same places the Android plugin uses:
// $ANDROID_NDK_HOME, then ndk.dir, then <sdk.dir>/ndk/<version>. The old
// hard-coded "~/Android/Sdk/ndk/..." pointed at a directory that does not
// exist on this project's Windows machines.
val ndkVersion = "27.2.12479018"

tasks.register<Exec>("buildRustLibs") {
    workingDir = file("${rootDir}/tslib_multi")
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val sdkDir = localProperties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    val ndkDir = System.getenv("ANDROID_NDK_HOME")
        ?: localProperties.getProperty("ndk.dir")
        ?: sdkDir?.let { file("$it/ndk/$ndkVersion").absolutePath }
    if (ndkDir != null) {
        environment("ANDROID_NDK_HOME", ndkDir)
        environment("ANDROID_NDK", ndkDir)
    } else {
        logger.warn("buildRustLibs: no NDK found (set ANDROID_NDK_HOME or sdk.dir in local.properties)")
    }
    environment("CMAKE_POLICY_VERSION_MINIMUM", "3.5")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", "${projectDir}/src/main/jniLibs",
        "build", "--release", "-p", "tslib-jni",
        "--features", "vendored-openssl",
        // -j10 oversubscribed the 4-core dev machines the OpenSSL build runs
        // on; AGENTS.md documents -j4 for exactly that reason
        "-j4"
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.haze.materials)
    implementation(libs.mikepenz.markdown.m3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Non-ASCII project paths on Windows break class loading unless the
    // test worker reads paths as UTF-8
    jvmArgs("-Dfile.encoding=UTF-8")
}
