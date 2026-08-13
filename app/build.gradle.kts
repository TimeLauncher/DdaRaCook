import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun configurationValue(name: String, defaultValue: String = ""): String =
    providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name)
        ?: defaultValue

fun localTeamToken(): String {
    val tokenFile = rootProject.file("server/서버 토큰.md")
    if (!tokenFile.isFile) return ""
    return Regex("cookassist-[A-Za-z0-9_-]+")
        .find(tokenFile.readText())
        ?.value
        .orEmpty()
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        // DAT 0.9.0 AARs declare minSdk 29. The verified CameraAccess sample itself uses 31.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "JUDGE_BASE_URL",
            configurationValue(
                name = "JUDGE_BASE_URL",
                defaultValue = "https://ddaracook-server.onrender.com"
            ).asBuildConfigString()
        )
        buildConfigField(
            "String",
            "JUDGE_TEAM_TOKEN",
            configurationValue(name = "TEAM_TOKEN", defaultValue = localTeamToken()).asBuildConfigString()
        )
        buildConfigField(
            "boolean",
            "USE_FAKE_CAMERA",
            configurationValue(name = "USE_FAKE_CAMERA", defaultValue = "false")
                .toBooleanStrictOrNull()
                ?.toString()
                ?: "false"
        )

        // Official DAT Developer Mode sentinel. Production credentials must be supplied through
        // non-tracked configuration before a non-Developer-Mode release.
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.vosk.android)
    implementation(libs.jna.android) {
        artifact {
            type = "aar"
        }
    }
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
