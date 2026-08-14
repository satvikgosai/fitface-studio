buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.9")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Where the tests look for real containers and recorded protocol fixtures.
//
// The corpus is downloaded watch-face packages, which this project has no right to
// redistribute, so it is never committed. Tests that need it skip themselves when it
// is missing: a clean clone builds and runs every synthetic test without it.
//
// First existing directory wins:
//   1. -Pfit3.corpusRoot=<path>, or FIT3_CORPUS_ROOT in the environment
//   2. <repo>/corpus            — the standalone default, gitignored
//   3. <repo>/../artifacts      — the combined workspace this project grew up in
fun resolveRoot(property: String, environment: String, vararg fallbacks: String): File {
    val configured = (
        providers.gradleProperty(property).orNull
            ?: providers.environmentVariable(environment).orNull
        )?.let(::File)
        ?.let { if (it.isAbsolute) it else layout.projectDirectory.file(it.path).asFile }
    val candidates = listOfNotNull(configured) +
        fallbacks.map { layout.projectDirectory.dir(it).asFile }
    return candidates.firstOrNull(File::isDirectory) ?: candidates.last()
}

extra["fit3CorpusRoot"] = resolveRoot(
    property = "fit3.corpusRoot",
    environment = "FIT3_CORPUS_ROOT",
    "corpus",
    "../artifacts",
)

extra["fit3FixtureRoot"] = resolveRoot(
    property = "fit3.fixtureRoot",
    environment = "FIT3_FIXTURE_ROOT",
    "corpus",
    "..",
)

