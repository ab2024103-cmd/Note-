package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.domain.model.NoteRow
import com.notepadpro.shared.platform.formatTimestamp

/** Whether the given color reads as a dark surface. */
@Composable
fun isSurfaceDark(): Boolean {
    val bg = MaterialTheme.colors.surface
    val luminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
    return luminance < 0.5
}

/**
 * Notes sidebar: search box + note list (DB-backed, refreshed by the core).
 * Delete asks for confirmation; pin/unpin is a one-tap action.
 */
@Composable
fun SidebarPane(core: AppCore, darkTheme: Boolean) {
    val notes by core.notes.collectAsState()
    var search by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<NoteRow?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        // Search box with placeholder overlay.
        Box(modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = search,
                onValueChange = {
                    search = it
                    core.onSearchQueryChanged(it)
                },
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                textStyle = TextStyle(color = MaterialTheme.colors.onSurface, fontSize = 13.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSurfaceDark()) androidx.compose.ui.graphics.Color(0xFF2A2A2A)
                        else MaterialTheme.colors.onSurface.copy(alpha = 0.07f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
            if (search.isEmpty()) {
                Text(
                    "Search notes…",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                )
            }
        }

        Spacer(Modifier.size(6.dp))
        val sorted = remember(notes) {
            notes.sortedWith(
                compareByDescending<NoteRow> { it.isPinned }
                    .thenByDescending { it.modifiedAt }
            )
        }
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (search.isBlank()) "No notes yet.\nTap ＋ New to start." else "No notes match “$search”.",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sorted, key = { it.id }) { row ->
                    NoteRowItem(
                        row = row,
                        darkTheme = darkTheme,
                        onOpen = { core.openNote(row) },
                        onTogglePin = { core.togglePinned(row) },
                        onDelete = { pendingDelete = row }
                    )
                }
            }
        }
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete note?") },
            text = { Text("“${target.title}” will be removed from the library. The saved file (if any) is not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    core.deleteNote(target)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NoteRowItem(
    row: NoteRow,
    darkTheme: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val bg = MaterialTheme.colors.surface
    val dark = isSurfaceDark()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (row.isPinned) {
                    if (dark) MaterialTheme.colors.primary.copy(alpha = 0.10f)
                    else MaterialTheme.colors.primary.copy(alpha = 0.08f)
                } else bg,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title.ifBlank { "Untitled" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = if (row.isPinned) FontWeight.SemiBold else FontWeight.Normal
            )
            if (row.preview.isNotBlank()) {
                Text(
                    row.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
            Text(
                formatTimestamp(row.modifiedAt),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.38f),
                fontSize = 10.sp
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (row.isPinned) "★" else "☆",
                fontSize = 15.sp,
                color = if (row.isPinned) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .clickable(onClick = onTogglePin)
                    .padding(4.dp)
            )
            Text(
                "🗑",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(4.dp)
            )
        }
    }
}
