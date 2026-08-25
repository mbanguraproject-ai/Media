package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class PillarChoice(val label: String, val stored: String, val fieldLabel: String) {
    SONG("Song", "MUSIC", "Artist"),
    PODCAST("Podcast", "PODCAST", "Show / Host"),
    AUDIOBOOK("Audiobook", "AUDIOBOOK", "Author")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(
    item: AppMediaItem,
    hasOverride: Boolean,
    onSave: (title: String, artist: String, details: String, pillar: String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf(item.title) }
    var artist by remember { mutableStateOf(item.artist) }
    var details by remember { mutableStateOf(item.details ?: "") }
    var choice by remember {
        mutableStateOf(
            when (item.pillar) {
                Pillar.PODCAST -> PillarChoice.PODCAST
                Pillar.AUDIOBOOK -> PillarChoice.AUDIOBOOK
                else -> PillarChoice.SONG
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MediaColors.InkRaised,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MediaColors.CreamFaint) }
    ) {
        // Back must dismiss the KEYBOARD first, not the whole sheet.
        // ModalBottomSheet registers its own back handler that calls
        // onDismissRequest, so a back press aimed at the IME was tearing the
        // sheet down and throwing away every edit. This handler is registered
        // inside the sheet content, so it sits later on the dispatcher and
        // wins; it only claims back while the keyboard is actually up.
        val imeUp = WindowInsets.isImeVisible
        val keyboard = LocalSoftwareKeyboardController.current
        BackHandler(enabled = imeUp) { keyboard?.hide() }

        // imePadding lifts the Save button above the keyboard when a field is
        // focused. ModalBottomSheet already applies the navigation-bar inset,
        // so we do NOT add navigationBarsPadding here (that would double-inset).
        // The whole sheet was ONE non-scrolling Column with imePadding(). When
        // the keyboard opened, the padding pushed the column's bottom past the
        // sheet's clip and took Save with it - and nothing scrolled, so it was
        // unreachable. Fields scroll now; the actions are pinned above the IME.
        Column(Modifier.fillMaxWidth().imePadding()) {
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(Space.xl, Space.sm, Space.xl, 0.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(item, Modifier.size(56.dp), corner = 10)
                Spacer(Modifier.width(Space.md))
                Text("Edit details", style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
            }
            Spacer(Modifier.height(Space.xl))

            FieldLabel("Title")
            EditField(title, { title = it }, "Title")
            Spacer(Modifier.height(Space.lg))

            FieldLabel(choice.fieldLabel)
            EditField(artist, { artist = it }, choice.fieldLabel)
            Spacer(Modifier.height(Space.lg))

            FieldLabel("Details")
            EditField(details, { details = it }, "Notes, description…", minHeight = 72)
            Spacer(Modifier.height(Space.xl))

            FieldLabel("This is a")
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillarChoice.values().forEach { pc ->
                    val selected = pc == choice
                    Box(
                        Modifier.weight(1f)
                            .background(
                                if (selected) MediaColors.Cream else MediaColors.Ink,
                                RoundedCornerShape(10.dp)
                            )
                            .border(
                                0.5.dp,
                                if (selected) MediaColors.Cream else MediaColors.InkHairline,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { choice = pc }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            pc.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MediaColors.Ink else MediaColors.CreamDim
                        )
                    }
                }
            }

            Spacer(Modifier.height(Space.xl))
        }

        // ---- pinned actions: always reachable, whatever the keyboard does ----
        Column(Modifier.fillMaxWidth().padding(Space.xl, 0.dp, Space.xl, Space.xl)) {
            Box(
                Modifier.fillMaxWidth()
                    .background(MediaColors.Accent, RoundedCornerShape(12.dp))
                    .clickable { onSave(title, artist, details, choice.stored) }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Save", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MediaColors.OnAccent)
            }

            if (hasOverride) {
                Spacer(Modifier.height(Space.md))
                Box(
                    Modifier.fillMaxWidth()
                        .clickable { onReset() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Reset to automatic",
                        style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim)
                }
            }
        }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
}

@Composable
private fun EditField(value: String, onChange: (String) -> Unit, hint: String, minHeight: Int = 0) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight.dp)
            .background(MediaColors.Ink, RoundedCornerShape(10.dp))
            .border(0.5.dp, MediaColors.InkHairline, RoundedCornerShape(10.dp))
            .padding(Space.md, 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MediaColors.Cream),
            cursorBrush = SolidColor(MediaColors.Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
