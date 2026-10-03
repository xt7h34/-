plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// The icon and the signing key are stored as text (so they can be pasted from a phone)
// and decoded back into real files here, at build time.
fun decodeBase64File(root: File, source: String, target: File) {
    val src = File(root, source)
    if (src.exists()) {
        target.parentFile.mkdirs()
        target.writeBytes(java.util.Base64.getMimeDecoder().decode(src.readText().trim()))
    }
}

val debugKeystoreFile = File(layout.buildDirectory.get().asFile, "debug.keystore")
decodeBase64File(rootDir, "debug.keystore.b64", debugKeystoreFile)
decodeBase64File(rootDir, "icon.b64", File(rootDir, "src/main/res/drawable-nodpi/ic_launcher_foreground.webp"))

android {
    namespace = "com.rigen.volumeui"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kanade"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // Fixed debug key: every build is signed the same way, so a new APK installs over the old one.
    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
}
