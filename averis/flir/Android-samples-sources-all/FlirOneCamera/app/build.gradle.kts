import org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper

plugins {
    alias(libs.plugins.android.application)
    // mention the kotlin plugin for build purposes (see note below), but don't apply it to app that is not using Kotlin
    alias(libs.plugins.kotlin.android) apply false
}

// required to workaround build issue on CI (duplicate class kotlin...) when building non-Kotlin sample apps
// all apps that use Kotlin have 'kotlin-android' plugin applied already, but plain-Java apps need this workaround to be built on CI
// KotlinAndroidPluginWrapper is translated into plugin with id == "org.jetbrains.kotlin.android" as defined here:
// https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/build.gradle.kts
apply<KotlinAndroidPluginWrapper>()

android {
    namespace = "com.samples.flironecamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.samples.flironecamera"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildToolsVersion = "36.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    repositories {
        // default path where thermalsdk AAR is stored (for CI build)
        flatDir {
            dirs = setOf(File("../../../modules/thermalsdk/build/outputs/aar"))
        }
        // default path where androidsdk AAR is stored (for CI build)
        flatDir {
            dirs = setOf(File("../../../modules/androidsdk/build/outputs/aar"))
        }
        flatDir {
            dirs = setOf(File("libs"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.jetbrains.annotations)

    implementation("", name = "androidsdk-release", ext = "aar")
    implementation("", name = "thermalsdk-release", ext = "aar")
}

