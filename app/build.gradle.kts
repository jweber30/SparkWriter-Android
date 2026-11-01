import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "net.metalstrapper.sparkwriter"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.metalstrapper.sparkwriter"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // return_delivery, Mailroom, field fetch, and prefill buttons are off for the
        // initial release. Development builds can opt in with -Psparkwriter.externalServices=true.
        val externalServices = providers.gradleProperty("sparkwriter.externalServices").orNull?.toBoolean() ?: false
        buildConfigField("boolean", "EXTERNAL_SERVICES_ENABLED", externalServices.toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        named("main") {
            assets.srcDir("bundled")
        }
    }

    // Upload-key signing for Play. keystore.properties is gitignored; without it the
    // release build is left unsigned so debug and CI builds still work.
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":manifest-runtime"))
    implementation(project(":usb-writer"))
    implementation(project(":storage"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.okhttp)
    implementation(libs.commons.codec)
    implementation(libs.androidx.appfunctions)
    implementation(libs.androidx.appfunctions.service)
    implementation(libs.guava)
    ksp(libs.androidx.appfunctions.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(project(":shared-test-fixtures"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    // The app module is where AppFunction metadata from all modules is collected.
    arg("appfunctions:aggregateAppFunctions", "true")
}
