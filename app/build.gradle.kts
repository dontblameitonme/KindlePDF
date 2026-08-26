plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kindle.converter"
    compileSdk = 34

    val keystoreProps: Map<String, String>? = run {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) {
            f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it[0] != '#' && it.contains('=') }
                .associate { line ->
                    val i = line.indexOf('=')
                    line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
        } else {
            null
        }
    }

    signingConfigs {
        create("release") {
            keystoreProps?.let { props ->
                storeFile = file(props["storeFile"]!!)
                storePassword = props["storePassword"]
                keyAlias = props["keyAlias"]
                keyPassword = props["keyPassword"]
            }
        }
    }

    defaultConfig {
        applicationId = "com.kindle.converter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Universal single APK that supports both 32-bit (armeabi-v7a) and
    // 64-bit (arm64-v8a) devices. App has no native libs, so size is unchanged.
    splits {
        abi {
            isEnable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // PDF generation — PdfBox-Android (Apache 2.0, no AWT dependency)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown parsing
    implementation("org.commonmark:commonmark:0.22.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
