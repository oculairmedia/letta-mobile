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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
