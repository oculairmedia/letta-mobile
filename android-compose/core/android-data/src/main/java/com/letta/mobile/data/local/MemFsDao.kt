package com.letta.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemFsDao {
    @Query("SELECT * FROM memfs_files WHERE path = :path LIMIT 1")
    suspend fun file(path: String): MemFsFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: MemFsFileEntity)

    @Query("DELETE FROM memfs_files WHERE path = :path")
    suspend fun deleteFile(path: String)

    @Query("SELECT COALESCE(MAX(revision), 0) FROM memfs_commits")
    suspend fun maxRevision(): Long

    @Query("SELECT MAX(revision) FROM memfs_commits")
    fun observeMaxRevision(): Flow<Long?>

    @Query("SELECT * FROM memfs_commits WHERE revision > :afterRevision ORDER BY revision ASC")
    suspend fun listCommitsAfter(afterRevision: Long): List<MemFsCommitEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommit(commit: MemFsCommitEntity)

    @Query("DELETE FROM memfs_files")
    suspend fun deleteAllFiles()

    @Query("DELETE FROM memfs_commits")
    suspend fun deleteAllCommits()
}
