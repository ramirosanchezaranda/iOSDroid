import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ramiro.iosdroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ramiro.iosdroid"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.10"
    }

    signingConfigs {
        // Llave incluida en el repo para que cualquier build (local o CI) pueda
        // instalarse encima de la anterior sin desinstalar. Es solo para uso personal.
        create("shared") {
            storeFile = file("signing/iosdroid.jks")
            storePassword = "iosdroid"
            keyAlias = "iosdroid"
            keyPassword = "iosdroid"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.10.5")
}
