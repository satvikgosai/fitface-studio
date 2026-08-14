package dev.fitface.studio.core.data.di

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import dev.fitface.studio.core.data.db.FitFaceDatabase
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FitFaceDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migration3To4DeduplicatesUrisAndAddsUniqueIndex() {
        helper.createDatabase(DatabaseName, 3).use { database ->
            insert(database, id = 1, importedAt = 10)
            insert(database, id = 2, importedAt = 20)
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            4,
            true,
            Migration3To4,
        ).use { database ->
            database.query(
                "SELECT id FROM watch_face_projects WHERE sourceUri = 'content://same'",
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(2L, cursor.getLong(0))
            }
        }
    }

    private fun insert(database: SupportSQLiteDatabase, id: Long, importedAt: Long) {
        database.execSQL(
            """
            INSERT INTO watch_face_projects(
                id, displayName, sourceUri, faceId, faceName, importedAtEpochMillis,
                localApkPath, editedBinPath, selectedStyle
            ) VALUES(
                $id, 'face.apk', 'content://same', '00106', NULL, $importedAt,
                NULL, NULL, NULL
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val DatabaseName = "migration-test"
    }
}
