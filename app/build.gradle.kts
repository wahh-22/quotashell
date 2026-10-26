plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "dev.wahh.quotashell"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.wahh.quotashell"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Keep even the generated debug key in the checkout, not ~/.android.
    signingConfigs.getByName("debug") {
        storeFile =
            layout.buildDirectory
                .file("debug.keystore")
                .get()
                .asFile
    }
}
dependencies { implementation(project(":core")) }

// AGP only auto-generates the default home-directory debug key. Generate our
// explicitly relocated, development-only key before signing validation instead.
val localDebugKeystore = layout.buildDirectory.file("debug.keystore")
val generateLocalDebugKeystore =
    tasks.register<Exec>("generateLocalDebugKeystore") {
        outputs.file(localDebugKeystore)
        onlyIf { !localDebugKeystore.get().asFile.exists() }
        doFirst {
            localDebugKeystore
                .get()
                .asFile.parentFile
                .mkdirs()
        }
        commandLine(
            "${System.getProperty("java.home")}/bin/keytool",
            "-genkeypair",
            "-keystore",
            localDebugKeystore.get().asFile.absolutePath,
            "-storetype",
            "PKCS12",
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "androiddebugkey",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=Android Debug,O=Android,C=US",
            "-noprompt",
        )
    }
tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(generateLocalDebugKeystore)
}
