package com.letta.mobile.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object LettaDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `project_bug_reports` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `projectIdentifier` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `severity` TEXT NOT NULL,
                    `tagsJson` TEXT NOT NULL,
                    `attachmentReferencesJson` TEXT NOT NULL,
                    `structuredPrompt` TEXT NOT NULL,
                    `createdAt` TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_local_messages` (
                    `otid` TEXT NOT NULL,
                    `conversationId` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `attachmentsJson` TEXT NOT NULL,
                    `sentAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`otid`)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("SELECT `id`, `tagsJson` FROM `agents` WHERE `tagsJson` IS NOT NULL")
            try {
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val tagsIndex = cursor.getColumnIndexOrThrow("tagsJson")
                val update = db.compileStatement("UPDATE `agents` SET `tagsJson` = ? WHERE `id` = ?")
                while (cursor.moveToNext()) {
                    val rawTags = cursor.getString(tagsIndex)
                    if (AgentEntity.isJsonEncodedTags(rawTags)) continue

                    update.clearBindings()
                    update.bindString(1, AgentEntity.encodeTags(AgentEntity.decodeTags(rawTags)))
                    update.bindString(2, cursor.getString(idIndex))
                    update.executeUpdateDelete()
                }
            } finally {
                cursor.close()
            }
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` TEXT NOT NULL,
                    `agentId` TEXT NOT NULL,
                    `summary` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    `lastMessageAt` TEXT,
                    `archived` INTEGER,
                    `archivedAt` TEXT,
                    `inContextMessageIdsJson` TEXT NOT NULL,
                    `isolatedBlockIdsJson` TEXT NOT NULL,
                    `cachedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_agentId` ON `conversations` (`agentId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_lastMessageAt` ON `conversations` (`lastMessageAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_createdAt` ON `conversations` (`createdAt`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_refresh_state` (
                    `agentId` TEXT NOT NULL,
                    `lastRefreshAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`agentId`)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `runtime_events` (
                    `eventOffset` INTEGER NOT NULL,
                    `eventId` TEXT NOT NULL,
                    `backendId` TEXT NOT NULL,
                    `runtimeId` TEXT NOT NULL,
                    `agentId` TEXT,
                    `conversationId` TEXT,
                    `runId` TEXT,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    `schemaVersion` INTEGER NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    PRIMARY KEY(`eventOffset`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_events_eventId` ON `runtime_events` (`eventId`)")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_runtime_events_backendId_runtimeId_eventOffset`
                ON `runtime_events` (`backendId`, `runtimeId`, `eventOffset`)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_events_conversationId` ON `runtime_events` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_events_agentId` ON `runtime_events` (`agentId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_events_runId` ON `runtime_events` (`runId`)")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memfs_files` (
                    `path` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `metadataJson` TEXT NOT NULL,
                    PRIMARY KEY(`path`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memfs_commits` (
                    `revision` INTEGER NOT NULL,
                    `commitId` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `operation` TEXT NOT NULL,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`revision`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memfs_commits_commitId` ON `memfs_commits` (`commitId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memfs_commits_path` ON `memfs_commits` (`path`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )
}
