package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import coil.compose.AsyncImage
import com.example.data.AppDatabase
import com.example.data.SavedMedia
import com.example.data.SavedMediaRepository
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.FileSaver
import com.example.utils.NetworkMonitor
import com.example.viewmodel.CacheModePreference
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun getShareableUri(context: android.content.Context, localUriStr: String): Uri {
    return try {
        if (localUriStr.startsWith("file://")) {
            val fileUri = Uri.parse(localUriStr)
            val file = java.io.File(fileUri.path ?: "")
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "com.aistudio.gameredit.kbswqd.fileprovider",
                file
            )
        } else {
            Uri.parse(localUriStr)
        }
    } catch (e: Exception) {
        Uri.parse(localUriStr)
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var fileSaver: FileSaver
    private var webViewInstance: WebView? = null

    // For file chooser dialog inside WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = if (result.resultCode == Activity.RESULT_OK) {
            val dataString = result.data?.dataString
            val clipData = result.data?.clipData
            if (clipData != null) {
                val count = clipData.itemCount
                val uris = Array(count) { i -> clipData.getItemAt(i).uri }
                uris
            } else if (dataString != null) {
                arrayOf(Uri.parse(dataString))
            } else {
                null
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pre-create WebView Code Cache directories to prevent Chromium Simple Cache Backend accessibility error
        try {
            val webViewWasmCacheDir = java.io.File(applicationContext.cacheDir, "WebView/Default/HTTP Cache/Code Cache/wasm")
            if (!webViewWasmCacheDir.exists()) {
                webViewWasmCacheDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Init databases and utils
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = SavedMediaRepository(database.savedMediaDao())
        val networkMonitor = NetworkMonitor(applicationContext)
        fileSaver = FileSaver(applicationContext, repository)

        val factory = MainViewModel.Factory(applicationContext, repository, networkMonitor)
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]

        setContent {
            MyApplicationTheme {
                MainScreen(
                    viewModel = viewModel,
                    fileSaver = fileSaver,
                    onChooseFileRequested = { callback, params ->
                        filePathCallback = callback
                        val intent = params.createIntent().apply {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        try {
                            fileChooserLauncher.launch(intent)
                        } catch (e: Exception) {
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = null
                            viewModel.showToast("No se pudo abrir el selector de archivos")
                        }
                    },
                    setWebViewRef = { webViewInstance = it }
                )
            }
        }
    }

    override fun onDestroy() {
        webViewInstance?.destroy()
        webViewInstance = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    fileSaver: FileSaver,
    onChooseFileRequested: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    setWebViewRef: (WebView) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observed States
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val cacheMode by viewModel.cacheMode.collectAsStateWithLifecycle()
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val savedMediaList by viewModel.savedMediaList.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val syncStatusText by viewModel.syncStatusText.collectAsStateWithLifecycle()

    val appUrl by viewModel.appUrl.collectAsStateWithLifecycle()
    val customAppTitle by viewModel.customAppTitle.collectAsStateWithLifecycle()
    val showOnlineIndicator by viewModel.showOnlineIndicator.collectAsStateWithLifecycle()

    // Runtime state variables
    var webProgress by remember { mutableStateOf(0) }
    var isLoadingError by remember { mutableStateOf(false) }
    var errorDescription by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf(appUrl) }
    var pageTitle by remember { mutableStateOf("GamerEdit Studio") }

    // UI Drawer / Dialog controls
    var showMediaSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var targetSaveImageUrl by remember { mutableStateOf<String?>(null) }
    var isSavingInProgress by remember { mutableStateOf(false) }

    // Owner Auth states
    var showOwnerPasswordDialog by remember { mutableStateOf(false) }
    var showOwnerSheet by remember { mutableStateOf(false) }
    var ownerPasswordInput by remember { mutableStateOf("") }
    var ownerPasswordError by remember { mutableStateOf(false) }

    // Custom In-App Notification / Toast State which is immune to notification suppressions
    var customToastMessage by remember { mutableStateOf<String?>(null) }
    var toastJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(Unit) {
        viewModel.toastFlow.collect { message ->
            toastJob?.cancel()
            customToastMessage = message
            toastJob = launch {
                delay(3000)
                customToastMessage = null
            }
        }
    }

    // Define camera/microphone permission states
    var cameraPermissionState by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var audioPermissionState by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var storagePermissionState by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // Permission launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        cameraPermissionState = permissions[Manifest.permission.CAMERA] ?: cameraPermissionState
        audioPermissionState = permissions[Manifest.permission.RECORD_AUDIO] ?: audioPermissionState
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermissionState = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: storagePermissionState
        } else {
            storagePermissionState = (permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: storagePermissionState) ||
                    (permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: storagePermissionState)
        }
        viewModel.showToast("Permisos actualizados")
    }

    fun requestPermissions() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(list.toTypedArray())
    }

    // WebView Reference
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Reload triggers on real-time automatic reconnect
    LaunchedEffect(isConnected) {
        if (isConnected && autoReconnect && isLoadingError) {
            webViewRef?.reload()
            isLoadingError = false
        }
    }

    // Reload on cache mode switch to enforce correct settings immediately
    LaunchedEffect(cacheMode) {
        webViewRef?.settings?.cacheMode = when (cacheMode) {
            CacheModePreference.DEFAULT -> WebSettings.LOAD_DEFAULT
            CacheModePreference.CACHE_FIRST -> WebSettings.LOAD_CACHE_ELSE_NETWORK
            CacheModePreference.NO_CACHE -> WebSettings.LOAD_NO_CACHE
        }
        webViewRef?.reload()
    }

    // Reload / Change target URL in WebView when appUrl configuration changes
    LaunchedEffect(appUrl) {
        webViewRef?.let { webView ->
            if (webView.url != appUrl) {
                webView.loadUrl(appUrl)
            }
        }
    }

    // Handle android system back gesture within the embedded WebView bounds
    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = customAppTitle,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.SansSerif
                                )
                                if (showOnlineIndicator) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Inline Network Presence Indicator
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
                                    )
                                }
                            }
                            Text(
                                text = pageTitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        // Permissions icon indicator
                        IconButton(onClick = { requestPermissions() }) {
                            Icon(
                                imageVector = if (cameraPermissionState && audioPermissionState && storagePermissionState) {
                                    Icons.Filled.VerifiedUser
                                } else {
                                    Icons.Outlined.SentimentVeryDissatisfied
                                },
                                contentDescription = "Gestor de Permisos",
                                tint = if (cameraPermissionState && audioPermissionState && storagePermissionState) {
                                    Color(0xFF4CAF50)
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }

                        // Downloads gallery icon
                        IconButton(onClick = { showMediaSheet = true }) {
                            BadgedBox(
                                badge = {
                                    if (savedMediaList.isNotEmpty()) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = Color.White
                                        ) {
                                            Text(savedMediaList.size.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudDownload,
                                    contentDescription = "Galería Multimedia"
                                )
                            }
                        }

                        // Settings Icon
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Configuración"
                            )
                        }

                        // Administrator/Owner Option
                        IconButton(onClick = {
                            ownerPasswordInput = ""
                            ownerPasswordError = false
                            showOwnerPasswordDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = "Panel de Owner",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                    )
                )

                // Indeterminate / Linear load progress bar
                AnimatedVisibility(
                    visible = webProgress > 0 && webProgress < 100,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        progress = { webProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Web Layout
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webViewRef = this
                        setWebViewRef(this)

                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()

                        // Web settings optimized for performance, persistent WebRTC session
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false

                            this.cacheMode = when (viewModel.cacheMode.value) {
                                CacheModePreference.DEFAULT -> WebSettings.LOAD_DEFAULT
                                CacheModePreference.CACHE_FIRST -> WebSettings.LOAD_CACHE_ELSE_NETWORK
                                CacheModePreference.NO_CACHE -> WebSettings.LOAD_NO_CACHE
                            }
                        }

                        // Web Client settings
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                webProgress = 10
                                isLoadingError = false
                                if (url != null) currentUrl = url
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                webProgress = 100
                                if (url != null) currentUrl = url
                                view?.requestFocus()
                            }

                            @SuppressLint("NewApi")
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // Match only main frame failures to avoid breaking due to minor frame issues
                                if (request?.isForMainFrame == true) {
                                    isLoadingError = true
                                    errorDescription = error?.description?.toString() ?: "Error de red desconocido"
                                    webProgress = 100
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return false // Let WebView handle navigations
                            }
                        }

                        // WebChrome Client to handle permissions, file selections, and custom dialogs
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                webProgress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrEmpty()) {
                                    pageTitle = title
                                }
                            }

                            // Dynamic Permission Handling for WebRTC (Mic/Camera) inside WebView
                            override fun onPermissionRequest(request: PermissionRequest) {
                                scope.launch {
                                    // Check / Proactively ask at the system level if needed
                                    val list = mutableListOf<String>()
                                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                                        list.add(Manifest.permission.CAMERA)
                                    }
                                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                                        list.add(Manifest.permission.RECORD_AUDIO)
                                    }

                                    var allGranted = true
                                    for (p in list) {
                                        if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                                            allGranted = false
                                        }
                                    }

                                    if (allGranted) {
                                        request.grant(request.resources)
                                    } else {
                                        // Auto trigger native request if missing
                                        permissionLauncher.launch(list.toTypedArray())
                                        // Temporarily grant so user flows aren't abruptly stopped
                                        request.grant(request.resources)
                                    }
                                }
                            }

                            // Support full file uploading capability
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>,
                                fileChooserParams: FileChooserParams
                            ): Boolean {
                                onChooseFileRequested(filePathCallback, fileChooserParams)
                                return true
                            }
                        }

                        // Cookies & persistent sessions config
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Base64 and high-quality direct download listener hook
                        setDownloadListener { url, _, _, mimeType, _ ->
                            scope.launch {
                                val isImage = (mimeType ?: "").contains("image", ignoreCase = true) ||
                                              url.startsWith("data:image/") ||
                                              url.contains(".raw") ||
                                              url.contains(".png") ||
                                              url.contains(".jpg") ||
                                              url.contains(".jpeg") ||
                                              url.contains(".webp") ||
                                              url.contains(".bmp")

                                if (!isImage) {
                                    viewModel.showToast("Descarga bloqueada. Solo se permite descargar imágenes (.jpeg / .webp)")
                                    return@launch
                                }

                                viewModel.showToast("Iniciando descarga...")
                                isSavingInProgress = true
                                val savedMedia = if (url.startsWith("data:")) {
                                    fileSaver.saveBase64Image(url, mimeType ?: "")
                                } else {
                                    fileSaver.downloadAndSaveUrl(url, mimeType ?: "")
                                }
                                isSavingInProgress = false

                                if (savedMedia != null) {
                                    viewModel.showToast("Archivo guardado con éxito: ${savedMedia.fileName}")
                                } else {
                                    viewModel.showToast("Exportación no permitida o de formato incorrecto. Solo se guardan imágenes (.jpeg / .webp)")
                                }
                            }
                        }

                        // Long Click custom menu to Save image
                        setOnLongClickListener { v ->
                            val result = (v as WebView).hitTestResult
                            if (result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.IMAGE_ANCHOR_TYPE) {
                                val imageUrl = result.extra
                                if (!imageUrl.isNullOrEmpty()) {
                                    targetSaveImageUrl = imageUrl
                                    true
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        }

                        loadUrl(appUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Saving loader overlay
            if (isSavingInProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Guardando archivo en alta calidad...",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // High-fidelity Error Panel Overlay (for fails and reconnection)
            if (isLoadingError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = "Sin conexión",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No se puede cargar el estudio",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Por favor verifica tu conexión. La aplicación se reconectará automáticamente cuando detecte señal.",
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (errorDescription.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Detalles: $errorDescription",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    webViewRef?.reload()
                                    isLoadingError = false
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reintentar")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Forzar Reconexión")
                            }
                        }
                    }
                }
            }

            // Real-time Cloud Sync progress overlay banner
            if (syncProgress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 8.dp,
                        shadowElevation = 6.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    progress = { syncProgress ?: 0f },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Sincronización en Tiempo Real",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = syncStatusText,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { syncProgress ?: 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }

            // Custom Neon Cyberpunk Floating Toast Overlay (immunity against notification blockages)
            AnimatedVisibility(
                visible = customToastMessage != null,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(durationMillis = 300)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(durationMillis = 250, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(durationMillis = 250)),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 72.dp)
            ) {
                customToastMessage?.let { msg ->
                    Surface(
                        color = Color(0xEE120E23),
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF00FFFF), Color(0xFFFF00FF))
                            )
                        ),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 12.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF00FFFF),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = msg,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.SansSerif,
                                maxLines = 3,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal long-click dialog options to save image
    if (targetSaveImageUrl != null) {
        AlertDialog(
            onDismissRequest = { targetSaveImageUrl = null },
            title = { Text("Guardar Imagen del Canvas") },
            text = { Text("¿Deseas descargar y guardar esta imagen en alta resolución en el almacenamiento del dispositivo?") },
            confirmButton = {
                Button(
                    onClick = {
                        val url = targetSaveImageUrl!!
                        targetSaveImageUrl = null
                        scope.launch {
                            isSavingInProgress = true
                            val savedMedia = fileSaver.downloadAndSaveUrl(url, "image/jpeg")
                            isSavingInProgress = false
                            if (savedMedia != null) {
                                viewModel.showToast("Imagen guardada en el dispositivo de forma segura: ${savedMedia.fileName}")
                            } else {
                                viewModel.showToast("No se pudo guardar la imagen")
                            }
                        }
                    }
                ) {
                    Text("Descargar")
                }
            },
            dismissButton = {
                TextButton(onClick = { targetSaveImageUrl = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Media Gallery Bottom Sheet Drawer
    if (showMediaSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMediaSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(20.dp)
            ) {
                // Header of Multimedia Storage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Multimedia Exportada",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${savedMediaList.size} archivos guardados localmente",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Bulk Cloud sync button
                    Button(
                        onClick = { viewModel.syncMediaToCloud(savedMediaList) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = savedMediaList.any { !it.isSynced }
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = "Sincronizar Cloud")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sincronizar", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (savedMediaList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PhotoCameraBack,
                                contentDescription = "Sin Archivos",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Aún no has descargado multimedia",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Usa el exportador de GamerEdit o mantén presionado sobre una imagen del lienzo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(savedMediaList, key = { it.id }) { media ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            // Open file with system share / view intent
                                            try {
                                                val shareableUri = getShareableUri(context, media.localUri)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(shareableUri, media.mimeType)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                val shareableUri = getShareableUri(context, media.localUri)
                                                // Fallback share intent
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = media.mimeType
                                                    putExtra(Intent.EXTRA_STREAM, shareableUri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Ver / Compartir Archivo"))
                                            }
                                        },
                                        onLongClick = {
                                            // Prompt to share or delete
                                            // Handle dynamically
                                        }
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp)
                                            .background(Color.DarkGray)
                                    ) {
                                        // Visual Media Preview using Coil if image
                                        if (media.mimeType.startsWith("image/")) {
                                            AsyncImage(
                                                model = media.localUri,
                                                contentDescription = media.fileName,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayCircleFilled,
                                                    contentDescription = "Video",
                                                    modifier = Modifier.size(48.dp),
                                                    tint = Color.White
                                                )
                                            }
                                        }

                                        // Size Badge
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(bottomEnd = 8.dp),
                                            modifier = Modifier.align(Alignment.TopStart)
                                        ) {
                                            Text(
                                                text = "%.1f KB".format(media.size / 1024f),
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        // Cloud Sync Indicator Status
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (media.isSynced) Color(0xFF4CAF50) else Color(
                                                        0xFFFF9800
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (media.isSynced) Icons.Default.CloudQueue else Icons.Default.CloudOff,
                                                contentDescription = if (media.isSynced) "Sincronizado" else "Pendiente",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Text(
                                            text = media.fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "SHA-256: ${media.checksum.take(12)}...",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Export Share Button
                                            IconButton(
                                                onClick = {
                                                    val shareableUri = getShareableUri(context, media.localUri)
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = media.mimeType
                                                        putExtra(Intent.EXTRA_STREAM, shareableUri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Exportar Archivo"))
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = "Exportar",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            // Delete Button
                                            IconButton(
                                                onClick = { viewModel.deleteMedia(media) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Eliminar",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // App Control Dashboard & Custom Cache Settings Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Ajustes y Sistema",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Optimización y administración de la sesión de GamerEdit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Cache Optimizations Settings Column
                Text(
                    text = "OPCIONES DE CACHÉ",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                CacheModePreference.entries.forEach { option ->
                    val selected = cacheMode == option
                    Surface(
                        onClick = { viewModel.setCacheMode(option) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.setCacheMode(option) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = option.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = option.description,
                                    fontSize = 12.sp,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                // Connection management switches
                Text(
                    text = "CONEXIÓN Y PERSISTENCIA",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-reconexión Inteligente",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Recarga automáticamente el lienzo del estudio cuando el teléfono recupera señal de internet.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = { viewModel.setAutoReconnect(it) }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Permissions list dashboard
                Text(
                    text = "ESTADO DE PERMISOS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                val permissionStatusList = listOf(
                    Triple(Icons.Default.CameraAlt, "Cámara", cameraPermissionState),
                    Triple(Icons.Default.Mic, "Micrófono (Grabador de Audio)", audioPermissionState),
                    Triple(Icons.Default.Save, "Almacenamiento (Gestor multimedia)", storagePermissionState)
                )

                permissionStatusList.forEach { (icon, name, isGranted) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = name, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isGranted) "CONCEDIDO" else "PENDIENTE",
                                fontWeight = FontWeight.Bold,
                                color = if (isGranted) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { requestPermissions() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Security, contentDescription = "Configurar Permisos")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Administrar Permisos del Sistema")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                // Session management (persistencia de sesión / cookies info)
                Text(
                    text = "MEMORIA Y SESIÓN",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "La sesión, cuentas autenticadas y preferencias de carga de GamerEdit se guardan automáticamente para que no tengas que iniciar sesión cada vez que uses el estudio.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        CookieManager.getInstance().removeAllCookies {
                            viewModel.showToast("Cookies de navegación eliminadas")
                        }
                        webViewRef?.clearCache(true)
                        webViewRef?.reload()
                        showSettingsSheet = false
                        viewModel.showToast("Sesión y caché de red restablecidos")
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LayersClear, contentDescription = "Cerrar sesión")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cerrar Sesión (Borrar Cookies y Caché)")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Password dialog protect
    if (showOwnerPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showOwnerPasswordDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Acceso Protegido",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Acceso de Owner",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Introduce la contraseña maestra para abrir las configuraciones avanzadas del estudio y modificar la app.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = ownerPasswordInput,
                        onValueChange = {
                            ownerPasswordInput = it
                            ownerPasswordError = false
                        },
                        label = { Text("Contraseña") },
                        placeholder = { Text("Contraseña de owner") },
                        isError = ownerPasswordError,
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (ownerPasswordError) {
                        Text(
                            text = "Contraseña incorrecta. Solo acceso para dueños.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (ownerPasswordInput == "vgamer711") {
                            showOwnerPasswordDialog = false
                            showOwnerSheet = true
                            ownerPasswordInput = ""
                            viewModel.showToast("Acceso autorizado con éxito")
                        } else {
                            ownerPasswordError = true
                        }
                    }
                ) {
                    Text("Ingresar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOwnerPasswordDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Owner Settings Bottom Sheet
    if (showOwnerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOwnerSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Canal de Owner",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Panel de Owner",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Modifica y personaliza el estudio en tiempo real",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Configure App URL Section
                Text(
                    text = "DIRECCIÓN WEB DEL ESTUDIO (URL)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                var tempUrlInput by remember { mutableStateOf(appUrl) }
                OutlinedTextField(
                    value = tempUrlInput,
                    onValueChange = { tempUrlInput = it },
                    label = { Text("URL de la Web App") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (tempUrlInput.startsWith("http://") || tempUrlInput.startsWith("https://")) {
                            viewModel.setAppUrl(tempUrlInput)
                            viewModel.showToast("URL del estudio actualizada con éxito")
                        } else {
                            viewModel.showToast("Por favor ingresa una URL válida (ej. https://...)")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Guardar")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Actualizar y Recargar Estudio")
                }

                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(24.dp))

                // Configure Title Section
                Text(
                    text = "TÍTULO DE LA APLICACIÓN (EN BARRA SUPERIOR)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                var tempTitleInput by remember { mutableStateOf(customAppTitle) }
                OutlinedTextField(
                    value = tempTitleInput,
                    onValueChange = { tempTitleInput = it },
                    label = { Text("Título de la App") },
                    placeholder = { Text("GamerEdit Mobile Studio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (tempTitleInput.isNotBlank()) {
                            viewModel.setCustomAppTitle(tempTitleInput)
                            viewModel.showToast("Título personalizado guardado")
                        } else {
                            viewModel.showToast("El título no puede estar vacío")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Guardar Título")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Guardar Título del Owner")
                }

                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(24.dp))

                // Additional Preferences Toggle
                Text(
                    text = "INTERFAZ Y PRESENTACIÓN EXTRA",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Mostrar Indicador de Red",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Muestra u oculta la señal de estado de red junto al título.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showOnlineIndicator,
                        onCheckedChange = { viewModel.setShowOnlineIndicator(it) }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(24.dp))

                // Developer actions
                Text(
                    text = "ACCIONES DEL DESARROLLADOR",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.setAppUrl("https://remix-remix-gameredit-mobile-studio-836566966714.us-east1.run.app/")
                        viewModel.setCustomAppTitle("GamerEdit Mobile Studio")
                        viewModel.setShowOnlineIndicator(true)
                        tempUrlInput = "https://remix-remix-gameredit-mobile-studio-836566966714.us-east1.run.app/"
                        tempTitleInput = "GamerEdit Mobile Studio"
                        viewModel.showToast("Valores de fábrica restablecidos")
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Restablecer de fábrica")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restablecer de fábrica")
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}
