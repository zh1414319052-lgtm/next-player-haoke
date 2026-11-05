import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
}

// Keep the upstream native implementation byte-for-byte; only the Java/Kotlin renderer
// capability contract is patched locally. Never put the upstream classes.jar on the classpath.
val nextlibNative = configurations.create("nextlibNative") {
    isCanBeConsumed = false
    isTransitive = false
}
val extractNextlibNative = tasks.register<Sync>("extractNextlibNative") {
    val archive = nextlibNative.elements.map { it.single().asFile }
    inputs.file(archive)
    doFirst {
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(archive.get().readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == "998b4ff249fd3be3dfe2bf0757d47cb7cd903c4bf02a702efc4789574c679839") {
            "Unexpected Nextlib native AAR: $actual. Review the upstream source and JNI contract before updating."
        }
    }
    from(archive.map { zipTree(it) }) { include("jni/**") }
    into(layout.buildDirectory.dir("upstreamNative"))
}

android {
    namespace = "io.github.anilbeesetti.nextlib.media3ext"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}
kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addStaticSourceDirectory(layout.buildDirectory.dir("upstreamNative/jni").get().asFile.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(extractNextlibNative) }

dependencies {
    add(nextlibNative.name, "io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0@aar")
    api(libs.androidx.media3.exoplayer)
    implementation("androidx.annotation:annotation:1.10.0")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
