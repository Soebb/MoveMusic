plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.movemusic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.movemusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            // Experimental release signing only. This uses the machine-generated
            // debug key and is not suitable for stable public releases.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("io.coil-kt.coil3:coil:3.0.4")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.10.1")
}
