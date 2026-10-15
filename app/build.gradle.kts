import java.util.Base64
import java.util.Properties
import java.io.FileInputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.jalaluddin.focusshield"
    minSdk = 24
    targetSdk = 36
    versionCode = 15
    versionName = "15"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystoreBase64 = System.getenv("RELEASE_KEYSTORE_BASE64")
      val keystoreFilePath = System.getenv("RELEASE_STORE_FILE")
      val storePasswordEnv = System.getenv("RELEASE_STORE_PASSWORD")
      val keyAliasEnv = System.getenv("RELEASE_KEY_ALIAS")
      val keyPasswordEnv = System.getenv("RELEASE_KEY_PASSWORD")

      val resolvedFile = when {
        !keystoreFilePath.isNullOrBlank() -> file(keystoreFilePath.trim().removeSurrounding("\"").removeSurrounding("'"))
        !keystoreBase64.isNullOrBlank() -> {
          try {
            val decodedFile = file("${layout.buildDirectory.asFile.get().absolutePath}/release-signing/release-keystore.jks")
            decodedFile.parentFile?.mkdirs()
            val cleanBase64 = keystoreBase64.trim().removeSurrounding("\"").removeSurrounding("'").replace("\\s".toRegex(), "")
            if (cleanBase64.isNotEmpty()) {
              decodedFile.writeBytes(Base64.getDecoder().decode(cleanBase64))
              decodedFile
            } else null
          } catch (e: Exception) {
            logger.warn("Failed to decode RELEASE_KEYSTORE_BASE64: ${e.message}")
            null
          }
        }
        file("${rootDir}/.signing/focusshield-release.keystore").exists() -> file("${rootDir}/.signing/focusshield-release.keystore")
        file("${rootDir}/release-upload.jks").exists() -> file("${rootDir}/release-upload.jks")
        else -> null
      }

      val localCredsFile = file("${rootDir}/.signing/signing_credentials.env")
      val localProps = if (localCredsFile.exists()) {
        val props = Properties()
        FileInputStream(localCredsFile).use { props.load(it) }
        props
      } else null

      val effectiveStorePassword = if (!storePasswordEnv.isNullOrBlank()) {
        storePasswordEnv.trim().removeSurrounding("\"").removeSurrounding("'")
      } else localProps?.getProperty("RELEASE_STORE_PASSWORD")

      val effectiveKeyAlias = when {
        !keyAliasEnv.isNullOrBlank() -> keyAliasEnv.trim().removeSurrounding("\"").removeSurrounding("'")
        !localProps?.getProperty("RELEASE_KEY_ALIAS").isNullOrBlank() -> localProps?.getProperty("RELEASE_KEY_ALIAS")
        else -> "focusshield"
      }

      val effectiveKeyPassword = when {
        !keyPasswordEnv.isNullOrBlank() -> keyPasswordEnv.trim().removeSurrounding("\"").removeSurrounding("'")
        !localProps?.getProperty("RELEASE_KEY_PASSWORD").isNullOrBlank() -> localProps?.getProperty("RELEASE_KEY_PASSWORD")
        else -> effectiveStorePassword
      }

      val isReleaseSigningReady = resolvedFile != null && !effectiveStorePassword.isNullOrBlank()
      if (isReleaseSigningReady) {
        storeFile = resolvedFile
        storePassword = effectiveStorePassword
        keyAlias = effectiveKeyAlias
        keyPassword = effectiveKeyPassword
      } else {
        // Fallback to debug keystore for local building when release keystore credentials are not provided
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

// Stripped unneeded heavy libraries for maximum performance, lightweight APK, and instant startup
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  // androidTest dependencies disabled to optimize APK size and build speed
  // androidTestImplementation(platform(libs.androidx.compose.bom))
  // androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  // androidTestImplementation(libs.androidx.espresso.core)
  // androidTestImplementation(libs.androidx.junit)
  // androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
