package com.securecam.data.local

import android.content.Context
import androidx.room.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "security_logs")
data class SecurityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val logTime: Long,
    val type: String,
    val description: String,
    val confidence: Float,
    val videoPath: String? = null
)

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SecurityLogEntity)

    @Query("SELECT * FROM security_logs ORDER BY logTime DESC")
    fun getAllLogs(): Flow<List<SecurityLogEntity>>

    @Query("SELECT * FROM security_logs ORDER BY logTime DESC")
    fun getAllLogsSync(): List<SecurityLogEntity>

    @Query("DELETE FROM security_logs WHERE id = :id")
    suspend fun deleteLogById(id: Int)
}

@Database(entities = [SecurityLogEntity::class], version = 1, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}

// CRITICAL FIX: The missing Hilt Dependency Injection module
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LogDatabase {
        return Room.databaseBuilder(
            context,
            LogDatabase::class.java,
            "securecam_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideLogDao(database: LogDatabase): LogDao {
        return database.logDao()
    }
}