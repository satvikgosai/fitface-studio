package dev.fitface.studio.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_face_projects",
    indices = [Index(value = ["sourceUri"], unique = true)],
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val sourceUri: String,
    val faceId: String,
    val faceName: String? = null,
    val importedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "NULL")
    val localApkPath: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val editedBinPath: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val selectedStyle: String? = null,
)
