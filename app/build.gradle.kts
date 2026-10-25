plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vulkan = (project.findProperty("animaVulkan") as String?) == "true"
val vkHeaders = (project.findProperty("vkHeaders") as String?) ?: ""
val spirvPrefix = (project.findProperty("spirvPrefix") as String?) ?: ""
val spirvDir = (project.findProperty("spirvDir") as String?) ?: ""

android {
    namespace = "com.example.animalocal"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Vulkan build installs side by side with the CPU build for benchmarking.
        applicationId = "com.example.animalocal" + if (vulkan) ".vk" else ""
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANIMA_VULKAN=${if (vulkan) "ON" else "OFF"}",
                    "-DVK_HEADERS_DIR=$vkHeaders",
                )
                if (vulkan && spirvDir.isNotEmpty()) {
                    arguments += listOf("-DCMAKE_PREFIX_PATH=$spirvPrefix", "-DSPIRV-Headers_DIR=$spirvDir")
                }
            }
        }
        buildConfigField("boolean", "VULKAN", vulkan.toString())
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // sideload-only build
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
