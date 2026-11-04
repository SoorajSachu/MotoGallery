package com.sooraj.micagallery

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.app.Activity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val date: Long,
    val bucket: String,
    val isVideo: Boolean
)

private enum class Tab { PHOTOS, ALBUMS, FAVOURITES }

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        Coil.setImageLoader(
            ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
        )
        setContent { MicaGalleryApp() }
    }

    /** Ask the system for the highest refresh rate (e.g. 120 Hz) at the current resolution. */
    private fun requestHighRefreshRate() {
        val d = display ?: return
        val current = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }
}

@Composable
private fun MicaGalleryApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("mica_gallery", Context.MODE_PRIVATE) }
    var media by remember { mutableStateOf(emptyList<MediaItem>()) }
    var favourites by remember { mutableStateOf(prefs.getStringSet("favourites", emptySet())?.toSet() ?: emptySet()) }
    var tab by remember { mutableStateOf(Tab.PHOTOS) }
    var search by remember { mutableStateOf("") }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var pendingDelete by remember { mutableStateOf(emptySet<Long>()) }
    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }
    var loading by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 34) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) else if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = result.values.any { it } || hasMediaPermission(context)
    }

    // Loads media off the main thread so scrolling/UI stays smooth.
    fun refresh() {
        if (!hasMediaPermission(context)) return
        scope.launch {
            loading = true
            val loaded = withContext(Dispatchers.IO) { loadMedia(context) }
            media = loaded
            val ids = loaded.map { it.id }.toSet()
            selectedIds = selectedIds.intersect(ids)
            loading = false
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val removed = pendingDelete.map { it.toString() }.toSet()
            favourites = favourites - removed
            prefs.edit().putStringSet("favourites", favourites).apply()
            selectedIds = emptySet()
            selected = null
            refresh()
        }
        pendingDelete = emptySet()
    }

    fun deleteItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        try {
            val sender = MediaStore.createTrashRequest(
                context.contentResolver, items.map { it.uri }, true
            ).intentSender
            pendingDelete = items.map { it.id }.toSet()
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't delete these items", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = ArrayList(items.map { it.uri })
        val mime = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = mime
        send.clipData = ClipData.newUri(context.contentResolver, "media", uris[0]).also { clip ->
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share media"))
    }

    fun toggleFavourites(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id.toString() }
        val allFav = ids.all { it in favourites }
        favourites = if (allFav) favourites - ids.toSet() else favourites + ids
        prefs.edit().putStringSet("favourites", favourites).apply()
    }

    LaunchedEffect(permissionGranted) { if (permissionGranted) refresh() }

    // Stable callbacks (read fresh state through delegates) so grid tiles don't recompose needlessly.
    val onTileClick = remember {
        { item: MediaItem ->
            if (selectedIds.isNotEmpty()) selectedIds = selectedIds.toggle(item.id) else selected = item
        }
    }
    val onTileLongClick = remember {
        { item: MediaItem ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            selectedIds = selectedIds.toggle(item.id)
        }
    }

    val visible = remember(media, tab, favourites, search, selectedAlbum) {
        media.filter { item ->
            val matchesTab = when (tab) {
                Tab.PHOTOS -> true
                Tab.ALBUMS -> selectedAlbum == null || item.bucket == selectedAlbum
                Tab.FAVOURITES -> item.id.toString() in favourites
            }
            matchesTab && (search.isBlank() ||
                item.name.contains(search, true) || item.bucket.contains(search, true))
        }
    }
    val albums = remember(media) { media.groupBy { it.bucket }.toList().sortedBy { it.first.lowercase() } }
    val selectedItems = remember(media, selectedIds) { media.filter { it.id in selectedIds } }

    BackHandler(enabled = selectionMode && selected == null) { selectedIds = emptySet() }
    BackHandler(enabled = !selectionMode && selected == null && selectedAlbum != null) { selectedAlbum = null }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF0B1018),
        surface = Color(0xCC1B2532),
        primary = Color(0xFFB9D7FF),
        onPrimary = Color(0xFF10243B),
        onBackground = Color(0xFFF1F5FA),
        onSurface = Color(0xFFF1F5FA),
        secondary = Color(0xFFB9C8D9)
    )) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF1F5FA)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF17283B), Color(0xFF0B1018), Color(0xFF17202D)))
                )
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Spacer(Modifier.height(14.dp))
                    if (selectionMode) {
                        SelectionBar(
                            count = selectedIds.size,
                            allFavourite = selectedItems.isNotEmpty() &&
                                selectedItems.all { it.id.toString() in favourites },
                            onClose = { selectedIds = emptySet() },
                            onSelectAll = { selectedIds = visible.map { it.id }.toSet() },
                            onFavourite = { toggleFavourites(selectedItems) },
                            onShare = { shareItems(selectedItems) },
                            onDelete = { deleteItems(selectedItems) }
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("MICA", fontSize = 12.sp, letterSpacing = 3.sp,
                                    color = Color(0xFF9DB5CE), fontWeight = FontWeight.SemiBold)
                                Text(selectedAlbum ?: "Gallery",
                                    fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Surface(
                                color = Color(0x442B3C50), shape = CircleShape,
                                modifier = Modifier.size(46.dp).clickable { refresh() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = Color(0x552B3C50),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 15.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, tint = Color(0xFFB8C8D9))
                            Spacer(Modifier.width(10.dp))
                            TextField(
                                value = search, onValueChange = { search = it },
                                placeholder = { Text("Search photos and albums", color = Color(0xFF91A2B5)) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = Color(0xFFB9D7FF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                                Icon(Icons.Rounded.Close, "Clear search")
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    when {
                        !permissionGranted -> PermissionPrompt { permissionLauncher.launch(permissions) }
                        loading && media.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        tab == Tab.ALBUMS && selectedAlbum == null -> {
                            if (albums.isEmpty()) EmptyState("No photos yet", "Your camera roll will appear here.")
                            else LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 130.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(albums, key = { it.first }, contentType = { "album" }) { (name, items) ->
                                    AlbumTile(name, items.size, items.firstOrNull()?.uri) {
                                        selectedAlbum = name
                                    }
                                }
                            }
                        }
                        visible.isEmpty() -> EmptyState(
                            if (tab == Tab.FAVOURITES) "Nothing saved yet" else "No media found",
                            if (tab == Tab.FAVOURITES) "Tap the heart on a photo to keep it here." else "Try another search or add some photos."
                        )
                        else -> {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${visible.size} items", color = Color(0xFF9FB0C2), fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                if (selectedAlbum != null) TextButton(onClick = { selectedAlbum = null }) {
                                    Text("All albums")
                                }
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = 130.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                items(visible, key = { it.id }, contentType = { "media" }) { item ->
                                    MediaTile(
                                        item = item,
                                        selected = item.id in selectedIds,
                                        selectionMode = selectionMode,
                                        onClick = onTileClick,
                                        onLongClick = onTileLongClick
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating translucent bottom navigation (the only tab bar)
                Surface(
                    color = Color(0xCC1B2939),
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 18.dp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, bottom = 18.dp)
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        NavItem(Icons.Rounded.PhotoLibrary, "Photos", tab == Tab.PHOTOS) {
                            tab = Tab.PHOTOS; selectedAlbum = null
                        }
                        NavItem(Icons.Rounded.Folder, "Albums", tab == Tab.ALBUMS) {
                            tab = Tab.ALBUMS; selectedAlbum = null
                        }
                        NavItem(Icons.Rounded.Favorite, "Saved", tab == Tab.FAVOURITES) {
                            tab = Tab.FAVOURITES; selectedAlbum = null
                        }
                    }
                }
            }

            selected?.let { item ->
                MediaViewer(
                    item = item,
                    favourite = item.id.toString() in favourites,
                    onToggleFavourite = { toggleFavourites(listOf(item)) },
                    onShare = { shareItems(listOf(item)) },
                    onDelete = { deleteItems(listOf(item)) },
                    onClose = { selected = null }
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int, allFavourite: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit,
    onFavourite: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Cancel selection") }
        Text("$count selected", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
        IconButton(onClick = onFavourite) {
            Icon(if (allFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                "Favourite", tint = if (allFavourite) Color(0xFFFF7D91) else LocalContentColor.current)
        }
        IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share") }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                    active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)) {
        Icon(icon, null, tint = if (active) Color(0xFFB9D7FF) else Color(0xFF9AABBD))
        Text(label, fontSize = 10.sp,
            color = if (active) Color(0xFFB9D7FF) else Color(0xFF9AABBD))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaItem, selected: Boolean, selectionMode: Boolean,
    onClick: (MediaItem) -> Unit, onLongClick: (MediaItem) -> Unit
) {
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(Color(0xFF263546))
        .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) })) {
        AsyncImage(model = item.uri, contentDescription = item.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            Surface(color = Color(0x99000000), shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp).size(25.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
        }
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x663A7BD5)))
        if (selectionMode) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) Color(0xFFB9D7FF) else Color(0xDDFFFFFF),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
            )
        }
    }
}

