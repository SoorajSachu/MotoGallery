package com.sooraj.micagallery

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

private sealed interface GridEntry {
    data class Header(val label: String) : GridEntry
    data class Tile(val item: MediaItem) : GridEntry
}

private const val ALL_PHOTOS = "All photos"

private fun dateLabel(ms: Long, today: LocalDate): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val LIGHTROOM_PACKAGE = "com.adobe.lrmobile"

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // "auto" = light or dark system-bar icons, following the system theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        Coil.setImageLoader(
            ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
        )
        setContent { MicaTheme { MicaGalleryApp() } }
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

/** Material You: uses the wallpaper-based system colours (Android 12+) and follows light/dark mode. */
@Composable
private fun MicaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------- Liquid glass helpers ----------

@Composable
private fun rememberGlassStyle(): HazeStyle {
    val cs = MaterialTheme.colorScheme
    return remember(cs) {
        HazeStyle(
            backgroundColor = cs.surface,
            tint = HazeTint(cs.surface.copy(alpha = 0.40f)),
            blurRadius = 26.dp,
            noiseFactor = 0.03f,
            fallbackTint = HazeTint(cs.surface.copy(alpha = 0.90f))
        )
    }
}

/** Blurred, tinted glass with a specular edge highlight. Falls back to a scrim when [state] is null. */
private fun Modifier.glass(state: HazeState?, style: HazeStyle, shape: Shape, scrim: Color): Modifier {
    val base = this.clip(shape)
    val blurred = if (state != null) base.hazeChild(state, style) else base.background(scrim)
    return blurred
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)))
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.28f))),
            shape
        )
}

