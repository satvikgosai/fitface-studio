plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.fitface.studio.core.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The layout tests measure real composables, so they need the module's resources
        // and a theme to resolve against.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Robolectric is safe here in a way it is not in :feature:editor — this module
    // depends on nothing but Compose, so there is no accessory SDK bytecode for the JVM
    // verifier to choke on. It is what lets `createComposeRule` measure a real layout
    // without a device.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
    // ApplicationProvider, for the copy assertions in AboutCopyTest.
    testImplementation(libs.androidx.test.core)
    // The rule hosts its content in a ComponentActivity this contributes to the merged
    // debug manifest; without it `createComposeRule` cannot launch and every layout test
    // fails before it measures anything.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
