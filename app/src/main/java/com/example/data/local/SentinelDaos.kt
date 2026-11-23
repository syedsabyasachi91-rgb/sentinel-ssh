package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles ORDER BY COALESCE(lastConnectedAt, 0) DESC, createdAt DESC")
    fun observeAllProfiles(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE id = :id")
    fun observeProfileById(id: Long): Flow<ServerProfileEntity?>

    @Query("SELECT * FROM server_profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): ServerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ServerProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ServerProfileEntity)

    @Query("UPDATE server_profiles SET trustedHostFingerprint = :fingerprint WHERE id = :id")
    suspend fun updateHostFingerprint(id: Long, fingerprint: String)

    @Query("UPDATE server_profiles SET trustedHostFingerprint = NULL WHERE id = :id")
    suspend fun clearHostFingerprint(id: Long)

    @Query("UPDATE server_profiles SET lastConnectedAt = :timestamp, lastOsSummary = :osSummary WHERE id = :id")
    suspend fun updateConnectionTelemetry(id: Long, timestamp: Long, osSummary: String?)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)
}

@Dao
interface CommandSnippetDao {
    @Query("SELECT * FROM command_snippets ORDER BY isBuiltIn ASC, createdAt DESC")
    fun observeAllSnippets(): Flow<List<CommandSnippetEntity>>

    @Query("SELECT COUNT(*) FROM command_snippets")
    suspend fun countSnippets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnippet(snippet: CommandSnippetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snippets: List<CommandSnippetEntity>)

    @Query("DELETE FROM command_snippets WHERE id = :id")
    suspend fun deleteSnippetById(id: Long)
}
