package com.moblized.jellydash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import com.moblized.jellydash.car.describe
import com.moblized.jellydash.downloads.DownloadEntry
import com.moblized.jellydash.downloads.DownloadPlanner
import com.moblized.jellydash.jellyfin.Browse
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.Library
import com.moblized.jellydash.jellyfin.Sort
import com.moblized.jellydash.jellyfin.Ticks
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Where you are in the phone's Library tab. */
sealed interface Page {
    data object Libraries : Page
    data class Shelf(val title: String, val where: Browse) : Page
    data class Show(val show: Item) : Page
    data class Title(val item: Item) : Page
}

/** What the Library tab needs from the rest of the app. */
class LibraryActions(
    val download: (List<Item>) -> Unit,
    val remove: (String) -> Unit,
)

@Composable
fun LibraryTab(stack: List<Page>, onOpen: (Page) -> Unit, onBack: () -> Unit, downloads: List<DownloadEntry>, actions: LibraryActions) {
    BackHandler(enabled = stack.size > 1, onBack = onBack)
    val byId = remember(downloads) { downloads.associateBy { it.item.id } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val page = stack.last()) {
            Page.Libraries -> LibrariesPage(onOpen)
            is Page.Shelf -> ShelfPage(page, onOpen, onBack)
            is Page.Show -> ShowPage(page.show, onBack, byId, actions, onOpen)
            is Page.Title -> TitlePage(page.item, onBack, byId[page.item.id], actions)
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
internal fun Poster(item: Item, modifier: Modifier = Modifier, height: Int = 400) {
    val context = LocalContext.current
    val model = remember(item.id) {
        Graph.downloads(context).posterFile(item.id) ?: Graph.jellyfin(context).imageUrl(item, height)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (item.kind == Kind.Episode || item.kind == Kind.Series || item.kind == Kind.Season) R.drawable.ic_tv else R.drawable.ic_movie),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(28.dp),
        )
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun Problem(message: String, retry: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = retry) { Text("Try again") }
}

@Composable
private fun LibrariesPage(onOpen: (Page) -> Unit) {
    val context = LocalContext.current
    var libs by remember { mutableStateOf<List<Library>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            libs = Graph.jellyfin(context).libraries()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load your libraries"
        }
    }
    TopBar("Library", null)
    val l = libs
    when {
        error != null && l == null -> Problem(error!!) { attempt++ }
        l == null -> Loading()
        else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Download movies and shows here, then play them in the truck with no signal.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(l, key = { it.id }) { lib ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onOpen(Page.Shelf(lib.name, Browse(lib.id, lib.collectionType))) }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Brand.gradient), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(
                                when (lib.collectionType) {
                                    "tvshows" -> R.drawable.ic_tv
                                    "movies" -> R.drawable.ic_movie
                                    "boxsets" -> R.drawable.ic_collection
                                    else -> R.drawable.ic_folder
                                },
                            ),
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(lib.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ShelfPage(page: Page.Shelf, onOpen: (Page) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var sort by remember(page) { mutableStateOf(Sort.Name) }
    var attempt by remember(page) { mutableStateOf(0) }
    val items = remember(page, sort, attempt) { mutableStateListOf<Item>() }
    var total by remember(page, sort, attempt) { mutableStateOf(-1) }
    var error by remember(page, sort, attempt) { mutableStateOf<String?>(null) }
    var loadingMore by remember(page, sort, attempt) { mutableStateOf(false) }
    val grid = rememberLazyGridState()

    suspend fun more() {
        if (loadingMore || (total >= 0 && items.size >= total)) return
        loadingMore = true
        try {
            val p = Graph.jellyfin(context).browse(page.where, sort, items.size, 60)
            items.addAll(p.items)
            total = p.total
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this"
        }
        loadingMore = false
    }

    LaunchedEffect(page, sort, attempt) { more() }
    LaunchedEffect(grid, page, sort, attempt) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= items.size - 18 }
            .collect { more() }
    }

    TopBar(page.title, onBack)
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = sort == Sort.Name, onClick = { sort = Sort.Name }, label = { Text("A–Z") })
        FilterChip(selected = sort == Sort.Newest, onClick = { sort = Sort.Newest }, label = { Text("Recently added") })
    }
    when {
        error != null && items.isEmpty() -> Problem(error!!) { attempt++ }
        total < 0 -> Loading()
        items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            state = grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
                Column(Modifier.clickable { onOpen(pageFor(item)) }) {
                    Poster(item, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Spacer(Modifier.height(6.dp))
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                    item.year?.let { Text("$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

private fun pageFor(item: Item): Page = when {
    item.kind == Kind.Series -> Page.Show(item)
    item.kind.playable -> Page.Title(item)
    else -> Page.Shelf(item.name, Browse(item.id, "folders"))
}

/** Download, its progress, or "On your phone". */
@Composable
fun DownloadControl(item: Item, entry: DownloadEntry?, actions: LibraryActions, compact: Boolean = false) {
    when {
        entry == null -> if (compact) {
            IconButton(onClick = { actions.download(listOf(item)) }) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            Button(onClick = { actions.download(listOf(item)) }) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }
        }
        entry.done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = "Downloaded", tint = Brand.good)
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                Text("On your phone" + (entry.size?.let { " · ${DownloadPlanner.size(it)}" } ?: ""), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { actions.remove(item.id) }) { Text("Remove") }
            }
        }
        entry.failed -> if (compact) {
            IconButton(onClick = { actions.download(listOf(item)) }) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = "Try again", tint = MaterialTheme.colorScheme.error)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Download failed", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { actions.download(listOf(item)) }) { Text("Try again") }
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                if (entry.percent > 0f) {
                    CircularProgressIndicator(progress = { entry.percent / 100f }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
            if (!compact) {
                Spacer(Modifier.width(10.dp))
                Text(statusOf(entry), color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { actions.remove(item.id) }) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
            }
        }
    }
}

fun statusOf(e: DownloadEntry): String = when {
    e.done -> "On your phone" + (e.size?.let { " · ${DownloadPlanner.size(it)}" } ?: "")
    e.failed -> "Download failed"
    e.waitingForWifi -> "Waiting for Wi-Fi"
    e.state == androidx.media3.exoplayer.offline.Download.STATE_QUEUED -> "Waiting"
    e.percent > 0f -> "Downloading ${e.percent.toInt()}%" + (e.size?.let { " of ${DownloadPlanner.size(it)}" } ?: "")
    else -> if (e.converted) "Your server is converting it" else "Starting"
}

@Composable
private fun TitlePage(start: Item, onBack: () -> Unit, entry: DownloadEntry?, actions: LibraryActions) {
    val context = LocalContext.current
    var shown by remember(start.id) { mutableStateOf(start) }
    LaunchedEffect(start.id) { runCatching { Graph.jellyfin(context).item(start.id) }.getOrNull()?.let { shown = it } }
    TopBar(if (shown.kind == Kind.Episode) shown.seriesName ?: shown.name else shown.name, onBack)
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        item {
            Row {
                Poster(shown, Modifier.width(130.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(shown.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(describe(shown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shown.officialRating?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(18.dp))
            DownloadControl(shown, entry, actions)
            if (entry == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Plays in the truck from your phone, at full quality and with no signal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.overview?.let {
                Spacer(Modifier.height(18.dp))
                Text(it, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun ShowPage(show: Item, onBack: () -> Unit, byId: Map<String, DownloadEntry>, actions: LibraryActions, onOpen: (Page) -> Unit) {
    val context = LocalContext.current
    var episodes by remember(show.id) { mutableStateOf<List<Item>?>(null) }
    var error by remember(show.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(show.id, attempt) {
        error = null
        try {
            episodes = Graph.jellyfin(context).episodes(show.id, null)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this show"
        }
    }
    TopBar(show.name, onBack)
    val eps = episodes
    when {
        error != null && eps == null -> Problem(error!!) { attempt++ }
        eps == null -> Loading()
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            val seasons = eps.groupBy { it.parentIndex }
            for ((season, list) in seasons) {
                item(key = "season-$season") {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (season == null || season == 0) "Specials" else "Season $season",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        val missing = list.filter { byId[it.id] == null }
                        if (missing.isNotEmpty()) {
                            TextButton(onClick = { actions.download(missing) }) {
                                Text(if (missing.size == list.size) "Download season" else "Download the rest")
                            }
                        }
                    }
                }
                items(list, key = { it.id }) { ep ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(Page.Title(ep)) }
                            .padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Poster(ep, Modifier.width(96.dp).aspectRatio(16f / 9f), height = 200)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${ep.index ?: ""}. ${ep.name}", maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                            val e = byId[ep.id]
                            Text(
                                if (e != null && !e.done) statusOf(e) else listOfNotNull(ep.runTimeMs?.let { Ticks.duration(it) }, "Watched".takeIf { ep.played }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DownloadControl(ep, byId[ep.id], actions, compact = true)
                    }
                }
                item(key = "div-$season") { HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)) }
            }
        }
    }
}

