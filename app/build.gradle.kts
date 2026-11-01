import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// F-Droid baut eine APK je Architektur: -Pphotographics.abi=arm64-v8a (bzw. armeabi-v7a, x86_64).
// Dann eigener versionCode je Architektur (10 × Basis + 1/2/3) und unsigniert (F-Droid signiert selbst).
val singleAbi: String? = providers.gradleProperty("photographics.abi").orNull
val abis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val baseVersionCode = 3
require(singleAbi == null || singleAbi in abis) { "photographics.abi muss eine von $abis sein" }

android {
    namespace = "de.photographics.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.photographics.app"
        minSdk = 27 // SharedMemory für den nativen Kachelspeicher
        targetSdk = 36
        versionCode = if (singleAbi == null) baseVersionCode else baseVersionCode * 10 + abis.indexOf(singleAbi) + 1
        versionName = "0.4.0"
        // Adresse des Quellcodes (in gradle.properties: photographics.sourceUrl=https://github.com/…)
        buildConfigField("String", "SOURCE_URL", "\"${providers.gradleProperty("photographics.sourceUrl").getOrElse("")}\"")

    }

    // Eine APK je Prozessorarchitektur (deutlich kleiner) plus eine universelle
    splits {
        abi {
            isEnable = true
            reset()
            if (singleAbi != null) include(singleAbi) else include(*abis.toTypedArray())
            isUniversalApk = singleAbi == null
        }
    }

    // Release-Schlüssel: aus keystore.properties (lokal, nicht im Repository) oder aus
    // Umgebungsvariablen (GitHub Actions). Ohne Schlüssel wird mit dem Debug-Schlüssel signiert.
    val releaseKey = releaseKeyConfig()
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getValue("storeFile"))
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKey != null) signingConfigs.getByName("release") else if (singleAbi != null) null else {
                logger.warn("Kein Release-Schlüssel (keystore.properties) – Release wird mit dem Debug-Schlüssel signiert.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/** Schlüsseldaten: keystore.properties im Projektordner, sonst PG_KEYSTORE_FILE, PG_KEYSTORE_PASSWORD, PG_KEY_ALIAS, PG_KEY_PASSWORD. */
fun releaseKeyConfig(): Map<String, String>? {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        val p = Properties().apply { f.inputStream().use { load(it) } }
        val m = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { p.getProperty(it).orEmpty() }
        return m.takeIf { it.values.all(String::isNotEmpty) }?.let { it + ("storeFile" to rootProject.file(it.getValue("storeFile")).path) }
    }
    val env = mapOf(
        "storeFile" to System.getenv("PG_KEYSTORE_FILE"), "storePassword" to System.getenv("PG_KEYSTORE_PASSWORD"),
        "keyAlias" to System.getenv("PG_KEY_ALIAS"), "keyPassword" to System.getenv("PG_KEY_PASSWORD"),
    )
    return if (env.values.all { !it.isNullOrEmpty() }) env.mapValues { it.value!! } else null
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    // Modell-Downloads: baut IPv4 und IPv6 parallel auf (kaputtes IPv6 kostet sonst Minuten)
    implementation(libs.okhttp)
    implementation(libs.androidx.print)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
}
