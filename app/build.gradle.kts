plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.serialization)
}

android {
    val packageName = "io.github.ancientcatz.lime"
    namespace = packageName
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = packageName
        minSdk = 30
        targetSdk = 33
        versionCode = 6
        versionName = "1.4.0"
    }

    buildTypes {
        debug {
            // Standard Android debug signing is used for the debug build.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            // Release signing is driven by environment variables so that CI can sign with a
            // temporary keystore stored in GitHub Actions secrets, and local builds without
            // those env vars produce an unsigned release APK (still useful for local validation).
            // We treat both null AND empty-string as "not set" so a workflow that exposes the
            // secret name but not its value (e.g. a pull_request run) falls back to unsigned.
            val keystoreFilePath = providers.environmentVariable("LIME_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
            val keystorePwd = providers.environmentVariable("LIME_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
            val keyAliasName = providers.environmentVariable("LIME_KEY_ALIAS").orNull?.takeIf { it.isNotEmpty() }
            val keyPwd = providers.environmentVariable("LIME_KEY_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
            if (keystoreFilePath != null && keystorePwd != null && keyAliasName != null && keyPwd != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystoreFilePath)
                    storePassword = keystorePwd
                    keyAlias = keyAliasName
                    keyPassword = keyPwd
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }
        }
    }

    buildFeatures {
        viewBinding = false
        // BuildConfig is used by LimeModule (BuildConfig.APPLICATION_ID) and SettingFragment
        // (BuildConfig.VERSION_NAME). AGP 9.x defaults buildConfig to false, so we opt in.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // AGP 9.x has built-in Kotlin support (no explicit kotlin("android") plugin needed).
    // Kotlin compiler options are configured via the top-level `kotlin` block below.

    packaging {
        resources {
            // The libxposed module declaration files under META-INF/xposed/ must be merged
            // (not deduplicated away) so the framework can discover the entry class, module.prop,
            // and scope.list inside the APK.
            merges += "META-INF/xposed/*"
            // Exclude common duplicate metadata files that would otherwise break the release build.
            excludes += "**/NOTICE*"
            excludes += "**/LICENSE*"
            excludes += "**/DEPENDENCIES"
            excludes += "**/*.kotlin_module"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Lime needs to enumerate all installed apps so the user can pick one and configure
        // its orientation. QUERY_ALL_PACKAGES is the only way to do this on Android 11+.
        disable += "GoogleAppIndexingWarning"
        disable += "Instantiatable"
    }
}

dependencies {
    // Modern libxposed API 102 — compileOnly because the framework provides the implementation
    // at runtime. Packaging the API jar would create a duplicate runtime implementation.
    compileOnly(libs.libxposed.api)
    // Modern libxposed service — implementation because this is a real runtime dependency used by
    // the module's settings app to talk to the framework (XposedServiceHelper).
    implementation(libs.libxposed.service)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
}
