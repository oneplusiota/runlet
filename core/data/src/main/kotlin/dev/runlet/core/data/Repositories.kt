package dev.runlet.core.data

import android.database.sqlite.SQLiteConstraintException
import dev.runlet.core.engine.Clock
import dev.runlet.core.engine.ScriptParser
import dev.runlet.core.engine.ScriptText
import kotlinx.coroutines.flow.Flow

/** Thrown when saving would break a rule the user can fix (duplicate name, bad script). */
class AutomationSaveException(message: String) : IllegalArgumentException(message)

class AutomationRepository(private val dao: AutomationDao, private val clock: Clock) {
    fun observeAll(): Flow<List<AutomationEntity>> = dao.observeAll()

    suspend fun get(id: Long): AutomationEntity? = dao.get(id)

    /**
     * Validates [source] with the parser, then inserts or updates. The stored name always
     * comes from the script's `routine "name"` line so the list and the script cannot drift.
     * Returns the row id.
     */
    suspend fun save(id: Long?, source: String): Long {
        val name = try {
            ScriptParser.parse(source).name
        } catch (e: IllegalArgumentException) {
            throw AutomationSaveException(e.message ?: "Invalid script")
        }
        val now = clock.nowMs()
        return try {
            if (id == null || id == 0L) {
                dao.insert(AutomationEntity(name = name, source = source, updatedAtMs = now))
            } else {
                val existing = dao.get(id) ?: throw AutomationSaveException("Automation no longer exists")
                dao.update(existing.copy(name = name, source = source, updatedAtMs = now))
                id
            }
        } catch (e: SQLiteConstraintException) {
            throw AutomationSaveException("An automation named “$name” already exists")
        }
    }

    /**
     * Autosave for the editor: stores [source] even when it does not parse yet, so in-progress
     * edits are never lost. The name follows the script's `routine` line only while the script is
     * valid and the name is free; otherwise the previous name is kept.
     */
    suspend fun saveDraft(id: Long, source: String) {
        val existing = dao.get(id) ?: throw AutomationSaveException("Automation no longer exists")
        val parsedName = try {
            ScriptParser.parse(source).name
        } catch (_: IllegalArgumentException) {
            null
        }
        val now = clock.nowMs()
        if (parsedName != null && parsedName != existing.name) {
            try {
                dao.update(existing.copy(name = parsedName, source = source, updatedAtMs = now))
                return
            } catch (_: SQLiteConstraintException) {
                // Name belongs to another automation: fall through and keep the old name.
            }
        }
        dao.update(existing.copy(source = source, updatedAtMs = now))
    }

    /** Creates an empty-but-editable automation named [name]. Returns its id. */
    suspend fun createDraft(name: String): Long {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME_CHARS || clean.any { it == '"' || it.isISOControl() }) {
            throw AutomationSaveException("Use a name of 1–$MAX_NAME_CHARS characters without quotes")
        }
        return try {
            dao.insert(
                AutomationEntity(
                    name = clean,
                    source = "routine \"$clean\"\nalerts both\n\n# Add your actions here\n",
                    updatedAtMs = clock.nowMs(),
                ),
            )
        } catch (e: SQLiteConstraintException) {
            throw AutomationSaveException("An automation named “$clean” already exists")
        }
    }

    /**
     * Stores imported text as a draft, even if it does not parse. Never runs it. A name clash gets
     * a numeric suffix. Returns the new id.
     */
    suspend fun importSource(source: String): Long {
        val base = try {
            ScriptParser.parse(source).name
        } catch (_: IllegalArgumentException) {
            // Not runnable yet, but a `routine "name"` line still names the draft.
            ROUTINE_LINE.find(source)?.groupValues?.get(1) ?: "Imported draft"
        }
        var name = base
        var n = 2
        while (dao.getByName(name) != null) name = "$base ${n++}"
        // Keep the script's own routine line in step with the list name, so the editor and the list never disagree.
        // Text with no routine line is left exactly as it came.
        val stored = if (name != base && ScriptText.routineName(source) != null) ScriptText.renameRoutine(source, name) else source
        return dao.insert(AutomationEntity(name = name, source = stored, updatedAtMs = clock.nowMs()))
    }

    suspend fun enabled(): List<AutomationEntity> = dao.getEnabled()

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: Long) = dao.delete(id)

    private companion object {
        const val MAX_NAME_CHARS = 80
        val ROUTINE_LINE = Regex("""^\s*routine\s+"([^"\r\n]{1,160})"\s*$""", RegexOption.MULTILINE)
    }
}

/** Run history and per-run logs. Replaces the prototype's single 24,000-character log string. */
class RunHistoryRepository(private val dao: RunDao, private val clock: Clock) {
    fun observeRecent(limit: Int = 50): Flow<List<RunEntity>> = dao.observeRecentRuns(limit)

    fun observeLogs(runId: Long): Flow<List<RunLogEntity>> = dao.observeLogs(runId)

    /** Call once at process start: any run still marked active was killed with its process. */
    suspend fun closeOrphanedRuns(): Int = dao.closeOrphanedRuns(clock.nowMs())

    suspend fun begin(automationId: Long?, automationName: String): Long {
        val id = dao.insertRun(
            RunEntity(
                automationId = automationId,
                automationName = automationName,
                startedAtMs = clock.nowMs(),
                status = "RUNNING",
            ),
        )
        dao.trim(MAX_RUNS)
        return id
    }

    /** Records a run that never started, e.g. a trigger that fired while another routine was running. */
    suspend fun recordSkipped(automationId: Long?, automationName: String, reason: String) {
        val now = clock.nowMs()
        dao.insertRun(
            RunEntity(
                automationId = automationId,
                automationName = automationName,
                startedAtMs = now,
                endedAtMs = now,
                status = "SKIPPED",
                error = reason,
            ),
        )
        dao.trim(MAX_RUNS)
    }

    /** When [automationId] last began a run (or was skipped), or null if never. */
    suspend fun lastStartedAt(automationId: Long): Long? = dao.lastStartedAt(automationId)

    suspend fun log(runId: Long, message: String) =
        dao.insertLog(RunLogEntity(runId = runId, timestampMs = clock.nowMs(), message = message))

    suspend fun setStatus(runId: Long, status: String) = dao.setStatus(runId, status)

    suspend fun finish(runId: Long, status: String, error: String? = null) =
        dao.finishRun(runId, status, clock.nowMs(), error)

    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_RUNS = 200
    }
}
