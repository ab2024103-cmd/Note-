package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.FindUiState
import com.notepadpro.shared.platform.CommonKey
import com.notepadpro.shared.platform.PlatformKeyScope
import kotlin.math.roundToInt

/** Compact, non-modal, draggable palette matching the n1 reference. */
@Composable
internal fun FloatingFindReplacePanel(
    state: FindUiState,
    darkTheme: Boolean,
    focusReplacement: Boolean,
    focusRequest: Long,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onMatchCase: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val margin = with(density) { 8.dp.toPx() }
        val panelWidth = minOf(396.dp, (maxWidth - 16.dp).coerceAtLeast(0.dp))
        val widthPx = with(density) { panelWidth.toPx() }
        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        var position by remember { mutableStateOf<Offset?>(null) }
        val maxX = (constraints.maxWidth - widthPx - margin).coerceAtLeast(margin)
        val maxY = (constraints.maxHeight - panelSize.height - margin).coerceAtLeast(margin)
        fun bounded(value: Offset) = Offset(value.x.coerceIn(margin, maxX), value.y.coerceIn(margin, maxY))
        val initialY = with(density) { (if (maxHeight >= 400.dp) 72.dp else 8.dp).toPx() }
        val location = bounded(position ?: Offset((constraints.maxWidth - widthPx) / 2f, initialY))
        val move by rememberUpdatedState<(Offset) -> Unit> { delta -> position = bounded(location + delta) }
        val background = if (darkTheme) Color(0xFF25272D) else Color.White
        val border = if (darkTheme) Color(0xFF474B55) else Color(0xFFE1E4ED)
        val fieldBackground = if (darkTheme) Color(0xFF30333B) else Color(0xFFF7F8FD)
        val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.62f)
        val controls = if (maxWidth < 480.dp) 40.dp else 32.dp
        val hasMatches = state.matches.isNotEmpty() && !state.searching

        Surface(
            modifier = Modifier
                .offset { IntOffset(location.x.roundToInt(), location.y.roundToInt()) }
                .width(panelWidth)
                .heightIn(max = (maxHeight - 16.dp).coerceAtLeast(0.dp))
                .onSizeChanged { panelSize = it }
                .testTag("find-replace-panel"),
            color = background,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, border),
            elevation = 8.dp
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .testTag("find-replace-drag-handle")
                        .pointerInput(Unit) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                move(amount)
                            }
                        }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(8.dp, 12.dp)) {
                        for (x in 0..1) for (y in 0..2) {
                            drawCircle(muted, radius = 0.8.dp.toPx(), center = Offset((2 + x * 3).dp.toPx(), (2 + y * 3).dp.toPx()))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (readOnly) "Find · Reading mode" else "Find & Replace", fontSize = 11.sp, color = muted)
                    Spacer(Modifier.width(10.dp))
                    Divider(Modifier.weight(1f), color = border)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FindField(
                        value = state.query, placeholder = "Find", label = "Find text",
                        background = fieldBackground, border = border,
                        focus = readOnly || !focusReplacement, focusRequest = focusRequest,
                        onValue = onQuery, onEnter = onNext, onShiftEnter = onPrevious, onClose = onClose,
                        modifier = Modifier.weight(1f)
                    )
                    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.searching) "…" else "${if (hasMatches) state.currentIndex + 1 else 0} / ${state.matches.size}",
                            color = muted, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.testTag("find-match-count")
                        )
                    }
                    PaletteIcon("↑", "Previous match", controls, hasMatches, onPrevious)
                    PaletteIcon("↓", "Next match", controls, hasMatches, onNext)
                    PaletteIcon("×", "Close Find & Replace", controls, true, onClose)
                }
                if (!readOnly) {
                    Divider(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = border)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FindField(
                            value = state.replaceQuery, placeholder = "Replace with", label = "Replacement text",
                            background = fieldBackground, border = border,
                            focus = focusReplacement, focusRequest = focusRequest,
                            onValue = onReplacement, onEnter = onReplace, onShiftEnter = onReplaceAll, onClose = onClose,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onReplace, enabled = hasMatches,
                            modifier = Modifier.width(68.dp).height(34.dp),
                            shape = RoundedCornerShape(7.dp), contentPadding = PaddingValues(4.dp)
                        ) { Text("Replace", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = onReplaceAll, enabled = hasMatches,
                            modifier = Modifier.width(42.dp).height(34.dp).semantics { contentDescription = "Replace all" },
                            shape = RoundedCornerShape(7.dp), contentPadding = PaddingValues(4.dp)
                        ) { Text("All", fontSize = 12.sp) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.toggleable(value = state.caseSensitive, role = Role.Checkbox, onValueChange = onMatchCase)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(state.caseSensitive, onCheckedChange = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Match case", fontSize = 12.sp, color = muted)
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.replacedCount > 0) Text("${state.replacedCount} replaced", color = muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PaletteIcon(text: String, label: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size).semantics { contentDescription = label }) {
        Text(text, fontSize = 18.sp, color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.8f else 0.3f))
    }
}

@Composable
private fun FindField(
    value: String,
    placeholder: String,
    label: String,
    background: Color,
    border: Color,
    focus: Boolean,
    focusRequest: Long,
    onValue: (String) -> Unit,
    onEnter: () -> Unit,
    onShiftEnter: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(focus, focusRequest) {
        if (focus) {
            requester.requestFocus()
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    // Keep the caller's weight on the direct Row child, outside the key-scope box.
    Box(modifier.height(34.dp).background(background, RoundedCornerShape(6.dp)).border(1.dp, border, RoundedCornerShape(6.dp))) {
        PlatformKeyScope(enabled = true, onKey = { key ->
            if (!key.isDown || key.ctrl || key.alt) false
            else when (key.key) {
                CommonKey.ENTER -> { if (key.shift) onShiftEnter() else onEnter(); true }
                CommonKey.ESCAPE -> { onClose(); true }
                else -> false
            }
        }) {
            BasicTextField(
                value = field,
                onValueChange = { field = it; onValue(it.text) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                    .focusRequester(requester).semantics { contentDescription = label },
                textStyle = MaterialTheme.typography.body2.copy(fontSize = 13.sp, color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                singleLine = true,
                decorationBox = { input ->
                    Box {
                        if (field.text.isEmpty()) Text(placeholder, fontSize = 13.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f))
                        input()
                    }
                }
            )
        }
    }
}
