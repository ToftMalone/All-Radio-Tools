plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.allnetworktools"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.allnetworktools"
        minSdk = 31
        targetSdk = 37
        versionCode = 41
        versionName = "1.6"
    }

    buildTypes {
        // Installs next to the published app: its own package and name ("All Radio Tools Debug", src/debug/res).
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Same as debug for the package and name, but shrunk like the release: small enough to share.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets["preview"].res.directories += "src/debug/res"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.path)
                // Plain JVM tests read the French texts (Robolectric ones get them from their locale qualifier).
                it.systemProperty("ant.strings", file("src/main/res/values-fr").path)
                it.jvmArgs("-Duser.language=fr", "-Duser.country=FR")
            }
        }
    }
    androidResources {
        // Lists the app's languages for Android 13+'s per-app language setting.
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material.components)
    implementation(libs.osmdroid)
    implementation(libs.usb.serial)
    implementation(libs.arcore)
    implementation(libs.bouncycastle)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
