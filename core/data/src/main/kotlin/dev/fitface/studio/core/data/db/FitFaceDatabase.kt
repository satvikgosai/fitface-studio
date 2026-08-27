package dev.fitface.studio.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.AutoMigration

@Database(
    entities = [ProjectEntity::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
    exportSchema = true,
)
abstract class FitFaceDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}
