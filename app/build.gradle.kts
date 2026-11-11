plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "pl.bateria"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.bateria"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$runNumber"
        // wersja do udostępniania (-Ppublic=true): bez „Moich aplikacji”, aktualizacje z publicznego repozytorium
        val pub = (project.findProperty("public") ?: "false").toString() == "true"
        buildConfigField("boolean", "PUBLIC", pub.toString())
        buildConfigField("String", "REPO", if (pub) "\"Bartoszelek/bastion\"" else "\"Bartoszelek/bateria\"")
    }

    signingConfigs {
        create("main") {
            storeFile = file("bateria.keystore")
            storePassword = "bateria123"
            keyAlias = "bateria"
            keyPassword = "bateria123"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("main") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("main")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // filtry Blooma z bazami sygnatur antywirusa (format Hypatia / LoveLaceAV)
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
