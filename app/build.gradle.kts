import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.room2repair.app"
  compileSdk = 36

  defaultConfig {
    // Different ID from the first app, so both can be installed side by side.
    applicationId = "com.room2repair.app2"
    minSdk = 31
    targetSdk = 36
    versionCode = 4
    versionName = "1.4"

    // Meta Wearables Device Access Toolkit: "0" works while Developer Mode is on in the Meta AI
    // app. For a published app, use the IDs from the Wearables Developer Center.
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
    // Name under the icon, and the link Meta AI uses to come back to this app.
    manifestPlaceholders["appLabel"] = "Room2Repair 2"
    manifestPlaceholders["appScheme"] = "room2repair2"
  }

  // A fixed key kept in the repo, so every GitHub build is signed the same way and
  // installs as an update over the previous one (no uninstall, data kept). Demo use only.
  signingConfigs {
    create("demo") {
      storeFile = file("room2repair.keystore")
      storePassword = "room2repair"
      keyAlias = "room2repair"
      keyPassword = "room2repair"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("demo")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.speech)
  implementation(libs.mwdat.inputs)
}
