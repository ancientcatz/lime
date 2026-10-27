plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.serialization)
}

android {
    val packageName = "lsposed.orange"
    namespace = packageName
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = packageName
        minSdk = 30
        targetSdk = 32
        versionCode = 4
        versionName = "1.2.0-npatch"
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
            val keystoreFilePath = providers.environmentVariable("ORANGE_KEYSTORE_FILE").orNull
            val keystorePwd = providers.environmentVariable("ORANGE_KEYSTORE_PASSWORD").orNull
            val keyAliasName = providers.environmentVariable("ORANGE_KEY_ALIAS").orNull
            val keyPwd = providers.environmentVariable("ORANGE_KEY_PASSWORD").orNull
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
        // BuildConfig is used by OrangeModule (BuildConfig.APPLICATION_ID) and SettingFragment
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
        // Orange-NG needs to enumerate all installed apps so the user can pick one and configure
        // its orientation. QUERY_ALL_PACKAGES is the only way to do this on Android 11+.
        disable += "GoogleAppIndexingWarning"
        disable += "Instantiatable"
        // targetSdk is intentionally preserved at 32 (the original Orange-NG value) per the
        // migration brief: "Do not increase target SDK or changing runtime behavior without
        // investigating the consequences." The ExpiredTargetSdkVersion lint check enforces the
        // Google Play Store's target-API requirement, which is a distribution policy concern, not
        // a build or runtime incompatibility. The module is distributed via GitHub Releases
        // (not Google Play), so this check does not apply. Bumping targetSdk to 33+ would change
        // runtime behavior (notification runtime permissions, granular media permissions,
        // predictive back gesture, etc.) and is therefore out of scope for this migration.
        // Documented in docs/npatch-migration-notes/WARNINGS.md.
        disable += "ExpiredTargetSdkVersion"
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
