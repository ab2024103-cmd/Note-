package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.domain.model.NoteRow
import com.notepadpro.shared.platform.formatTimestamp

/**
 * Notes sidebar: search box + note list (DB-backed, refreshed by the core).
 */
@Composable
fun SidebarPane(core: AppCore, darkTheme: Boolean) {
    val uiState by core.ui.collectAsState()
    val notes by core.notes.collectAsState()
    var deleteTarget by remember { mutableStateOf<NoteRow?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Notes",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 6.dp)
        )
        SidebarSearch(query = uiState.searchQuery, onQuery = { core.onSearchQueryChanged(it) })
        Spacer(Modifier.size(4.dp))
        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (uiState.searchQuery.isBlank()) "No notes yet.\nTap ＋ New or press Ctrl+N."
                    else "No notes match \u201c${uiState.searchQuery}\u201d.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(notes, key = { it.id }) { row ->
                    NoteListItem(
                        row = row,
                        isOpen = false,
                        onClick = { core.openNote(row) },
                        onPin = { core.togglePinned(row) },
                        onDelete = { deleteTarget = row }
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete note?") },
            text = {
                Text(
                    "Delete \u201c${target.title}\u201d from the library?\n" +
                        "The linked file on disk (if any) will NOT be deleted.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    core.deleteNote(target)
                }) { Text("Delete", color = MaterialTheme.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SidebarSearch(query: String, onQuery: (String) -> Unit) {
    var tfv by remember { mutableStateOf(TextFieldValue(query)) }
    LaunchedEffect(query) {
        if (tfv.text != query) tfv = TextFieldValue(query)
    }
    val bg = if (isDarkSurface()) androidx.compose.ui.graphics.Color(0xFF2A2A2A)
    else androidx.compose.ui.graphics.Color(0xFFF0F0F0)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
    ) {
        BasicTextField(
            value = tfv,
            onValueChange = { nv ->
                tfv = nv
                onQuery(nv.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = MaterialTheme.typography.body2.copy(fontSize = 13.sp),
            singleLine = true
        )
        if (query.isEmpty()) {
            Text(
                "Search notes…",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 10.dp, top = 8.dp)
            )
        }
    }
}

@Composable
private fun NoteListItem(
    row: NoteRow,
    isOpen: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (row.isPinned) "★ " else "",
                fontSize = 11.sp,
                color = MaterialTheme.colors.primary
            )
            Text(
                row.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (row.preview.isNotEmpty() && row.preview != row.title) {
            Text(
                row.preview,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                formatTimestamp(row.modifiedAt),
                fontSize = 10.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPin, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(if (row.isPinned) "Unpin" else "Pin", fontSize = 11.sp)
            }
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Delete", fontSize = 11.sp, color = MaterialTheme.colors.error)
            }
        }
    }
}

@Composable
private fun isDarkSurface(): Boolean {
    val c = MaterialTheme.colors.surface
    return (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) < 0.5
}
