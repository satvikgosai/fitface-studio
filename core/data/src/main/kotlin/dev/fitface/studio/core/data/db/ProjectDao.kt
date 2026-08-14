package dev.fitface.studio.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM watch_face_projects ORDER BY importedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM watch_face_projects WHERE id = :id")
    suspend fun findById(id: Long): ProjectEntity?

    @Query("SELECT * FROM watch_face_projects WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun findBySourceUri(sourceUri: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity): Long

    @Query("DELETE FROM watch_face_projects WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
