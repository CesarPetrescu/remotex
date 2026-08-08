plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release CI passes -PversionName=<tag> ("v1.2.3") on tags and
// -PversionName=nightly-<date>-<sha> on main; local builds pass nothing.
// See .github/workflows/release.yml.
val versionNameProperty: String = (findProperty("versionName") as String?)?.trim().orEmpty()
val resolvedVersionName: String = versionNameProperty.ifEmpty { "0.1.0" }

// v1.2.3 → 10203, so a newer tag always compares greater. Anything that
// isn't a semver tag (nightlies, local builds) stays at 1 — those are
// sideloaded, not upgraded in place. -PversionCode=<int> overrides.
fun versionCodeFor(name: String): Int {
    val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(name) ?: return 1
    val (major, minor, patch) = m.destructured
    return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

val resolvedVersionCode: Int =
    (findProperty("versionCode") as String?)?.trim()?.toIntOrNull()
        ?: versionCodeFor(resolvedVersionName)

android {
    namespace = "app.remotex"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.remotex"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        // Inject the relay URL at build time. Defaults to 10.0.2.2 so the
        // debug build talks to a relay running on the host machine when
        // launched in the Android emulator; override with -PrelayUrl=...
        val relayUrl = (findProperty("relayUrl") as String?)
            ?: "http://10.0.2.2:8080"
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner — used to decide whether to post the
    // "agent done" notification (skip when the app is in the foreground).
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
