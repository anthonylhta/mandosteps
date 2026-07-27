plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The ingest bearer is provided as an env var by build.sh (mirroring the hub's
// steps.sh) and baked into BuildConfig — it never appears in source or git.
val ingestSecret: String =
    System.getenv("STEPS_INGEST_SECRET")?.takeIf { it.isNotBlank() }
        ?: error("STEPS_INGEST_SECRET not set — build via ./build.sh")

android {
    namespace = "dev.anthonyta.mandosteps"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anthonyta.mandosteps"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        val escaped = ingestSecret.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "INGEST_SECRET", "\"$escaped\"")
        buildConfigField("String", "INGEST_URL", "\"https://anthonyta.dev/api/daily/steps\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
