group = "de.noctunix.obdbridge"
version = "1.0-SNAPSHOT"

buildscript {
    val kotlinVersion = "2.4.10"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.3.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "de.noctunix.obdbridge"

    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests {
            // Robolectric shadows `android.jar`'s framework stubs (notably `org.json.*`,
            // which throw "not mocked" under the plain JVM), needed by `CxBufferWriterTest`/
            // `CxCommandSessionTest`/`CxRecordingRestartMarkerTest`/`ObdApiTest`.
            isIncludeAndroidResources = true
            all {
                it.outputs.upToDateWhen { false }

                it.testLogging {
                    events("passed", "skipped", "failed", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // no.nordicsemi.android:ble 2.11.0 — BSD-3-Clause, latest stable release on Maven
    // Central; the GATT connect/bond/MTU/notification queue CxBleClient.kt builds on.
    // The licence text ships as an asset — see NOTICE.
    implementation("no.nordicsemi.android:ble:2.11.0")

    // Test-only, never bundled:
    //   • junit:junit — EPL-1.0.
    //   • org.robolectric:robolectric — MIT. See the `isIncludeAndroidResources` comment
    //     above for why it is needed.
    //   • androidx.test:core — Apache-2.0. Supplies `ApplicationProvider` for a real
    //     `Context` in `CxRecordingRestartMarkerTest`/`ObdApiTest`.
    //   • org.json:json — the JSON implementation Robolectric's shadow otherwise supplies;
    //     declared explicitly so `JSONObject`/`JSONArray` resolve at compile time too.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.json:json:20231013")
}
