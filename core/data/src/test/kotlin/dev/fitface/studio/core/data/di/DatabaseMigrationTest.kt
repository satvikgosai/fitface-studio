package dev.fitface.studio.core.data.di

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import dev.fitface.studio.core.data.db.FitFaceDatabase
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            insertV3(database, id = 1, importedAt = 10)
            insertV3(database, id = 2, importedAt = 20)
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

    /**
     * The upgrade every existing install runs. Nothing may be deleted, every row has to come
     * out with a name that tells it apart from its siblings, and the columns parsed out of
     * `sourceUri` have to be right — or NULL where there was nothing to parse.
     */
    @Test
    @Throws(IOException::class)
    fun migration4To5NamesEveryProjectAndParsesItsSource() {
        helper.createDatabase(DatabaseName, 4).use { database ->
            // Two styles of one face: the only way an install could already hold two
            // projects on the same face, and the pair the naming has to separate.
            insertV4(
                database,
                id = 1,
                faceId = "00112",
                sourceUri = "fit3-catalog://dev.fitface.face00112/40001/0",
                faceName = "Black and white",
                selectedStyle = "style0.bin",
                importedAt = 100,
            )
            insertV4(
                database,
                id = 2,
                faceId = "00112",
                sourceUri = "fit3-catalog://dev.fitface.face00112/40001/3",
                faceName = "Black and white",
                selectedStyle = "style3.bin",
                importedAt = 200,
            )
            // A schema 1 row from the file picker. It carries no catalogue key, and a
            // migration that assumed one would strand the database on version 4 for good.
            insertV4(
                database,
                id = 3,
                faceId = "00106",
                sourceUri = "content://documents/imported",
                faceName = null,
                displayName = "Imported face.apk",
                selectedStyle = null,
                importedAt = 300,
            )
        }

        helper.runMigrationsAndValidate(DatabaseName, 5, true, Migration4To5).use { database ->
            database.query(
                """
                SELECT id, projectName, productId, packageVersionCode, styleId,
                       updatedAtEpochMillis, importedAtEpochMillis
                FROM watch_face_projects ORDER BY id
                """.trimIndent(),
            ).use { cursor ->
                assertEquals("no project may be dropped by the upgrade", 3, cursor.count)

                cursor.moveToNext()
                assertEquals("Black and white", cursor.getString(1))
                assertEquals("dev.fitface.face00112", cursor.getString(2))
                assertEquals(40001L, cursor.getLong(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(100L, cursor.getLong(5))

                cursor.moveToNext()
                assertEquals(
                    "the second project on a face is numbered, not left identical",
                    "Black and white 2",
                    cursor.getString(1),
                )
                assertEquals(3, cursor.getInt(4))
                assertEquals(200L, cursor.getLong(5))

                cursor.moveToNext()
                assertEquals("Imported face", cursor.getString(1))
                assertNull("an unparseable sourceUri leaves NULL", cursor.stringOrNull(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
                assertEquals(300L, cursor.getLong(5))
            }
        }
    }

    /**
     * The point of the upgrade: a face may carry more than one project again. Schema 4's
     * unique index is what refused the second one, so this fails loudly if the DROP is lost.
     */
    @Test
    @Throws(IOException::class)
    fun migration4To5LetsAFaceCarryTwoProjectsFromTheSamePackage() {
        helper.createDatabase(DatabaseName, 4).use { database ->
            insertV4(
                database,
                id = 1,
                faceId = "00112",
                sourceUri = "fit3-catalog://dev.fitface.face00112/40001/0",
                faceName = "Black and white",
                selectedStyle = "style0.bin",
                importedAt = 100,
            )
        }

        helper.runMigrationsAndValidate(DatabaseName, 5, true, Migration4To5).use { database ->
            database.execSQL(
                """
                INSERT INTO watch_face_projects(
                    id, displayName, sourceUri, faceId, faceName, importedAtEpochMillis,
                    localApkPath, editedBinPath, selectedStyle, projectName, productId,
                    packageVersionCode, styleId, updatedAtEpochMillis
                ) VALUES(
                    2, 'Black and white.apk', 'fit3-catalog://dev.fitface.face00112/40001/0',
                    '00112', 'Black and white', 400, NULL, NULL, 'style0.bin',
                    'Black and white 2', 'dev.fitface.face00112', 40001, 0, 400
                )
                """.trimIndent(),
            )
            database.query("SELECT COUNT(*) FROM watch_face_projects").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    private fun Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun insertV3(database: SupportSQLiteDatabase, id: Long, importedAt: Long) {
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

    private fun insertV4(
        database: SupportSQLiteDatabase,
        id: Long,
        faceId: String,
        sourceUri: String,
        faceName: String?,
        selectedStyle: String?,
        importedAt: Long,
        displayName: String = "${faceName.orEmpty()}.apk",
    ) {
        database.execSQL(
            """
            INSERT INTO watch_face_projects(
                id, displayName, sourceUri, faceId, faceName, importedAtEpochMillis,
                localApkPath, editedBinPath, selectedStyle
            ) VALUES(?, ?, ?, ?, ?, ?, NULL, NULL, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                id,
                displayName,
                sourceUri,
                faceId,
                faceName,
                importedAt,
                selectedStyle,
            ),
        )
    }

    private companion object {
        const val DatabaseName = "migration-test"
    }
}
