package demo.nexa.plauid.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import demo.nexa.plauid.data.local.entity.RecordingNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for recording notes.
 * Minimal surface area focused on the app's actual needs.
 */
@Dao
interface RecordingNoteDao {
    
    /**
     * Observe all notes, ordered by creation date (newest first).
     */
    @Query("SELECT * FROM recording_notes ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<RecordingNoteEntity>>
    
    /**
     * Observe a single note by ID.
     */
    @Query("SELECT * FROM recording_notes WHERE id = :id")
    fun observeById(id: String): Flow<RecordingNoteEntity?>
    
    /**
     * Get a single note by ID (one-shot, not reactive).
     * Useful for background operations that don't need Flow.
     */
    @Query("SELECT * FROM recording_notes WHERE id = :id")
    suspend fun getById(id: String): RecordingNoteEntity?
    
    /**
     * Insert a new note. Replace if ID already exists (unlikely, but safe).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: RecordingNoteEntity)
    
    /**
     * Update transcript text and status for a note.
     */
    @Query("UPDATE recording_notes SET transcriptText = :text, status = :status WHERE id = :id")
    suspend fun updateTranscript(id: String, text: String, status: String)
    
    /**
     * Update summary text and status for a note.
     */
    @Query("UPDATE recording_notes SET summaryText = :text, status = :status WHERE id = :id")
    suspend fun updateSummary(id: String, text: String, status: String)
    
    /**
     * Update status and optional error message for a note.
     */
    @Query("UPDATE recording_notes SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String?)
    
    /**
     * Delete a note by ID.
     * Caller is responsible for also deleting the associated audio file.
     */
    @Query("DELETE FROM recording_notes WHERE id = :id")
    suspend fun deleteById(id: String)
    
    /**
     * Delete all notes.
     * Useful for testing or full reset scenarios.
     */
    @Query("DELETE FROM recording_notes")
    suspend fun deleteAll()
}
