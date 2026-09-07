package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.domain.model.EditorLine
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.editor.Caret
import com.notepadpro.shared.ui.theme.Markers

/**
 * Builds the annotated text of one line: inline-highlight backgrounds,
 * find-match overlays and checked strike-through, in one [AnnotatedString].
 */
internal fun buildLineAnnotation(
    line: EditorLine,
    findRanges: List<IntRange>,
    findColor: Color,
    currentFindRange: IntRange? = null
): AnnotatedString {
    val text = line.plainText
    if (text.isEmpty()) return AnnotatedString("")
    val styles = ArrayList<AnnotatedString.Range<SpanStyle>>(line.spans.size + findRanges.size + 1)

    // 1) inline highlight spans (marker style: pastel background + dark text)
    var offset = 0
    for (span in line.spans) {
        val end = offset + span.text.length
        if (span.highlighted && span.text.isNotEmpty()) {
            styles.add(
                AnnotatedString.Range(
                    item = SpanStyle(
                        background = Markers.spanBackground(span.highlightColor),
                        color = Markers.markText
                    ),
                    start = offset,
                    end = end
                )
            )
        }
        offset = end
    }

    // 2) checked -> line-through across the whole line
    if (line.checked && text.isNotEmpty()) {
        styles.add(
            AnnotatedString.Range(
                item = SpanStyle(textDecoration = TextDecoration.LineThrough),
                start = 0,
                end = text.length
            )
        )
    }

    // 3) find matches (added last: they paint on top of highlights)
    val len = text.length
    for (range in findRanges) {
        if (range.isEmpty()) continue
        val s = range.first.coerceIn(0, len)
        val e = (range.last + 1).coerceIn(s, len)
        if (e > s) {
            styles.add(
                AnnotatedString.Range(
                    item = SpanStyle(
                        background = if (range == currentFindRange) Color(0xFFFFB74D) else findColor,
                        color = Color.Black,
                        textDecoration = TextDecoration.Underline
                    ),
                    start = s,
                    end = e
                )
            )
        }
    }

    return if (styles.isEmpty()) AnnotatedString(text)
    else AnnotatedString(text = text, spanStyles = styles)
}

