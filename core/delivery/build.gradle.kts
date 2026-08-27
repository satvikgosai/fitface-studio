import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// The accessory SDK JARs are proprietary third-party binaries. This project has no
// right to redistribute them, so they are not committed — see libs/README.md and
// NOTICE.md. When one is absent the build fetches it from the public mirror below
// and refuses to continue unless the bytes hash to the exact build this module was
// written against. A JAR you supplied yourself is never re-downloaded or replaced.
val accessorySdkMirror =
    "https://raw.githubusercontent.com/MiJey/TizenConsumerSAAgentV2/master/app/libs"

val accessorySdkChecksums = mapOf(
    "accessory-v2.6.4.jar" to "d8333b1d92866b09c712476f82aedfbcfd2f909cbf14cc1d4ebffd9f864dce14",
    "sdk-v1.0.0.jar" to "a0950fde86125fd7487039e6c5d009e1f502155ce504c29ac04c9d2737b78a5b",
)

val accessorySdkDirectory: File = rootProject.layout.projectDirectory.dir("libs").asFile

val fetchAccessorySdk = tasks.register("fetchAccessorySdk") {
    group = "build setup"
    description = "Fetches the accessory SDK JARs into libs/ when absent, enforcing each " +
        "download's SHA-256."

    // Only plain values cross into the task action: the configuration cache is on.
    val mirror = accessorySdkMirror
    val checksums = accessorySdkChecksums
    val libsDirectory = accessorySdkDirectory

    // Declaring the JARs as outputs is the whole up-to-date check. After one
    // successful run the task is skipped until a JAR is deleted or edited.
    outputs.files(checksums.keys.map { File(libsDirectory, it) })

    doLast {
        checksums.forEach { (name, expected) ->
            val jar = File(libsDirectory, name)

            if (jar.isFile) {
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(jar.readBytes())
                    .joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    logger.warn(
                        """
                        WARNING: libs/$name is not the build :core:delivery was written against.
                          expected sha256 $expected
                          actual   sha256 $actual
                          Your copy is left untouched. Delete it to fetch the pinned one instead.
                        """.trimIndent(),
                    )
                }
                return@forEach
            }

            logger.lifecycle(
                """
                libs/$name is absent — fetching it from $mirror

                  WARNING: the accessory SDK JARs are proprietary third-party binaries. They
                  are not covered by this project's MIT licence, this project grants no rights
                  in them, and the mirror above is an unofficial third-party copy. They are
                  fetched only so a personal, local build of :core:delivery can compile against
                  the transport the paired watch speaks. Satisfy yourself that you are permitted
                  to use them, and do not redistribute them. See NOTICE.md.
                """.trimIndent(),
            )

            val download = File(libsDirectory, "$name.part")
            try {
                libsDirectory.mkdirs()
                val connection = URI("$mirror/$name").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.inputStream.use { source ->
                    download.outputStream().use { sink -> source.copyTo(sink) }
                }
            } catch (cause: IOException) {
                download.delete()
                throw GradleException(
                    "Could not download libs/$name from $mirror. Retry with a network " +
                        "connection, or put your own copy of the JAR in libs/.",
                    cause,
                )
            }

            // Never trust the mirror: a JAR that does not hash to the pinned value is
            // discarded rather than compiled against.
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(download.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                download.delete()
                throw GradleException(
                    "libs/$name failed verification and was discarded: expected sha256 " +
                        "$expected, got $actual. The mirror is not serving the build this " +
                        "module was written against.",
                )
            }

            Files.move(download.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.lifecycle("libs/$name verified (sha256 $expected)")
        }
    }
}

val accessorySdkJars = files(accessorySdkChecksums.keys.map { File(accessorySdkDirectory, it) })
    .builtBy(fetchAccessorySdk)

android {
    namespace = "dev.fitface.studio.core.delivery"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(accessorySdkJars)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Fixture-backed tests skip when this directory is absent; see the root build file.
    systemProperty("fit3.fixtureRoot", (rootProject.extra["fit3FixtureRoot"] as File).absolutePath)
}
