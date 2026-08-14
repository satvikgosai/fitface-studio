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
            .addMigrations(Migration3To4)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun provideProjectDao(database: FitFaceDatabase): ProjectDao = database.projectDao()

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver
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
