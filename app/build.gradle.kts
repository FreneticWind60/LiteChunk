import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing lives outside the build script: keystore.properties + keystore/*.jks are the only
// copy of the key, so they stay out of version control and the project still configures (and still
// produces an unsigned release) on a machine that does not have them.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val canSignRelease = keystorePropertiesFile.exists()

android {
    namespace = "com.frenetic.litechunk"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.frenetic.litechunk"
        minSdk = 27
        targetSdk = 37
        // 1.21.0 build5 pr1
        // versionCode = major*10^8 + minor*10^6 + patch*10^4 + build*100 + stage,
        //   stage = 1..49 for a preview ("prN"), 50 for the released build.
        //   -> 1.21.0 build1 = 121_000_150, 1.21.0 build2 = 121_000_250, 1.21.0 build3 = 121_000_350.
        // A preview of build3 must sit *above* the released build2 (121_000_250) or it cannot be installed
        // over it, so the build number moves forward even for a preview.
        // The 10^8 scale (rather than 10^7) is deliberate: the first two previews shipped as
        // 12_100_501 / 12_100_502 under an older encoding, and a release has to be installable over
        // them on any device that still has one.
        versionCode = 121_000_551
        versionName = "1.21.0 build5 pr1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // minSdk 27 only needs the v2/v3 schemes; v1 would add ~60 KB of
                // META-INF signatures to every release APK for nothing.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    androidResources {
        // The UI ships zh / en / ja only, but Material & AppCompat carry strings for
        // ~85 locales; keeping them all inflates resources.arsc by a few hundred KB.
        // The zh variants have to be listed per region: a bare "zh" filter does not
        // keep the zh-rCN / zh-rTW / zh-rHK buckets those libraries ship.
        localeFilters += listOf("zh", "zh-rCN", "zh-rTW", "zh-rHK", "en", "ja")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // The engine, its dex loader and the public facade all arrive from the ChunkBridge composite build
    // declared in settings.gradle.kts; `api` comes with it as a transitive dependency.
    implementation("com.frenetic.chunkbridge:chunkbridge:1.0.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
