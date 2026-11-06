package com.moblized.jellydash.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moblized.jellydash.R
import com.moblized.jellydash.downloads.DownloadEntry
import com.moblized.jellydash.downloads.DownloadPlanner
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Kind

/** Everything saved on the phone, and what's still downloading. */
@Composable
fun DownloadsContent(
    entries: List<DownloadEntry>,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: (Item) -> Unit,
    poster: @Composable (Item, Modifier) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            val done = entries.filter { it.done }
            val bytes = done.sumOf { it.size ?: it.bytes }
            Text(
                if (done.isEmpty()) "Nothing saved yet" else "${done.size} on your phone · ${DownloadPlanner.size(bytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Only on Wi-Fi", color = MaterialTheme.colorScheme.onSurface)
                    Text("Saves your data plan. Downloads wait and pick up on Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = wifiOnly, onCheckedChange = onWifiOnly)
            }
        }
        if (entries.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Find a movie or show in Library and tap Download. Downloads play in the truck at full quality with no signal.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
        items(entries, key = { it.item.id }) { e ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val wide = e.item.kind == Kind.Episode
                poster(e.item, if (wide) Modifier.width(96.dp).aspectRatio(16f / 9f) else Modifier.width(54.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val title = if (wide) e.item.seriesName ?: e.item.name else e.item.name
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    if (wide) {
                        val code = listOfNotNull(e.item.parentIndex?.let { "S$it" }, e.item.index?.let { "E$it" }).joinToString("")
                        Text(listOf(code, e.item.name).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(statusOf(e), style = MaterialTheme.typography.bodySmall, color = if (e.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (e.active) {
                        Spacer(Modifier.height(6.dp))
                        if (e.percent > 0f) {
                            LinearProgressIndicator(progress = { e.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (e.failed) TextButton(onClick = { onRetry(e.item) }) { Text("Try again") }
                }
                IconButton(onClick = { onRemove(e.item.id) }) {
                    Icon(Icons.Default.Close, contentDescription = if (e.done) "Remove" else "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Poster box used when there's no image to show. */
@Composable
fun PosterPlaceholder(item: Item, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(if (item.kind == Kind.Episode) R.drawable.ic_tv else R.drawable.ic_movie),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp),
        )
    }
}
