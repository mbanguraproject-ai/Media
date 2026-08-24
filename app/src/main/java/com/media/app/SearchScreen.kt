package com.media.app
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SearchScreen(
    all: List<AppMediaItem>,
    onPlay: (List<AppMediaItem>, Int) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, all) {
        if (query.isBlank()) emptyList()
        else all.filter {
            it.title.contains(query, true) || it.artist.contains(query, true)
        }
    }

    Column(
        Modifier.fillMaxSize().background(moodBackground()).statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Space.md, Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(MediaColors.InkRaised).padding(Space.md, 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = MediaColors.CreamDim,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.sm))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search your library",
                            style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamFaint)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MediaColors.Cream),
                        cursorBrush = SolidColor(MediaColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Filled.Close, "Clear", tint = MediaColors.CreamDim,
                        modifier = Modifier.size(18.dp).clickable { query = "" })
                }
            }
        }

        when {
            query.isBlank() -> CenterHint("Find anything in your library")
            results.isEmpty() -> CenterHint("No results for \"$query\"")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = bottomSafePadding())) {
                items(results) { item ->
                    val idx = results.indexOf(item)
                    SearchRow(item) { onPlay(results, idx) }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(item: AppMediaItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(Space.xl, Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(item, Modifier.size(48.dp), corner = 8)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium,
                color = MediaColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist, style = MaterialTheme.typography.bodyMedium,
                color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamFaint)
    }
}
