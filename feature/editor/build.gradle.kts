plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.fitface.studio.feature.editor"
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
        // The ViewModel's failure path logs through android.util.Log, which is a stub that
        // throws in a plain unit test — so without this the exception escaped `showFailure`
        // and the error the test was asserting on was never set. There is no Android
        // runtime here on purpose; see the comment on kotlinx-coroutines-test below.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:delivery"))
    implementation(project(":core:ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    // Style previews are PNGs extracted from the package into app storage; Coil keeps
    // decoding and downsampling them off this module.
    implementation(libs.coil.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    // EditorViewModel launches from its own init, so the ViewModel tests have to own
    // Dispatchers.Main. Deliberately not Robolectric: this module depends on
    // :core:delivery, whose merged manifest declares a receiver from the accessory SDK
    // JAR, and instantiating that pre-stackmap bytecode fails the JVM verifier before a
    // single test runs. A test dispatcher needs no Android runtime at all.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
