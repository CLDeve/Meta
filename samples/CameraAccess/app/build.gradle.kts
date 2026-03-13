/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

import java.util.Properties
import java.io.File
import java.net.URL

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 35

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.certis.kerbside"
    minSdk = 31
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
    val localProperties =
        Properties().apply {
          generateSequence(project.projectDir) { current -> current.parentFile }
              .map { directory -> directory.resolve("local.properties") }
              .filter { candidate -> candidate.isFile }
              .toList()
              .asReversed()
              .forEach { localPropertiesFile ->
                localPropertiesFile.inputStream().use(::load)
              }
        }

    fun configValue(name: String, defaultValue: String = ""): String {
      val propValue = project.findProperty(name) as String?
      if (!propValue.isNullOrBlank()) return propValue
      val localValue =
          sequenceOf(name, name.lowercase(), name.lowercase().replace('_', '.'))
              .mapNotNull { key -> localProperties.getProperty(key)?.trim() }
              .firstOrNull { it.isNotEmpty() }
      if (!localValue.isNullOrEmpty()) return localValue
      return System.getenv(name)?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    fun asBuildConfigString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    val openAiBaseUrl = configValue("OPENAI_BASE_URL", "https://api.openai.com/v1")
    val openAiModel = configValue("OPENAI_MODEL", "gpt-4o-mini")
    val openAiApiKey = configValue("OPENAI_API_KEY")
    val commandCenterUrl = configValue("COMMAND_CENTER_URL")
    val livePovSignalingUrl =
        configValue(
            "LIVE_POV_SIGNALALING_URL",
            configValue("LIVE_POV_SIGNALING_URL", "wss://cameraaccess-live-pov.onrender.com/ws"),
        )
    val livePovRoom = configValue("LIVE_POV_ROOM", "cameraaccess")
    buildConfigField("String", "OPENAI_BASE_URL", asBuildConfigString(openAiBaseUrl))
    buildConfigField("String", "OPENAI_MODEL", asBuildConfigString(openAiModel))
    buildConfigField("String", "OPENAI_API_KEY", asBuildConfigString(openAiApiKey))
    buildConfigField("String", "COMMAND_CENTER_URL", asBuildConfigString(commandCenterUrl))
    buildConfigField("String", "LIVE_POV_SIGNALING_URL", asBuildConfigString(livePovSignalingUrl))
    buildConfigField("String", "LIVE_POV_ROOM", asBuildConfigString(livePovRoom))

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }

    externalNativeBuild { cmake { cppFlags += listOf("-O3") } }
    ndk { abiFilters += listOf("arm64-v8a") }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
  signingConfigs {
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.squareup.okhttp)
  implementation(libs.stream.webrtc.android)
  implementation(libs.tflite)
  implementation(libs.tflite.task.vision)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}

val tfliteModelUrl =
    "https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite"
val tfliteModelAsset = file("src/main/assets/efficientdet-lite0.tflite")
val downloadTfliteModel =
    tasks.register("downloadTfliteModel") {
      outputs.file(tfliteModelAsset)
      doLast {
        if (tfliteModelAsset.exists() && tfliteModelAsset.length() > 1024 * 1024) return@doLast
        tfliteModelAsset.parentFile.mkdirs()
        val tmp = File(tfliteModelAsset.parentFile, tfliteModelAsset.name + ".download")
        URL(tfliteModelUrl).openStream().use { input ->
          tmp.outputStream().use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val read = input.read(buffer)
              if (read <= 0) break
              out.write(buffer, 0, read)
            }
          }
        }
        if (tmp.length() < 1024 * 1024) {
          tmp.delete()
          error("Downloaded model file looks too small; download failed.")
        }
        tmp.renameTo(tfliteModelAsset)
      }
    }

tasks.named("preBuild").configure { dependsOn(downloadTfliteModel) }
