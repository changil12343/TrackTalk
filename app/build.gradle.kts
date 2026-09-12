import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? {
    val localValue = releaseSigningProperties
        .getProperty(propertyName)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return localValue ?: providers
        .environmentVariable(environmentName)
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

val releaseStoreFilePath = releaseSigningValue(
    propertyName = "storeFile",
    environmentName = "TRACKTALK_UPLOAD_STORE_FILE",
)
val releaseStorePassword = releaseSigningValue(
    propertyName = "storePassword",
    environmentName = "TRACKTALK_UPLOAD_STORE_PASSWORD",
)
val releaseKeyAlias = releaseSigningValue(
    propertyName = "keyAlias",
    environmentName = "TRACKTALK_UPLOAD_KEY_ALIAS",
)
val releaseKeyPassword = releaseSigningValue(
    propertyName = "keyPassword",
    environmentName = "TRACKTALK_UPLOAD_KEY_PASSWORD",
)
val releaseSigningValues = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningInputPresent =
    releaseSigningPropertiesFile.isFile || releaseSigningValues.any { it != null }
val releaseSigningConfigured = releaseSigningValues.all { it != null }
val releaseSigningRequirement = providers
    .gradleProperty("tracktalk.requireReleaseSigning")
    .orNull
    ?.trim()
    ?.lowercase()
val requireReleaseSigning = when (releaseSigningRequirement) {
    null, "", "false" -> false
    "true" -> true
    else -> throw GradleException(
        "tracktalk.requireReleaseSigning must be either true or false.",
    )
}

// The release operator supplies the public, non-geofenced policy URL. Empty
// is intentionally visible in-app as an incomplete release configuration.
val privacyPolicyUrl = providers
    .gradleProperty("tracktalk.privacyPolicyUrl")
    .orNull
    ?.trim()
    .orEmpty()
if (privacyPolicyUrl.isNotEmpty() && !privacyPolicyUrl.startsWith("https://")) {
    throw GradleException("tracktalk.privacyPolicyUrl must use https://.")
}
val escapedPrivacyPolicyUrl = privacyPolicyUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

if (releaseSigningInputPresent && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing credentials are incomplete. Provide storeFile, " +
            "storePassword, keyAlias, and keyPassword in keystore.properties " +
            "or the TRACKTALK_UPLOAD_* environment variables.",
    )
}
if (requireReleaseSigning && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing was required, but no complete upload-key credentials " +
            "were provided.",
    )
}

val releaseSigningStoreFile = releaseStoreFilePath?.let { rootProject.file(it) }
if (releaseSigningConfigured && releaseSigningStoreFile?.isFile != true) {
    throw GradleException("The configured release-signing keystore does not exist.")
}

android {
    namespace = "com.trackvoice"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trackvoice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$escapedPrivacyPolicyUrl\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = checkNotNull(releaseSigningStoreFile)
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("com.android.billingclient:billing:9.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
