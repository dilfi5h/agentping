package io.dilfi5h.agentping.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One ntfy message frame. Primary key is ntfy's idempotent id (resume/replay via since never
 * duplicates or drops; a conflict means a duplicate message, dropped by insert IGNORE).
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    /** ntfy-side second-level timestamp * 1000; timeline sorting always uses it (reporter clocks are untrusted). */
    val time: Long,
    val topic: String,
    val title: String?,
    val raw: String?,
    val agent: String?,
    val host: String?,
    val state: String?,
    val task: String?,
    val detail: String?,
    val session: String?,
    val ts: Long?,
    val dur: Long?,
) {
    val stateKind: StateKind get() = StateKind.from(state ?: "")
}

@Dao
interface MessageDao {
    /** @return id of the newly inserted row; -1 means it duplicates an existing message (ntfy id is idempotent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY time DESC LIMIT 1000")
    fun timeline(): Flow<List<MessageEntity>>

    @Query("SELECT id FROM messages ORDER BY time DESC LIMIT 1")
    suspend fun lastId(): String?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE time < :before")
    suspend fun prune(before: Long)
}

/** Tombstone for deleted messages: skips these id when the server cache replays (since=12h refresh), so deletes don't come back to life. */
@Entity(tableName = "deleted_ids")
data class DeletedId(
    @PrimaryKey val id: String,
    val deletedAt: Long,
)

@Dao
interface DeletedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(d: DeletedId)

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_ids WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /** ntfy caches 12h, keeping tombstones for 2 days is enough to cover any replay window. */
    @Query("DELETE FROM deleted_ids WHERE deletedAt < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [MessageEntity::class, DeletedId::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MessageDao
    abstract fun deletedDao(): DeletedDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "agentping.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
