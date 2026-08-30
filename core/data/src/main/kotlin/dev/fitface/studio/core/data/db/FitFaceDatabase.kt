package dev.fitface.studio.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.AutoMigration

/**
 * The projects database.
 *
 * **The version numbers cannot be renumbered, and versions 1–3 cannot be reclaimed.** The
 * first public release, `v0.1.0`, already shipped at version 4 — schemas 1, 2 and 3 and the
 * migrations between them were development steps, so no device anywhere holds a database at
 * one of them. That makes renumbering 4 down to 1 look free, and it is the opposite: every
 * install on earth has `PRAGMA user_version = 4`, so a build declaring a lower number opens
 * that file as a **downgrade**, and this builder answers a downgrade with
 * `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`. Every saved project on
 * every existing phone, deleted on first launch, silently.
 *
 * The dead 1→2, 2→3 and 3→4 steps stay for the same reason they are cheap: they cost two
 * schema files and a test apiece, they are the honest record of how the shape got here, and
 * removing them would strand any development database still sitting at one of those numbers
 * with no upgrade path at all.
 */
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
