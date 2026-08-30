package dev.fitface.studio.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved edit of one watch face.
 *
 * There is deliberately **no uniqueness on [sourceUri]**. Schema 4 had one, and it is what
 * limited a face to a single project: `openPackage` looked a row up by that key and reused
 * it, so opening a face you already had silently re-entered the same project. A face may
 * now carry as many projects as you start on it, and [faceId] is indexed because that is
 * the question the catalogue's face sheet asks — "which of these are mine".
 *
 * [sourceUri] stays as provenance. Nothing queries it any more; [productId],
 * [packageVersionCode] and [styleId] carry the same three facts in a form that can be
 * compared without parsing a string, and schema 5 backfilled them from it.
 */
@Entity(
    tableName = "watch_face_projects",
    indices = [Index(value = ["faceId"])],
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val sourceUri: String,
    val faceId: String,
    val faceName: String? = null,
    /**
     * When this project was last *opened*, not last edited — `openProject` bumps it. It is
     * why two projects on one face swapped places in the list every time either was
     * opened, and why the list sorts on [updatedAtEpochMillis] instead.
     */
    val importedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "NULL")
    val localApkPath: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val editedBinPath: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val selectedStyle: String? = null,
    /**
     * The name a person sees, written once when the project is created and thereafter only
     * by a rename.
     *
     * Nothing derived may overwrite it. `openProject` rewrites [faceName] and
     * [importedAtEpochMillis] from the package on every open, and a name that did the same
     * would undo a rename the next time the project was opened.
     *
     * Null only on a row written before schema 5 that the backfill could not name; the
     * summary falls back through [faceName] and [displayName] for those.
     */
    @ColumnInfo(defaultValue = "NULL")
    val projectName: String? = null,
    /** Null when [sourceUri] is not one of this app's keys — an imported `content://` row. */
    @ColumnInfo(defaultValue = "NULL")
    val productId: String? = null,
    /**
     * The store version this project was built from, against which the catalogue's current
     * `versionCode` says whether a newer one exists. Null means unknown, which must read as
     * "say nothing", never as "out of date".
     */
    @ColumnInfo(defaultValue = "NULL")
    val packageVersionCode: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val styleId: Int? = null,
    /** When a commit last changed this project. Set at creation and by every commit. */
    @ColumnInfo(defaultValue = "0")
    val updatedAtEpochMillis: Long = 0,
)
