plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.fitface.studio.core.format"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Corpus-backed tests skip when this directory is absent; see the root build file.
    systemProperty("fit3.corpusRoot", (rootProject.extra["fit3CorpusRoot"] as File).absolutePath)
}
