package com.example.data

import kotlinx.coroutines.flow.Flow

class SavedMediaRepository(private val dao: SavedMediaDao) {
    val allMedia: Flow<List<SavedMedia>> = dao.getAllMediaFlow()

    suspend fun insertMedia(media: SavedMedia): Long {
        return dao.insertMedia(media)
    }

    suspend fun updateMedia(media: SavedMedia) {
        dao.updateMedia(media)
    }

    suspend fun deleteMediaById(id: Int) {
        dao.deleteMediaById(id)
    }

    suspend fun getMediaById(id: Int): SavedMedia? {
        return dao.getMediaById(id)
    }
}
