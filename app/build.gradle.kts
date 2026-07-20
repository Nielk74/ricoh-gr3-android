import java.io.FileOutputStream
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Resolve a build parameter from either an environment variable or a Gradle
 * property (env wins). Returns null when neither is set or the value is blank.
 */
fun buildParam(name: String): String? =
    (System.getenv(name) ?: (project.findProperty(name) as String?))?.takeIf { it.isNotBlank() }

/**
 * Materialise the release keystore, if any. Supports two mechanisms:
 *   - KEYSTORE_B64:  base64-encoded keystore, decoded to a temp file.
 *   - KEYSTORE_FILE: path to an existing keystore file.
 * Returns null when no keystore material is provided (local dev without secrets).
 */
fun resolveKeystoreFile(): File? {
    buildParam("KEYSTORE_FILE")?.let { path ->
        val f = File(path)
        if (f.exists()) return f
    }
    buildParam("KEYSTORE_B64")?.let { b64 ->
        val out = File(layout.buildDirectory.get().asFile, "release-keystore.jks")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { it.write(Base64.getDecoder().decode(b64.trim())) }
        return out
    }
    return null
}

android {
    namespace = "com.ricohgr3.app"
    compileSdk = 34
    val githubRepo = buildParam("GITHUB_REPO") ?: "Nielk74/ricoh-gr3-android"

    defaultConfig {
        applicationId = "com.ricohgr3.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (buildParam("VERSION_CODE")?.toIntOrNull()) ?: 1
        // Keep the local fallback aligned with the newest published tag. Release
        // builds override it from the tag in .github/workflows/release.yml.
        versionName = buildParam("VERSION_NAME") ?: "0.8.1"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    // Optional release signing. Only wired up when a keystore and all
    // credentials are present; otherwise the release build is left unsigned
    // so contributors without secrets can still run `./gradlew assembleRelease`
    // locally.
    val releaseKeystore = resolveKeystoreFile()
    val storePasswordParam = buildParam("STORE_PASSWORD")
    val keyAliasParam = buildParam("KEY_ALIAS")
    val keyPasswordParam = buildParam("KEY_PASSWORD")
    val hasReleaseSigning = releaseKeystore != null &&
        storePasswordParam != null &&
        keyAliasParam != null &&
        keyPasswordParam != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePasswordParam
                keyAlias = keyAliasParam
                keyPassword = keyPasswordParam
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    // Persistence for the sticky last-used look (Phase 6c / 7.1).
    implementation(libs.androidx.datastore.preferences)

    // Bundled on-device model: portrait rendering must not depend on a first-run download.
    implementation(libs.mlkit.face.detection)

    // Wi-Fi HTTP /v1 layer: OkHttp transport + kotlinx.serialization JSON parsing.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
