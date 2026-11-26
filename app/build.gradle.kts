plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.p4z8n1vr.openiv"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.p4z8n1vr.openiv"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
