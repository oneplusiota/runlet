package dev.runlet.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun get(id: Long): AutomationEntity?

    @Query("SELECT * FROM automations WHERE enabled = 1")
    suspend fun getEnabled(): List<AutomationEntity>

    @Query("UPDATE automations SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT * FROM automations WHERE name = :name")
    suspend fun getByName(name: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AutomationEntity): Long

    @Update
    suspend fun update(entity: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RunDao {
    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Query("UPDATE runs SET status = :status, endedAtMs = :endedAtMs, error = :error WHERE id = :runId")
    suspend fun finishRun(runId: Long, status: String, endedAtMs: Long?, error: String?)

    @Query("UPDATE runs SET status = :status WHERE id = :runId")
    suspend fun setStatus(runId: Long, status: String)

    @Query("SELECT MAX(startedAtMs) FROM runs WHERE automationId = :automationId")
    suspend fun lastStartedAt(automationId: Long): Long?

    @Insert
    suspend fun insertLog(entry: RunLogEntity)

    @Query("SELECT * FROM runs ORDER BY startedAtMs DESC, id DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int): Flow<List<RunEntity>>

    @Query("SELECT * FROM run_logs WHERE runId = :runId ORDER BY id")
    fun observeLogs(runId: Long): Flow<List<RunLogEntity>>

    /** Runs left RUNNING/PAUSED by a process death; marked STOPPED on next launch. */
    @Query(
        "UPDATE runs SET status = 'STOPPED', endedAtMs = :nowMs, error = 'Interrupted' " +
            "WHERE status IN ('RUNNING','PAUSED')",
    )
    suspend fun closeOrphanedRuns(nowMs: Long): Int

    /** Keeps the newest [keep] runs; their logs cascade. */
    @Query(
        "DELETE FROM runs WHERE id NOT IN " +
            "(SELECT id FROM runs ORDER BY startedAtMs DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM runs")
    suspend fun clear()
}
