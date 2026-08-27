package dev.fitface.studio.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    /**
     * Every project, newest-opened first.
     *
     * This order is the list's stable base, not what it shows: the Projects page sorts and
     * filters what it is given, so the sort a person picked survives a row changing under
     * it. Ordering here as well keeps the flow deterministic when no sort has been chosen.
     */
    @Query("SELECT * FROM watch_face_projects ORDER BY importedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM watch_face_projects WHERE id = :id")
    suspend fun findById(id: Long): ProjectEntity?

    /**
     * Every project started on this face. Used to name a new one so it cannot collide with
     * one already there, and to tell the face sheet which projects are the face's own.
     *
     * There is deliberately no `findBySourceUri`. It was how `openPackage` decided to reuse
     * a project instead of creating one, which is exactly the behaviour being removed;
     * reinstating it would quietly restore one project per face.
     */
    @Query("SELECT * FROM watch_face_projects WHERE faceId = :faceId")
    suspend fun findByFaceId(faceId: String): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity): Long

    /**
     * Deliberately not an `insert` of a whole row. A rename can arrive while the editor
     * holds an older copy of the same project, and writing that copy back would undo the
     * commit it has not seen.
     */
    @Query("UPDATE watch_face_projects SET projectName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("DELETE FROM watch_face_projects WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
