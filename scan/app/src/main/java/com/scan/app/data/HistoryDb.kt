package com.scan.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val mode: String = "general",
    val models: String = "",
    val analysisJson: String = "",
    val rawText: String = "",
    val notes: String = "",
    val sourceUrl: String = "",
    val title: String = ""
)

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    fun observeById(id: Long): Flow<HistoryEntity?>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE models LIKE :like ORDER BY createdAt DESC LIMIT 100")
    suspend fun byModel(like: String): List<HistoryEntity>

    @Insert
    suspend fun insert(e: HistoryEntity): Long

    @Update
    suspend fun update(e: HistoryEntity)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT * FROM history WHERE createdAt < :cutoff")
    suspend fun olderThan(cutoff: Long): List<HistoryEntity>
}

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {

    abstract fun history(): HistoryDao

    companion object {
        fun build(context: Context): HistoryDb =
            Room.databaseBuilder(context.applicationContext, HistoryDb::class.java, "scan-history.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
