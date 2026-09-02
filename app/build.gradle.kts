plugins { id("com.android.application") }

val releaseStoreFile = providers.environmentVariable("PIXELPILL_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("PIXELPILL_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("PIXELPILL_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("PIXELPILL_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "io.github.pixelpill.motion"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.pixelpill.motion"
        minSdk = 33
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.1-rc1"
    }
    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null
            && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "BUILD_CHANNEL", "\"debug\"")
            buildConfigField("boolean", "VERBOSE_HOOK_LOGS", "true")
        }
        release {
            buildConfigField("String", "BUILD_CHANNEL", "\"release-candidate\"")
            buildConfigField("boolean", "VERBOSE_HOOK_LOGS", "false")
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*") }
    lint {
        abortOnError = true
        warningsAsErrors = false
        // Release stability takes precedence over automatic dependency churn.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
}
