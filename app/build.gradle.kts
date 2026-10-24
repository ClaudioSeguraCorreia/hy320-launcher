import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave de assinatura da release: keystore.properties na raiz (fora do Git).
// Sem ele, a release é assinada com a chave de debug de quem compila.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "dev.hy320.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.hy320.launcher"
        minSdk = 30
        // targetSdk 30 = mesmo comportamento do Android 11 do projetor
        targetSdk = 30
        versionCode = 17
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Fora da Play Store: targetSdk 30 é intencional (Android 11 do projetor)
        disable += "ExpiredTargetSdkVersion"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Única dependência: RecyclerView (grade com foco por D-pad, leve)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