@Composable
private fun MicaGalleryApp() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
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
    var topBarPx by remember { mutableIntStateOf(0) }
    val selectionMode = selectedIds.isNotEmpty()
    val hazeState = remember { HazeState() }
    val glassStyle = rememberGlassStyle()
    val topInset = with(density) { topBarPx.toDp() } + 8.dp

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

    // Camera is always the first album and the default view; "All photos" is the last album entry.
    val cameraBucket = remember(media) { media.firstOrNull { it.bucket.equals("Camera", true) }?.bucket }
    val visible = remember(media, tab, favourites, search, selectedAlbum, cameraBucket) {
        media.filter { item ->
            val matchesTab = when (tab) {
                Tab.PHOTOS -> cameraBucket == null || item.bucket == cameraBucket
                Tab.ALBUMS -> selectedAlbum == null || selectedAlbum == ALL_PHOTOS || item.bucket == selectedAlbum
                Tab.FAVOURITES -> item.id.toString() in favourites
            }
            if (search.isBlank()) matchesTab else {
                // Searching looks through everything, not just the current album.
                (item.name.contains(search, true) || item.bucket.contains(search, true)) &&
                    (tab != Tab.FAVOURITES || item.id.toString() in favourites)
            }
        }
    }
    val albums = remember(media) {
        val grouped = media.groupBy { it.bucket }.toList()
        val camera = grouped.filter { it.first.equals("Camera", true) }
        val rest = grouped.filterNot { it.first.equals("Camera", true) }.sortedBy { it.first.lowercase() }
        camera + rest + listOf(ALL_PHOTOS to media)
    }
    val entries = remember(visible) {
        val today = LocalDate.now()
        buildList<GridEntry> {
            var last = ""
            for (item in visible) {
                val label = dateLabel(item.date, today)
                if (label != last) { add(GridEntry.Header(label)); last = label }
                add(GridEntry.Tile(item))
            }
        }
    }
    val screenTitle = when {
        tab == Tab.ALBUMS && selectedAlbum != null -> selectedAlbum.orEmpty()
        tab == Tab.PHOTOS -> cameraBucket ?: "Gallery"
        tab == Tab.ALBUMS -> "Albums"
        else -> "Saved"
    }
    val selectedItems = remember(media, selectedIds) { media.filter { it.id in selectedIds } }

    BackHandler(enabled = selectionMode && selected == null) { selectedIds = emptySet() }
    BackHandler(enabled = !selectionMode && selected == null && selectedAlbum != null) { selectedAlbum = null }

    CompositionLocalProvider(LocalContentColor provides cs.onBackground) {
        Box(Modifier.fillMaxSize().background(cs.background)) {

            // ---- Content: scrolls underneath the glass bars (this is the blur source) ----
            Box(
                Modifier.fillMaxSize()
                    .haze(hazeState)
                    .background(Brush.linearGradient(listOf(
                        cs.primaryContainer.copy(alpha = 0.45f).compositeOver(cs.background),
                        cs.background,
                        cs.tertiaryContainer.copy(alpha = 0.35f).compositeOver(cs.background)
                    )))
            ) {
                when {
                    !permissionGranted -> Box(Modifier.fillMaxSize().padding(top = topInset)) {
                        PermissionPrompt { permissionLauncher.launch(permissions) }
                    }
                    loading && media.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    tab == Tab.ALBUMS && selectedAlbum == null && search.isBlank() -> {
                        if (albums.isEmpty()) Box(Modifier.fillMaxSize().padding(top = topInset)) {
                            EmptyState("No photos yet", "Your camera roll will appear here.")
                        } else LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset, bottom = 130.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(albums, key = { it.first }, contentType = { "album" }) { (name, items) ->
                                AlbumTile(name, items.size, items.firstOrNull()?.uri) { selectedAlbum = name }
                            }
                        }
                    }
                    visible.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = topInset)) {
                        EmptyState(
                            if (tab == Tab.FAVOURITES) "Nothing saved yet" else "No media found",
                            if (tab == Tab.FAVOURITES) "Tap the heart on a photo to keep it here." else "Try another search or add some photos."
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = topInset, bottom = 130.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        item(key = "count", span = { GridItemSpan(maxLineSpan) }, contentType = "count") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${visible.size} items", color = cs.onSurfaceVariant, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                if (tab == Tab.ALBUMS && selectedAlbum != null) {
                                    TextButton(onClick = { selectedAlbum = null }) { Text("All albums") }
                                }
                            }
                        }
                        items(
                            entries,
                            key = { entry ->
                                when (entry) {
                                    is GridEntry.Header -> "h:" + entry.label
                                    is GridEntry.Tile -> entry.item.id
                                }
                            },
                            span = { entry ->
                                if (entry is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                            },
                            contentType = { entry -> if (entry is GridEntry.Header) "header" else "media" }
                        ) { entry ->
                            when (entry) {
                                is GridEntry.Header -> Text(
                                    entry.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                    modifier = Modifier.padding(start = 17.dp, end = 17.dp, top = 12.dp, bottom = 4.dp)
                                )
                                is GridEntry.Tile -> MediaTile(
                                    item = entry.item,
                                    selected = entry.item.id in selectedIds,
                                    selectionMode = selectionMode,
                                    onClick = onTileClick,
                                    onLongClick = onTileLongClick
                                )
                            }
                        }
                    }
                }
            }

            // ---- Glass top panel: title / selection actions + search ----
            Column(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { topBarPx = it.height }
                    .glass(hazeState, glassStyle,
                        RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), cs.surface.copy(alpha = 0.9f))
                    .statusBarsPadding()
                    .padding(bottom = 14.dp)
            ) {
                Spacer(Modifier.height(10.dp))
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
                                color = cs.primary, fontWeight = FontWeight.SemiBold)
                            Text(screenTitle,
                                fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier.size(46.dp).clip(CircleShape)
                                .background(cs.onSurface.copy(alpha = 0.08f))
                                .clickable { refresh() },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh") }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    TextField(
                        value = search, onValueChange = { search = it },
                        placeholder = { Text("Search photos and albums", color = cs.onSurfaceVariant) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = cs.primary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                        Icon(Icons.Rounded.Close, "Clear search")
                    }
                }
            }

            // ---- Floating glass bottom navigation ----
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp)
                    .glass(hazeState, glassStyle, RoundedCornerShape(30.dp), cs.surface.copy(alpha = 0.9f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

            // Full-screen viewer overlay (inside the root Box so it covers everything)
            selected?.let { item ->
                MediaViewer(
                    item = item,
                    favourite = item.id.toString() in favourites,
                    onToggleFavourite = { toggleFavourites(listOf(item)) },
                    onShare = { shareItems(listOf(item)) },
                    onEdit = { openInLightroom(context, item.uri) },
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
                "Favourite", tint = if (allFavourite) MaterialTheme.colorScheme.tertiary else LocalContentColor.current)
        }
        IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share") }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(22.dp))
            .background(if (active) cs.primaryContainer.copy(alpha = 0.75f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Icon(icon, null, tint = tint)
        Text(label, fontSize = 10.sp, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaItem, selected: Boolean, selectionMode: Boolean,
    onClick: (MediaItem) -> Unit, onLongClick: (MediaItem) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(cs.surfaceContainerHighest)
        .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) })) {
        AsyncImage(model = item.uri, contentDescription = item.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(25.dp)
                .clip(CircleShape).background(Color(0x99000000)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
        if (selected) Box(Modifier.fillMaxSize().background(cs.primary.copy(alpha = 0.35f)))
        if (selectionMode) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) cs.onPrimary else Color(0xDDFFFFFF),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                    .then(if (selected) Modifier.background(cs.primary, CircleShape) else Modifier)
            )
        }
    }
}

@Composable
private fun AlbumTile(name: String, count: Int, cover: Uri?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh.copy(alpha = 0.7f))
        .clickable(onClick = onClick).padding(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.12f).clip(RoundedCornerShape(13.dp))
            .background(cs.surfaceContainerHighest)) {
            if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(9.dp))
        Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp))
        Text("$count items", color = cs.onSurfaceVariant, fontSize = 11.sp,
            modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PhotoLibrary, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
            Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(54.dp), tint = cs.primary)
            Spacer(Modifier.height(12.dp))
            Text("Your photos, beautifully organised", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Allow media access so Mica Gallery can display photos and videos stored on this device.",
                color = cs.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onGrant) { Text("Allow access") }
        }
    }
}

// ---------- Full-screen viewer ----------

