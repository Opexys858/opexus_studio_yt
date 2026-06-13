package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_media")
data class SavedMedia(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val localUri: String,
    val size: Long,
    val mimeType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val syncTimestamp: Long? = null,
    val checksum: String = ""
)
