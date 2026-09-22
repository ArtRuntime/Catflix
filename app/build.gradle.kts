plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    signingConfigs {
        create("catflix-main") {
            storeFile =
                file("catflix.jks")
            keyAlias = "catflix-main"
            keyPassword = "catflix1111"
            storePassword = "catflix1111"
        }
    }
    namespace = "com.alex.catflix"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.alex.catflix"
        minSdk = 26
        targetSdk = 37
        multiDexEnabled = false
        versionCode = 1
        versionName = "1.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("catflix-main")
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = false
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("catflix-main")
            isMinifyEnabled = false
            isShrinkResources = false
            optimization {
                enable = false
            }
        }
    }
    packaging {
        jniLibs {
            excludes += "**/libandroidx.graphics.path.so"
        }
        resources {
            excludes += "**/libandroidx.graphics.path.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)


}