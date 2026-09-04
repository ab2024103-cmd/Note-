package com.notepadpro.shared.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
private fun buildLineAnnotation(
    line: EditorLine,
    findRanges: List<IntRange>,
    findColor: Color
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
        val s = range.first.coerceIn(0, len)
        val e = range.last.coerceIn(s, len)
        if (e > s) {
            styles.add(
                AnnotatedString.Range(
                    item = SpanStyle(
                        background = findColor,
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
    onCaretApplied: () -> Unit
) {
    val textColor = MaterialTheme.colors.onBackground
    val lineText = line.plainText

    var tfv by remember(line.id) {
        mutableStateOf(
            TextFieldValue(buildLineAnnotation(line, findRanges, findColor))
        )
    }

    // Register/unregister this row's FocusRequester.
    DisposableEffect(line.id) {
        registerFocus(line.id, focusRequester)
        onDispose { unregisterFocus(line.id) }
    }

    /**
     * External model change sync (undo / replace-all / mark / split / merge
     * / find results). Only applied when the decorated text actually differs,
     * so plain typing (which updates tfv first) never jumps the caret.
     */
    LaunchedEffect(line.plainText, line.spans, line.checked, findRanges, findColor) {
        val expected: AnnotatedString = buildLineAnnotation(line, findRanges, findColor)
        // Compare plain/decorated text structurally without depending on the
        // exact TextFieldValue.text type (String vs AnnotatedString).
        val currentText: String = tfv.text.toString()
        if (currentText != expected.toString()) {
            val selMin = (caretToApply?.min ?: tfv.selection.min).coerceIn(0, lineText.length)
            tfv = TextFieldValue(expected, TextRange(selMin))
        }
    }

    // Keyboard-driven focus request for this row (arrow moves, Enter, undo...).
    LaunchedEffect(caretToApply) {
        val caret = caretToApply ?: return@LaunchedEffect
        tfv = tfv.copy(selection = TextRange(caret.min.coerceIn(0, lineText.length)))
        focusRequester.requestFocus()
        onCaretApplied()
    }

    fun applyCaretIfPending() {
        val caret = caretToApply ?: return
        tfv = tfv.copy(selection = TextRange(caret.min.coerceIn(0, lineText.length)))
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
                .width(36.dp)
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
                ListType.NUMBER -> Text(
                    "${number ?: 1}.",
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
                tfv = newValue
                onTextChange(line.id, newValue.text, newValue.selection.min, newValue.selection.max)
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { f ->
                    if (f.isFocused) {
                        applyCaretIfPending()
                        onRowFocused(line.id)
                    }
                },
            textStyle = TextStyle(
                fontSize = fontSizeSp.sp,
                color = textColor
            ),
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
