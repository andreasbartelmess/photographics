plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// java-gi spricht GTK über die Foreign-Function-API an (java-gi 1.0 setzt Java 25 voraus)
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

kotlin {
    jvmToolchain(25)
    // Kotlin 2.2 kennt als Ziel höchstens Java 24; der Code läuft trotzdem auf dem JDK 25
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(24) }
// java-gi wird nur für Java 25 veröffentlicht – Auflösung entsprechend anfragen
for (c in listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")) {
    configurations.named(c) { attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.javagi.adw)
    // KI-Modelle (Inpainting, Hochskalieren) – dieselbe Java-Schnittstelle wie onnxruntime-android
    implementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    jvmArgs(application.applicationDefaultJvmArgs)
    // Optionaler KI-Test: PG_AI_TEST=1 XDG_DATA_HOME=/tmp/… ./gradlew :gnome:test
    environment("PG_AI_TEST", System.getenv("PG_AI_TEST") ?: "")
    System.getenv("XDG_DATA_HOME")?.let { environment("XDG_DATA_HOME", it) }
    testLogging { showStandardStreams = true }
}

application {
    mainClass.set("de.photographics.gnome.MainKt")
    applicationName = "photographics"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        // Fedora/openSUSE legen die Bibliotheken nach /usr/lib64, Debian/Ubuntu nach /usr/lib/<triplet>
        "-Djava.library.path=/usr/lib64:/usr/lib:/usr/lib/x86_64-linux-gnu:/usr/lib/aarch64-linux-gnu",
        // Bildpixel liegen außerhalb des Java-Heaps (Kachelspeicher, siehe Main.kt)
        "-XX:MaxDirectMemorySize=16g",
        "-XX:G1HeapRegionSize=4m",
    )
}

// ./gradlew :gnome:run --args="bild.psd"; Umgebungsvariablen (z. B. PG_SCREENSHOT) werden übernommen
tasks.named<JavaExec>("run") {
    jvmArgs(application.applicationDefaultJvmArgs)
    workingDir = rootDir
}