/**
 * One row of the line-model editor: list glyph gutter + BasicTextField.
 *
 * The field is stateful per line id; external changes (undo, replace-all,
 * imports, splits/merges) are synced by comparing row text to the model in
 * a LaunchedEffect keyed on [EditorLine.plainText].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorLineRow(
    line: EditorLine,
    number: Int?,
    isActiveRow: Boolean,
    darkTheme: Boolean,
    wordWrap: Boolean,
    fontSizeSp: Float,
    findRanges: List<IntRange>,
    findColor: Color,
    focusRequester: FocusRequester,
    registerFocus: (String, FocusRequester) -> Unit,
    unregisterFocus: (String) -> Unit,
    caretToApply: Caret?,
    onTextChange: (lineId: String, text: String, selStart: Int, selEnd: Int) -> Unit,
    onRowFocused: (lineId: String) -> Unit,
    onToggleCheck: (lineId: String) -> Unit,
    onSelectLine: (lineId: String) -> Unit,
    onCaretApplied: () -> Unit,
    onVisualLineChanged: (String, Int, Int) -> Unit = { _, _, _ -> },
    currentFindRange: IntRange? = null
) {
    val textColor = MaterialTheme.colors.onBackground
    val lineText = line.plainText

    var tfv by remember(line.id) {
        mutableStateOf(
            TextFieldValue(buildLineAnnotation(line, findRanges, findColor, currentFindRange))
        )
    }

    var fieldFocused by remember(line.id) { mutableStateOf(false) }
    var editingSelection by remember(line.id) { mutableStateOf(tfv.selection) }
    var pendingCollapse by remember(line.id) { mutableStateOf<TextRange?>(null) }

    // Register/unregister this row's FocusRequester.
    DisposableEffect(line.id, focusRequester) {
        registerFocus(line.id, focusRequester)
        onDispose { unregisterFocus(line.id) }
    }

    var textLayout by remember(line.id) { mutableStateOf<TextLayoutResult?>(null) }
    val matchIntoView = remember(line.id) { BringIntoViewRequester() }
    LaunchedEffect(currentFindRange, textLayout) {
        val range = currentFindRange ?: return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        if (range.isEmpty() || layout.layoutInput.text.text != lineText || lineText.isEmpty()) return@LaunchedEffect
        matchIntoView.bringIntoView(layout.getBoundingBox(range.first.coerceIn(0, lineText.lastIndex)))
    }

    fun reportVisualLine() {
        val layout = textLayout ?: return
        if (layout.layoutInput.text.text != tfv.text) return
        val row = layout.getLineForOffset(tfv.selection.end.coerceIn(0, tfv.text.length))
        onVisualLineChanged(line.id, layout.getLineStart(row), layout.getLineEnd(row))
    }

    // CoreTextField emits a collapsed selection when it loses focus. Its
    // callback can arrive BEFORE our focus observer, so don't publish that
    // ambiguous collapse until the focus transition has finished. A genuine
    // cursor move inside the editor is accepted on the next composition;
    // moving into a toolbar/menu preserves the editing selection instead.
    LaunchedEffect(pendingCollapse, fieldFocused) {
        val collapsed = pendingCollapse ?: return@LaunchedEffect
        pendingCollapse = null
        if (fieldFocused) {
            editingSelection = collapsed
            onTextChange(line.id, tfv.text, collapsed.start, collapsed.end)
            reportVisualLine()
        } else {
            tfv = tfv.copy(selection = editingSelection)
        }
    }

    // Compare full annotations, not toString(): the latter discards colors and
    // makes formatting-only changes (including Find matches) invisible.
    val expected = remember(line, findRanges, findColor, currentFindRange) {
        buildLineAnnotation(line, findRanges, findColor, currentFindRange)
    }
    LaunchedEffect(line.id, expected) {
        if (tfv.annotatedString != expected) {
            tfv = if (tfv.text == lineText) {
                tfv.copy(annotatedString = expected) // preserve selection and IME composition
            } else {
                val caret = caretToApply
                TextFieldValue(expected, TextRange(
                    (caret?.start ?: editingSelection.start).coerceIn(0, lineText.length),
                    (caret?.end ?: editingSelection.end).coerceIn(0, lineText.length)
                )).also {
                    editingSelection = it.selection
                    pendingCollapse = null
                }
            }
        }
    }
    LaunchedEffect(tfv.selection, textLayout, isActiveRow) {
        if (isActiveRow) reportVisualLine()
    }

    // Keyboard-driven focus request for this row (arrow moves, Enter, undo...).
    LaunchedEffect(line.id, focusRequester, caretToApply) {
        val caret = caretToApply ?: return@LaunchedEffect
        tfv = tfv.copy(selection = TextRange(
            caret.start.coerceIn(0, lineText.length), caret.end.coerceIn(0, lineText.length)
        ))
        editingSelection = tfv.selection
        pendingCollapse = null
        focusRequester.requestFocus()
        onCaretApplied()
    }

    fun applyCaretIfPending() {
        val caret = caretToApply ?: return
        tfv = tfv.copy(selection = TextRange(
            caret.start.coerceIn(0, lineText.length), caret.end.coerceIn(0, lineText.length)
        ))
        editingSelection = tfv.selection
        pendingCollapse = null
    }

    val wash = line.lineColor?.let { Markers.lineWash(it, darkTheme) }
    val accent = line.lineColor?.let { Markers.accent(it, darkTheme) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(wash ?: Color.Transparent)
            .drawBehind {
                if (accent != null) {
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(4.dp.toPx(), size.height)
                    )
                }
            }
            .padding(start = 6.dp, end = 8.dp)
    ) {
        // -------- list glyph gutter (also the "select line" handle) --------
        Box(
            modifier = Modifier
                .width((maxOf(36f, ((number?.toString()?.length ?: 1) + 1) * fontSizeSp * 0.65f) + line.indent.coerceAtLeast(0) * 14).dp)
                .heightIn(min = (fontSizeSp * 1.5f).dp)
                .pointerInput(line.id) {
                    detectTapGestures { _ ->
                        when (line.listType) {
                            ListType.CHECK -> onToggleCheck(line.id)
                            else -> onSelectLine(line.id)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val indentPad = (line.indent * 14).dp
            when (line.listType) {
                ListType.BULLET -> if (line.plainText.isNotEmpty() || line.indent > 0 || line.listType != ListType.NONE) {
                    Text(
                        "•",
                        color = MaterialTheme.colors.primary,
                        fontSize = (fontSizeSp).sp,
                        modifier = Modifier.padding(start = indentPad)
                    )
                }
                ListType.NUMBER -> if (number != null) Text(
                    "$number.",
                    color = MaterialTheme.colors.primary,
                    fontSize = (fontSizeSp - 1).sp,
                    modifier = Modifier.padding(start = indentPad)
                )
                ListType.CHECK -> Box(modifier = Modifier.padding(start = indentPad)) {
                    CheckGlyph(checked = line.checked, color = MaterialTheme.colors.primary)
                }
                ListType.NONE -> Unit
            }
        }

        // -------- the editable text --------
        BasicTextField(
            value = tfv,
            onValueChange = { newValue ->
                val selectionOnlyCollapse = newValue.text == tfv.text &&
                    newValue.selection.collapsed && !editingSelection.collapsed
                if (selectionOnlyCollapse) {
                    if (fieldFocused) {
                        tfv = newValue
                        pendingCollapse = newValue.selection
                    } else {
                        tfv = newValue.copy(selection = editingSelection)
                    }
                } else {
                    tfv = newValue
                    editingSelection = newValue.selection
                    pendingCollapse = null
                    onTextChange(line.id, newValue.text, newValue.selection.start, newValue.selection.end)
                    reportVisualLine()
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .bringIntoViewRequester(matchIntoView)
                .focusRequester(focusRequester)
                .onFocusChanged { f ->
                    fieldFocused = f.isFocused
                    if (f.isFocused) {
                        applyCaretIfPending()
                        onRowFocused(line.id)
                        reportVisualLine()
                    } else if (pendingCollapse != null) {
                        tfv = tfv.copy(selection = editingSelection)
                        pendingCollapse = null
                    }
                },
            textStyle = TextStyle(
                fontSize = fontSizeSp.sp,
                color = textColor
            ),
            onTextLayout = { layout ->
                textLayout = layout
                reportVisualLine()
            },
            singleLine = !wordWrap,
            keyboardOptions = KeyboardOptions(autoCorrect = true),
            cursorBrush = SolidColor(if (isActiveRow) MaterialTheme.colors.primary else textColor)
        )
    }
}

/** Drawn checkbox glyph (font-independent, works on Android 5). */
@Composable
private fun CheckGlyph(checked: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()
        val left = stroke
        val top = stroke
        val right = size.width - stroke
        val bottom = size.height - stroke
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        if (checked) {
            val p1 = Offset(size.width * 0.2f, size.height * 0.52f)
            val p2 = Offset(size.width * 0.44f, size.height * 0.74f)
            val p3 = Offset(size.width * 0.82f, size.height * 0.26f)
            drawLine(color, p1, p2, strokeWidth = stroke + 1.dp.toPx())
            drawLine(color, p2, p3, strokeWidth = stroke + 1.dp.toPx())
        }
    }
}
