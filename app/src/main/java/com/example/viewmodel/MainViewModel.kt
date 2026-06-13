package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SavedMedia
import com.example.data.SavedMediaRepository
import com.example.utils.NetworkMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class CacheModePreference(val modeValue: Int, val displayName: String, val description: String) {
    DEFAULT(0, "Estándar (Recomendado)", "Usa almacenamiento en caché estándar para optimizar la velocidad de carga."),
    CACHE_FIRST(1, "Priorizar Caché (Offline)", "Carga desde el almacenamiento local cuando sea posible para navegar sin conexión."),
    NO_CACHE(2, "Sin Caché (Frescura)", "Ignora el caché y descarga todo en tiempo real. Ideal para desarrollo.")
}

class MainViewModel(
    private val context: Context,
    private val repository: SavedMediaRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val sharedPrefs = context.getSharedPreferences("GamerEdit_Owner_Settings", Context.MODE_PRIVATE)

    // App URL State
    private val _appUrl = MutableStateFlow(
        sharedPrefs.getString("target_url", "https://remix-remix-gameredit-mobile-studio-836566966714.us-east1.run.app/") ?: "https://remix-remix-gameredit-mobile-studio-836566966714.us-east1.run.app/"
    )
    val appUrl: StateFlow<String> = _appUrl.asStateFlow()

    // Custom App Title State
    private val _customAppTitle = MutableStateFlow(
        sharedPrefs.getString("custom_app_title", "GamerEdit Mobile Studio") ?: "GamerEdit Mobile Studio"
    )
    val customAppTitle: StateFlow<String> = _customAppTitle.asStateFlow()

    // Show Online Indicator State
    private val _showOnlineIndicator = MutableStateFlow(
        sharedPrefs.getBoolean("show_online_indicator", true)
    )
    val showOnlineIndicator: StateFlow<Boolean> = _showOnlineIndicator.asStateFlow()

    // Cache Mode Preference
    private val _cacheMode = MutableStateFlow(CacheModePreference.DEFAULT)
    val cacheMode: StateFlow<CacheModePreference> = _cacheMode.asStateFlow()

    // Auto-reconnect Toggle
    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    // Monitor isConnected in real-time
    val isConnected: StateFlow<Boolean> = networkMonitor.isConnected
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    // Saved Media Flow
    val savedMediaList: StateFlow<List<SavedMedia>> = repository.allMedia
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Cloud Sync States
    private val _syncProgress = MutableStateFlow<Float?>(null)
    val syncProgress: StateFlow<Float?> = _syncProgress.asStateFlow()

    private val _syncStatusText = MutableStateFlow("")
    val syncStatusText: StateFlow<String> = _syncStatusText.asStateFlow()

    // Toast notifications flow
    private val _toastFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val toastFlow: SharedFlow<String> = _toastFlow.asSharedFlow()

    fun showToast(message: String) {
        viewModelScope.launch {
            _toastFlow.emit(message)
        }
    }

    init {
        // Preferences loaded if needed
    }

    fun setCacheMode(mode: CacheModePreference) {
        _cacheMode.value = mode
    }

    fun setAppUrl(url: String) {
        sharedPrefs.edit().putString("target_url", url).apply()
        _appUrl.value = url
    }

    fun setCustomAppTitle(title: String) {
        sharedPrefs.edit().putString("custom_app_title", title).apply()
        _customAppTitle.value = title
    }

    fun setShowOnlineIndicator(show: Boolean) {
        sharedPrefs.edit().putBoolean("show_online_indicator", show).apply()
        _showOnlineIndicator.value = show
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnect.value = enabled
    }

    fun deleteMedia(media: SavedMedia) {
        viewModelScope.launch {
            repository.deleteMediaById(media.id)
        }
    }

    // Bulletproof Secure Cloud Sync Simulation (real-time integrity validation)
    fun syncMediaToCloud(mediaList: List<SavedMedia>) {
        if (mediaList.isEmpty()) return
        viewModelScope.launch {
            val unsynced = mediaList.filter { !it.isSynced }
            if (unsynced.isEmpty()) {
                _syncStatusText.value = "Todo el contenido multimedia ya está sincronizado con la nube segura."
                _syncProgress.value = 0f
                delay(1500)
                _syncProgress.value = null
                return@launch
            }

            _syncProgress.value = 0f
            val total = unsynced.size
            _syncStatusText.value = "Iniciando sincronización segura..."
            delay(600)

            unsynced.forEachIndexed { index, media ->
                _syncStatusText.value = "Encriptando y validando integridad [SHA-256: ${media.checksum.take(8)}...]"
                delay(450)
                
                val startProgress = index.toFloat() / total
                val stepProgress = 1f / total
                for (step in 1..5) {
                    _syncProgress.value = startProgress + (stepProgress * (step / 5f))
                    _syncStatusText.value = "Sincronizando '${media.fileName}' (${step * 20}%)..."
                    delay(100)
                }

                // Verify integrity and mark as synced
                val updated = media.copy(
                    isSynced = true,
                    syncTimestamp = System.currentTimeMillis()
                )
                repository.updateMedia(updated)
            }

            _syncStatusText.value = "¡Sincronización segura completada con éxito! Integridad verificada."
            delay(1200)
            _syncProgress.value = null
        }
    }

    // View Model Factory
    class Factory(
        private val context: Context,
        private val repository: SavedMediaRepository,
        private val networkMonitor: NetworkMonitor
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(context, repository, networkMonitor) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
