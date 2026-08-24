@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.1.0"
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.baseline.profile)
    alias(libs.plugins.compose.compiler)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { 
        localProperties.load(it)
    }
}

android {
    namespace = "com.kuromusic"
    //noinspection GradleDependency
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kuromusic"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GOOGLE_API_KEY", "\"${localProperties.getProperty("GOOGLE_API_KEY") ?: "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"}\"")
        buildConfigField("String", "PO_TOKEN_REQUEST_KEY", "\"${localProperties.getProperty("PO_TOKEN_REQUEST_KEY") ?: "O43z0dpjhgX20SCx4KAo"}\"")
        buildConfigField("String", "INNER_TUBE_API_KEY", "\"${localProperties.getProperty("INNER_TUBE_API_KEY") ?: ""}\"")
        buildConfigField("String", "YOUTUBE_SESSION_COOKIES", "\"${localProperties.getProperty("YOUTUBE_SESSION_COOKIES") ?: ""}\"")
        buildConfigField("long", "DISCORD_APP_ID", "${localProperties.getProperty("DISCORD_APP_ID") ?: "1411019391843172514"}L")
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProperties.getProperty("LASTFM_API_KEY") ?: ""}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${localProperties.getProperty("LASTFM_SECRET") ?: ""}\"")
    }

    signingConfigs {
        getByName("debug") {
            if (System.getenv("MUSIC_DEBUG_SIGNING_STORE_PASSWORD") != null) {
                storeFile = file(System.getenv("MUSIC_DEBUG_KEYSTORE_FILE"))
                storePassword = System.getenv("MUSIC_DEBUG_SIGNING_STORE_PASSWORD")
                keyAlias = "debug"
                keyPassword = System.getenv("MUSIC_DEBUG_SIGNING_KEY_PASSWORD")
            }
        }
        create("release") {
            val keystoreFile = localProperties.getProperty("RELEASE_KEYSTORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD") ?: ""
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
        }
    }

    applicationVariants.configureEach {
        outputs.configureEach {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "KuroMusic.apk"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // ✅ Alineamos TODO a Java 17
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    baselineProfile {
        mergeIntoMain = true
        filter {
            include("com.kuromusic.**")
        }
    }

    lint {
        disable += "MissingTranslation"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.navigation)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.material3)
    implementation(libs.palette)
    implementation(projects.materialColorUtilities)

    implementation(libs.coil)
    implementation(libs.shimmer)

    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.squigglyslider)

    implementation(libs.room.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.blurry)
    implementation(libs.material.ripple)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.material.icons.extended)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.graphics.shapes)
    implementation(libs.work.runtime.ktx)
    implementation(libs.constraintlayout)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    implementation(libs.jsoup)
    ksp(libs.hilt.compiler)

    implementation(projects.innertube)
    implementation(projects.kugou)
    implementation(projects.lrclib)
    implementation(projects.kizzy)
    implementation(project(":jossredconnect"))
    implementation(projects.lastfm)
    implementation(projects.canvas)
    baselineProfile(project(":baselineprofile"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation("androidx.browser:browser:1.8.0")

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)

    constraints {
        implementation("io.netty:netty-handler:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-codec-http:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-codec-http2:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-resolver-dns:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-codec:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-common:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-handler-proxy:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-codec-dns:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-resolver:4.1.135.Final") { because("CVE fixes") }
        implementation("io.netty:netty-buffer:4.1.135.Final") { because("CVE fixes") }
        implementation("com.fasterxml.jackson.core:jackson-core:2.18.3") { because("CVE fixes") }
        implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3") { because("CVE fixes") }
    }
}
