package dev.runlet.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AutomationEntity::class, RunEntity::class, RunLogEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class RunletDatabase : RoomDatabase() {
    abstract fun automations(): AutomationDao
    abstract fun runs(): RunDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN enabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): RunletDatabase =
            Room.databaseBuilder(context.applicationContext, RunletDatabase::class.java, "runlet.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
