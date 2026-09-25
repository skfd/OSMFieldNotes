import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
}

// OSM OAuth 2 client settings live in local.properties (gitignored); see local.properties.example.
// A missing file or key leaves the client id blank and the login button explains itself.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProperty(key: String, default: String): String =
    localProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default


android {
    namespace = "com.surveyme"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.surveyme"
        minSdk = 23
        targetSdk = 35
        versionCode = 18
        versionName = "1.2.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "OSM_OAUTH_CLIENT_ID", "\"${localProperty("OSM_OAUTH_CLIENT_ID", "")}\"")
        buildConfigField("String", "OSM_OAUTH_REDIRECT_URI", "\"${localProperty("OSM_OAUTH_REDIRECT_URI", "fixmeawesome://oauth/callback")}\"")
        buildConfigField("String", "OSM_OAUTH_BASE_URL", "\"${localProperty("OSM_OAUTH_BASE_URL", "https://www.openstreetmap.org")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

// compilerOptions removed from here


    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
            }
        }
    }
}

// Room writes schemas/<db>/<version>.json so migrations can be checked against the real DDL.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android dependencies
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Material Design Components
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.7")

    // Lifecycle Components
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // OSMDroid for OpenStreetMap
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // OkHttp for network requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // OSM login: Chrome Custom Tab for the authorize page, encrypted prefs for the token
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room database
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Gson for JSON parsing (GPX tags)
    implementation("com.google.code.gson:gson:2.12.1")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Unit Testing
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
