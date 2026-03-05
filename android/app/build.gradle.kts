plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android") apply false
}

if (extensions.findByName("kotlin") == null) {
  apply(plugin = "org.jetbrains.kotlin.android")
}

android {
  namespace = "com.pjarczak.bmcuflasher"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.pjarczak.bmcuflasher"
    minSdk = 21
    targetSdk = 35
    versionCode = 121
    versionName = "1.2.1"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    viewBinding = true
  }

  sourceSets {
    getByName("main") {
      assets.srcDirs("../../i18n")
    }
  }
}

dependencies {
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
}
