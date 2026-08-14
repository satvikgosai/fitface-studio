plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.fitface.studio.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("test").assets.directories.add("schemas")
        getByName("androidTest").assets.directories.add("schemas")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:format"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    // Corpus-backed tests skip when this directory is absent; see the root build file.
    systemProperty("fit3.corpusRoot", (rootProject.extra["fit3CorpusRoot"] as File).absolutePath)
}
