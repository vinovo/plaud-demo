package demo.nexa.plauid.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import demo.nexa.plauid.data.local.dao.RecordingNoteDao
import demo.nexa.plauid.data.local.entity.RecordingNoteEntity

/**
 * Room database for the Plauid app.
 * Single-table design keeps things simple for a sample app.
 */
@Database(
    entities = [RecordingNoteEntity::class],
    version = 2,
    exportSchema = false // For sample app; enable for production
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun recordingNoteDao(): RecordingNoteDao
    
    companion object {
        private const val DATABASE_NAME = "plauid_db"
        
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * Get the singleton database instance.
         * Thread-safe double-checked locking pattern.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                // For sample app: destructive migration is fine (data loss acceptable)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
