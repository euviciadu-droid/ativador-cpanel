plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tvdigital.ativador"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tvdigital.ativador"
        // Android 5.0+ (cobre praticamente todos os dispositivos em uso)
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"

        // (filtro de locales removido — causava falha no AAPT em algumas versões do AGP)

        vectorDrawables { useSupportLibrary = true }
    }

    // Dois APKs a partir do mesmo código-fonte:
    //  - tvdigital  → Ativador TV Digital (app original, applicationId app.tvdigital.ativador)
    //  - unitvfree  → Gerador UniTvFree   (visual vermelho, applicationId app.tvdigital.unitvfree)
    flavorDimensions += "variant"
    productFlavors {
        create("tvdigital") {
            dimension = "variant"
            applicationId = "app.tvdigital.ativador"
        }
        create("unitvfree") {
            dimension = "variant"
            applicationId = "app.tvdigital.unitvfree"
        }
    }


    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            // Minifica código e remove recursos não usados -> APK muito menor
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Gera 1 APK universal que roda em qualquer arquitetura (armeabi-v7a, arm64, x86, x86_64)
    // Como não temos código nativo próprio, o APK universal continua pequeno.
    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "kotlin/**",
                "**/*.kotlin_metadata",
                "DebugProbesKt.bin"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    // org.json já vem no Android SDK — não incluir como dependência
}
