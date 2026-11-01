plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Wie ein Android-Tablet mit largeHeap: Speichergrenze für Stresstests
    maxHeapSize = "512m"
    exclude("**/memory/**")
    // Große Objekte ohne Verschnitt ablegen, ähnlich wie Androids Large-Object-Space
    jvmArgs("-XX:G1HeapRegionSize=4m")
    // Kontaktblatt der Filtergalerie (GalleryTest.contactSheet): PG_GALLERY_SHEET=bild.jpg:blatt.png
    environment("PG_GALLERY_SHEET", System.getenv("PG_GALLERY_SHEET") ?: "")
}

// Speicher-Sperre: Pixel im Kachelspeicher außerhalb des Heaps, Java-Heap bewusst klein.
// Eine Funktion, die ein ganzes Bild in den Java-Speicher lädt, lässt diesen Test scheitern.
val memoryTest by tasks.registering(Test::class) {
    description = "Speicher-Stresstest mit 192 MB Java-Heap und nativem Kachelspeicher"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/memory/**")
    maxHeapSize = "192m"
    jvmArgs("-XX:MaxDirectMemorySize=3g", "-XX:G1HeapRegionSize=4m")
    testLogging { showStandardStreams = true; events("failed") }
}
tasks.named("check") { dependsOn(memoryTest) }

// Kompatibilitätsprüfung mit einer eigenen PSD/TIFF-Sammlung (liest die Dateien nur):
//   ./gradlew :core:psdCheck -PpsdDir=/pfad/zum/archiv [-PpsdOut=ordner] [-PpsdRoundtrip=false]
val psdCheck by tasks.registering(JavaExec::class) {
    description = "Vergleicht alle PSDs eines Ordners mit dem in der Datei gespeicherten Photoshop-Bild"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("de.photographics.core.tools.PsdArchiveCheckKt")
    args(
        providers.gradleProperty("psdDir").getOrElse(""),
        providers.gradleProperty("psdOut").getOrElse(layout.buildDirectory.dir("psd-check").get().asFile.path),
        providers.gradleProperty("psdRoundtrip").getOrElse("true"),
    )
    maxHeapSize = "2g"
    jvmArgs("-XX:MaxDirectMemorySize=8g", "-XX:G1HeapRegionSize=4m")
}
