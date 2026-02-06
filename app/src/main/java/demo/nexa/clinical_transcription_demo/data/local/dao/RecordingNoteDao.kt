package demo.nexa.clinical_transcription_demo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import demo.nexa.clinical_transcription_demo.data.local.entity.RecordingNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for recording notes.
 */
@Dao
interface RecordingNoteDao {
    
    @Query("SELECT * FROM recording_notes ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<RecordingNoteEntity>>
    
    @Query("SELECT * FROM recording_notes WHERE id = :id")
    fun observeById(id: String): Flow<RecordingNoteEntity?>
    
    @Query("SELECT * FROM recording_notes WHERE id = :id")
    suspend fun getById(id: String): RecordingNoteEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: RecordingNoteEntity)
    
    @Query("UPDATE recording_notes SET transcriptText = :text, status = :status WHERE id = :id")
    suspend fun updateTranscript(id: String, text: String, status: String)
    
    @Query("UPDATE recording_notes SET summaryText = :text, status = :status WHERE id = :id")
    suspend fun updateSummary(id: String, text: String, status: String)
    
    @Query("UPDATE recording_notes SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String?)
    
    @Query("DELETE FROM recording_notes WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM recording_notes")
    suspend fun deleteAll()
}
