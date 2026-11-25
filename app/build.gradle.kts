import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val mapsGithubRepo: String = localProps.getProperty("MAPS_GITHUB_REPO", "Koncpa/offgrid-maps-data")
val mapsGithubToken: String = localProps.getProperty("MAPS_GITHUB_TOKEN", "")
val mapsCdnBaseUrl: String = localProps.getProperty(
    "MAPS_CDN_BASE_URL",
    "https://offgrid-maps-cdn.appmanufacture-dev.workers.dev"
)
val releaseStoreFilePath: String = localProps.getProperty(
    "RELEASE_STORE_FILE",
    System.getenv("RELEASE_STORE_FILE") ?: ""
)
val releaseStorePassword: String = localProps.getProperty(
    "RELEASE_STORE_PASSWORD",
    System.getenv("RELEASE_STORE_PASSWORD") ?: ""
)
val releaseKeyAlias: String = localProps.getProperty(
    "RELEASE_KEY_ALIAS",
    System.getenv("RELEASE_KEY_ALIAS") ?: ""
)
val releaseKeyPassword: String = localProps.getProperty(
    "RELEASE_KEY_PASSWORD",
    System.getenv("RELEASE_KEY_PASSWORD") ?: ""
)
val releaseKeystoreFile = if (releaseStoreFilePath.isNotBlank()) file(releaseStoreFilePath) else null
val hasReleaseSigning = releaseKeystoreFile != null &&
    releaseKeystoreFile.exists() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()

android {
    namespace = "cz.offgrid.maps"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.offgrid.maps"
        minSdk = 30 // Wear OS 3+ (Android 11)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "MAPS_GITHUB_REPO", "\"$mapsGithubRepo\"")
        buildConfigField("String", "MAPS_GITHUB_TOKEN", "\"$mapsGithubToken\"")
        buildConfigField("String", "MAPS_CDN_BASE_URL", "\"$mapsCdnBaseUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Wear OS Compose (Material & Foundation)
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // Core Android & Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")

    // Wear Tooling & UI
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-tooling-preview:1.0.0")

    // GPS & Fused Location Provider
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // MapLibre Native SDK pro offline vektorové mapy
    implementation("org.maplibre.gl:android-sdk:11.5.1")

    // Unit testy
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
