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
 * 一条 ntfy message 帧。主键用 ntfy 的幂等键 id（since 续传重放时不重不漏，
 * 冲突即重复消息，insert IGNORE 丢弃）。
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    /** ntfy 侧秒级时间戳 * 1000；时间线排序一律用它（reporter 时钟不可信）。 */
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
    /** @return 新插入行 id；-1 表示与已有消息重复（ntfy id 幂等）。 */
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

/** 已删除消息的墓碑：服务器缓存回放（since=12h 刷新）时跳过这些 id，避免删除复活的。 */
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

    /** ntfy 缓存 12h，墓碑留 2 天足够覆盖任何回放窗口。 */
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
