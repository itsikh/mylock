package com.mylock.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// ── Entity ───────────────────────────────────────────────────────────────────

enum class LockEventType { UNLOCK, LOCK, UNLOCK_FAILED, LOCK_FAILED }

@Entity(tableName = "lock_events")
data class LockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_type") val eventType: LockEventType,
    @ColumnInfo(name = "lock_id") val lockId: Long,
    @ColumnInfo(name = "lock_name") val lockName: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "error_message") val errorMessage: String? = null
)

// ── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface LockEventDao {
    @Insert
    suspend fun insert(event: LockEvent)

    @Query("SELECT * FROM lock_events ORDER BY timestamp DESC LIMIT 50")
    fun getRecentEvents(): Flow<List<LockEvent>>

    @Query("DELETE FROM lock_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(entities = [LockEvent::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockEventDao(): LockEventDao
}
