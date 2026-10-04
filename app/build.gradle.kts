import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing config is loaded from app/keystore.properties, which is
// gitignored and NOT included in this project -- see README "Signing" section
// for how to generate your own keystore and fill this in. Falls back to
// unsigned release builds if the file isn't present, so the project still
// builds out of the box.
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
val hasSigningConfig = keystorePropertiesFile.exists()
if (hasSigningConfig) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.devcat.escootercontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devcat.escootercontrol"
        // Android 6.0 is the practical floor: BLE central APIs are available and
        // runtime permission handling is consistent from API 23 onward.
        minSdk = 23
        targetSdk = 36
        versionCode = 14
        versionName = "0.2.12"
    }

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Release performance matters for Compose. R8 removes dead code and performs
            // bytecode optimizations that are intentionally absent from debug builds.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Without keystore.properties present, this build type falls back to
            // no signing config at all -- `assembleRelease` will produce an
            // unsigned APK you'd need to sign manually. Add keystore.properties
            // to get a properly signed release build directly from Gradle.
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
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.6")

    // Stay on Haze 1.6.x for this Kotlin 2.1 / compileSdk 36 project. Haze 1.7
    // moved to Kotlin 2.2.x; 2.x moves to the newer Compose/SDK line. Blur is
    // intentionally limited to compact controls and tiles.
    implementation("dev.chrisbanes.haze:haze:1.6.10")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