/**
 * Drawn as an overlay in the main window (not a Dialog), so it is truly edge-to-edge and the
 * system bars and insets behave exactly like the rest of the app.
 */
@Composable
private fun MediaViewer(
    item: MediaItem, favourite: Boolean, onToggleFavourite: () -> Unit, onShare: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit
) {
    val context = LocalContext.current
    val hazeState = remember { HazeState() }
    // The viewer is always dark, like most gallery apps, so photos are shown on black.
    val darkGlass = remember {
        HazeStyle(
            backgroundColor = Color.Black,
            tint = HazeTint(Color.Black.copy(alpha = 0.35f)),
            blurRadius = 28.dp,
            noiseFactor = 0.03f,
            fallbackTint = HazeTint(Color.Black.copy(alpha = 0.75f))
        )
    }
    val scrim = Color.Black.copy(alpha = 0.5f)
    var chromeVisible by remember { mutableStateOf(true) }
    val showChrome = chromeVisible || item.isVideo
    // Video renders on its own surface, which can't be blurred, so use a plain scrim there.
    val blurState: HazeState? = if (item.isVideo) null else hazeState

    BackHandler(onBack = onClose)

    // Light status/navigation icons while the black viewer is open; restored when it closes.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            prevStatus?.let { controller.isAppearanceLightStatusBars = it }
            prevNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            if (item.isVideo) {
                VideoPlayer(item.uri, Modifier.fillMaxSize().navigationBarsPadding())
            } else {
                ZoomableImage(item, Modifier.fillMaxSize()) { chromeVisible = !chromeVisible }
            }
        }

        AnimatedVisibility(
            visible = showChrome, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(52.dp).glass(blurState, darkGlass, CircleShape, scrim).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(
                    Modifier.weight(1f).height(52.dp)
                        .glass(blurState, darkGlass, RoundedCornerShape(26.dp), scrim)
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(item.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(item.date)),
                        color = Color(0xFFD0D6DD), fontSize = 11.sp)
                }
            }
        }

        AnimatedVisibility(
            visible = showChrome, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                Modifier.navigationBarsPadding()
                    .padding(bottom = if (item.isVideo) 120.dp else 20.dp)
                    .glass(blurState, darkGlass, RoundedCornerShape(32.dp), scrim)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ViewerAction(
                    if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    "Favourite", tint = if (favourite) Color(0xFFFF8FA3) else Color.White,
                    onClick = onToggleFavourite
                )
                ViewerAction(Icons.Rounded.Share, "Share", onClick = onShare)
                if (!item.isVideo) ViewerAction(Icons.Rounded.Edit, "Edit", onClick = onEdit)
                ViewerAction(Icons.Rounded.Delete, "Delete", onClick = onDelete)
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Icon(icon, label, tint = tint)
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

/** Pinch to zoom, drag to pan when zoomed, double-tap to zoom in/out, tap to hide the bars. */
@Composable
private fun ZoomableImage(item: MediaItem, modifier: Modifier, onTap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .onSizeChanged { box = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            val s0 = scale
                            val o0 = offset
                            val s1 = if (s0 > 1.05f) 1f else 2.5f
                            val center = Offset(box.width / 2f, box.height / 2f)
                            val o1 = if (s1 == 1f) Offset.Zero else clampOffset((center - tap) * (s1 - 1f), s1)
                            animate(0f, 1f, animationSpec = tween(260)) { p, _ ->
                                scale = s0 + (s1 - s0) * p
                                offset = Offset(o0.x + (o1.x - o0.x) * p, o0.y + (o1.y - o0.y) * p)
                            }
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    val center = Offset(box.width / 2f, box.height / 2f)
                    // Keep the point under the fingers fixed while scaling, then apply the pan.
                    offset = clampOffset(offset + (centroid - center) * (scale - newScale) + pan, newScale)
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            // Decode up to 4096px so zooming in stays sharp without blowing up memory.
            model = ImageRequest.Builder(context).data(item.uri).size(4096).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
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

// ---------- Lightroom ----------

/**
 * Sends a photo to Adobe Lightroom (Android "Open in Lr" is a share target).
 * Falls back to ACTION_EDIT, then to the Play Store if Lightroom isn't installed.
 */
private fun openInLightroom(context: Context, uri: Uri) {
    val installed = try {
        context.packageManager.getPackageInfo(LIGHTROOM_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
    if (!installed) {
        Toast.makeText(context, "Lightroom isn't installed", Toast.LENGTH_SHORT).show()
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$LIGHTROOM_PACKAGE"))
        try {
            context.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$LIGHTROOM_PACKAGE")))
        }
        return
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("photo", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage(LIGHTROOM_PACKAGE)
    }
    val edit = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(uri, "image/*")
        clipData = ClipData.newRawUri("photo", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        setPackage(LIGHTROOM_PACKAGE)
    }
    for (intent in listOf(send, edit)) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // try the next way of opening it
        } catch (e: SecurityException) {
            // try the next way of opening it
        }
    }
    Toast.makeText(context, "Lightroom couldn't open this photo", Toast.LENGTH_SHORT).show()
}

// ---------- Media loading ----------

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
