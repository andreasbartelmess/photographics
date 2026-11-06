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

val lwjglNatives = "natives-linux"

dependencies {
    implementation(project(":core"))
    api(libs.lwjgl)
    api(libs.lwjgl.egl)
    api(libs.lwjgl.opengles)
    runtimeOnly(variantOf(libs.lwjgl) { classifier(lwjglNatives) })
    runtimeOnly(variantOf(libs.lwjgl.opengles) { classifier(lwjglNatives) })
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "512m"
    testLogging { showStandardStreams = true; events("failed") }
}
