package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AgentEntity::class, BugReportEntity::class, PendingLocalEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class LettaDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun bugReportDao(): BugReportDao
    abstract fun pendingLocalDao(): PendingLocalDao

    companion object {
        @Volatile
        private var INSTANCE: LettaDatabase? = null

        fun getDatabase(context: Context): LettaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LettaDatabase::class.java,
                    "letta_database"
                )
                    .addMigrations(*LettaDatabaseMigrations.ALL)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