@Composable
private fun AlbumTile(name: String, count: Int, cover: Uri?, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x442B3C50))
        .clickable(onClick = onClick).padding(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.12f).clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF263546))) {
            if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(9.dp))
        Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp))
        Text("$count items", color = Color(0xFF9FB0C2), fontSize = 11.sp,
            modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PhotoLibrary, null, tint = Color(0xFF71869B), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF9FB0C2), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
            Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(54.dp),
                tint = Color(0xFFB9D7FF))
            Spacer(Modifier.height(12.dp))
            Text("Your photos, beautifully organised", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Allow media access so Mica Gallery can display photos and videos stored on this device.",
                color = Color(0xFFB1C0D0), fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onGrant) { Text("Allow access") }
        }
    }
}

@Composable
private fun MediaViewer(item: MediaItem, favourite: Boolean, onToggleFavourite: () -> Unit,
                        onShare: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05080D))) {
            if (item.isVideo) {
                VideoPlayer(item.uri, Modifier.fillMaxSize())
            } else {
                AsyncImage(model = item.uri, contentDescription = item.name,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(item.date)),
                        color = Color(0xFFB7C2CF), fontSize = 11.sp)
                }
                IconButton(onClick = onToggleFavourite) {
                    Icon(if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        "Favourite", tint = if (favourite) Color(0xFFFF7D91) else Color.White)
                }
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                controllerShowTimeoutMs = 2500
            }
        },
        update = { it.player = player }
    )
}

private fun hasMediaPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 34) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    } else if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

private fun loadMedia(context: Context): List<MediaItem> {
    val result = mutableListOf<MediaItem>()
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    val args = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )
    val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
    context.contentResolver.query(collection, projection, selection, args, sort)?.use {
        val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val dateCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val typeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val bucketCol = it.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
        while (it.moveToNext()) {
            val id = it.getLong(idCol)
            val isVideo = it.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                       else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            result += MediaItem(id, ContentUris.withAppendedId(base, id),
                it.getString(nameCol) ?: "Untitled",
                it.getLong(dateCol) * 1000L,
                if (bucketCol >= 0) it.getString(bucketCol) ?: "Other" else "Other",
                isVideo)
        }
    }
    return result
}
