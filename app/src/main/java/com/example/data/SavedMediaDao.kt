package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedMediaDao {
    @Query("SELECT * FROM saved_media ORDER BY timestamp DESC")
    fun getAllMediaFlow(): Flow<List<SavedMedia>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: SavedMedia): Long

    @Update
    suspend fun updateMedia(media: SavedMedia)

    @Query("DELETE FROM saved_media WHERE id = :id")
    suspend fun deleteMediaById(id: Int)

    @Query("SELECT * FROM saved_media WHERE id = :id LIMIT 1")
    suspend fun getMediaById(id: Int): SavedMedia?
}
