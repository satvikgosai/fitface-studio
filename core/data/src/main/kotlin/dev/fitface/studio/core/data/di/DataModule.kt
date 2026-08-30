package dev.fitface.studio.core.data.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.data.WatchFaceRepositoryImpl
import dev.fitface.studio.core.data.FaceCatalogRepositoryImpl
import dev.fitface.studio.core.data.db.FitFaceDatabase
import dev.fitface.studio.core.data.db.ProjectDao
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ProjectNaming
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.model.FaceCatalogRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindWatchFaceRepository(
        implementation: WatchFaceRepositoryImpl,
    ): WatchFaceRepository

    @Binds
    @Singleton
    abstract fun bindFaceCatalogRepository(
        implementation: FaceCatalogRepositoryImpl,
    ): FaceCatalogRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FitFaceDatabase =
        Room.databaseBuilder(context, FitFaceDatabase::class.java, "fitface-studio.db")
            .addMigrations(Migration3To4, Migration4To5)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun provideProjectDao(database: FitFaceDatabase): ProjectDao = database.projectDao()

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    /**
     * One buffer for the whole process. A report is only useful if the catalogue, the
     * editor and the transfer all wrote into the same one, in order.
     */
    @Provides
    @Singleton
    fun provideDiagnosticsLog(): DiagnosticsLog = DiagnosticsLog()
}

internal val Migration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM watch_face_projects
            WHERE id NOT IN (
                SELECT MAX(id)
                FROM watch_face_projects
                GROUP BY sourceUri
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_watch_face_projects_sourceUri
            ON watch_face_projects(sourceUri)
            """.trimIndent(),
        )
    }
}

/**
 * Lets a face carry more than one project again, and gives every project the three facts it
 * needs to be told apart from its siblings: a name, the style it was started on, and the
 * store version it was built from.
 *
 * [Migration3To4] is what took multi-project away — it deleted every row but the newest per
 * `sourceUri` and added a unique index. This drops that index. It does **not** and cannot
 * bring the deleted rows back; their `projects/<id>/` directories are still on disk and are
 * swept separately.
 *
 * The three parsed columns are derived from `sourceUri`, which is
 * `fit3-catalog://<productId>/<versionCode>/<styleId>` for anything this app downloaded.
 * Schema 1 rows came from the file picker and hold a `content://` document URI instead, so
 * a row that does not parse keeps NULLs — a migration that threw here would leave the
 * database on version 4 for good and the app unable to open at all.
 *
 * Names are deduplicated within a face, so an install that already holds two styles of one
 * face comes out of the upgrade with "Aurora" and "Aurora 2" rather than two rows nothing on
 * screen can distinguish.
 */
internal val Migration4To5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_watch_face_projects_sourceUri")
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_watch_face_projects_faceId
            ON watch_face_projects(faceId)
            """.trimIndent(),
        )
        db.execSQL("ALTER TABLE watch_face_projects ADD COLUMN projectName TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE watch_face_projects ADD COLUMN productId TEXT DEFAULT NULL")
        db.execSQL(
            "ALTER TABLE watch_face_projects ADD COLUMN packageVersionCode INTEGER DEFAULT NULL",
        )
        db.execSQL("ALTER TABLE watch_face_projects ADD COLUMN styleId INTEGER DEFAULT NULL")
        db.execSQL(
            """
            ALTER TABLE watch_face_projects
            ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
        // The best "last edited" that can be recovered for a row that never recorded one.
        // Zero would sort every existing project below every new one on the Projects page.
        db.execSQL(
            "UPDATE watch_face_projects SET updatedAtEpochMillis = importedAtEpochMillis",
        )
        backfill(db)
    }

    /**
     * Read every row, then write. Deliberately two passes: the naming pass has to see the
     * names already given to earlier rows on the same face, and holding a cursor open across
     * its own table's updates is not something to rely on.
     */
    private fun backfill(db: SupportSQLiteDatabase) {
        val rows = mutableListOf<LegacyProject>()
        db.query(
            """
            SELECT id, faceId, sourceUri, faceName, displayName, selectedStyle
            FROM watch_face_projects ORDER BY faceId, id
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += LegacyProject(
                    id = cursor.getLong(0),
                    faceId = cursor.getString(1),
                    sourceUri = cursor.getString(2),
                    faceName = if (cursor.isNull(3)) null else cursor.getString(3),
                    displayName = cursor.getString(4),
                    selectedStyle = if (cursor.isNull(5)) null else cursor.getString(5),
                )
            }
        }

        val takenPerFace = mutableMapOf<String, MutableList<String>>()
        for (row in rows) {
            val source = FacePackage.parseSourceKey(row.sourceUri)
            val styleId = source?.styleId ?: styleIndexOf(row.selectedStyle)
            val taken = takenPerFace.getOrPut(row.faceId) { mutableListOf() }
            val name = ProjectNaming.defaultName(row.faceName ?: row.displayName, taken)
            taken += name
            db.execSQL(
                """
                UPDATE watch_face_projects
                SET projectName = ?, productId = ?, packageVersionCode = ?, styleId = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf<Any?>(name, source?.productId, source?.versionCode, styleId, row.id),
            )
        }
    }
}

private val StyleBinNamePattern = Regex("""style(\d+)\.bin""")

/** `style7.bin` -> 7. The second source for a style id, when `sourceUri` gives none. */
private fun styleIndexOf(selectedStyle: String?): Int? =
    selectedStyle?.let(StyleBinNamePattern::matchEntire)
        ?.groupValues?.get(1)
        ?.toIntOrNull()

private data class LegacyProject(
    val id: Long,
    val faceId: String,
    val sourceUri: String,
    val faceName: String?,
    val displayName: String,
    val selectedStyle: String?,
)
