package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BugReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: BugReportEntity): Long

    @Query(
        """
        SELECT * FROM project_bug_reports
        WHERE projectIdentifier = :projectIdentifier
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentForProject(projectIdentifier: String, limit: Int): List<BugReportEntity>
}
