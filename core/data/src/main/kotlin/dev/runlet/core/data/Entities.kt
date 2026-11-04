package dev.runlet.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved automation. [source] is the DSL text, the canonical form until the JSON model lands. */
@Entity(tableName = "automations", indices = [Index(value = ["name"], unique = true)])
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,
    val updatedAtMs: Long,
    /** Whether its `when` triggers are armed. Off by default, including for imports. */
    @ColumnInfo(defaultValue = "0") val enabled: Boolean = false,
)

/** One execution of an automation. Survives deletion of the automation (id is nulled). */
@Entity(
    tableName = "runs",
    foreignKeys = [ForeignKey(
        entity = AutomationEntity::class,
        parentColumns = ["id"],
        childColumns = ["automationId"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("automationId"), Index("startedAtMs")],
)
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val automationId: Long?,
    val automationName: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    /** Mirrors RunStatus: RUNNING, PAUSED, COMPLETED, ERROR, STOPPED. */
    val status: String,
    val error: String? = null,
)

@Entity(
    tableName = "run_logs",
    foreignKeys = [ForeignKey(
        entity = RunEntity::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("runId")],
)
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val timestampMs: Long,
    val message: String,
)
