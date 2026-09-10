package com.notepadpro.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.FindUiState
import com.notepadpro.shared.domain.model.HighlightColor
import com.notepadpro.shared.domain.model.ListType
import com.notepadpro.shared.editor.Caret
import com.notepadpro.shared.editor.DocState
import com.notepadpro.shared.editor.EditorSession
import com.notepadpro.shared.editor.ExtractEngine
import com.notepadpro.shared.editor.SaveStatus
import com.notepadpro.shared.editor.countWords
import com.notepadpro.shared.editor.docText
import com.notepadpro.shared.platform.CommonKey
import com.notepadpro.shared.platform.AppDispatchers
import com.notepadpro.shared.platform.PlatformInfo
import com.notepadpro.shared.platform.PlatformKeyEvent
import com.notepadpro.shared.platform.PlatformKeyScope
import com.notepadpro.shared.ui.theme.Markers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The editor: toolbars, find & replace panel, virtualized line list and the
 * status bar. One instance per tab (only the active tab is composed).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditorScreen(
    core: AppCore,
    session: EditorSession,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val prefs by core.prefs.collectAsState()
    val docState by session.state.collectAsState()
    val saveStatus by session.saveStatus.collectAsState()
    val findState by core.find.collectAsState()
    val uiState by core.ui.collectAsState()

    val findColor = Color(0xFFFFE8A3)
    var editorWidth by remember { mutableStateOf(0) }
    val layout = rememberDocumentLayout(docState, editorWidth, prefs.fontSizeSp, prefs.wordWrap)
    ClearEditingFocus(prefs.readingMode, uiState.extractOpen || uiState.sidebarOpen)

    Column(modifier = modifier.fillMaxSize().onSizeChanged { editorWidth = it.width }) {
        EditorToolbar(core, session, docState, darkTheme)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            EditorLines(
                core = core,
                session = session,
                docState = docState,
                darkTheme = darkTheme,
                wordWrap = prefs.wordWrap,
                fontSp = prefs.fontSizeSp,
                findState = findState,
                findColor = findColor,
                readOnly = prefs.readingMode
            )
            if (uiState.extractOpen) {
                ExtractOverlay(core, session, docState, reduceMotion = prefs.reduceMotion)
            }
            if (uiState.findOpen) {
                FloatingFindReplacePanel(
                    state = findState,
                    darkTheme = darkTheme,
                    focusReplacement = uiState.replaceMode,
                    focusRequest = uiState.findFocusRequest,
                    onQuery = core::onFindQueryChanged,
                    onReplacement = core::onReplaceQueryChanged,
                    onMatchCase = core::onCaseSensitiveChanged,
                    onPrevious = core::prevMatch,
                    onNext = core::nextMatch,
                    onReplace = core::replaceCurrent,
                    onReplaceAll = core::replaceAll,
                    onClose = { core.setFindOpen(false) },
                    readOnly = prefs.readingMode
                )
            }
        }
        StatusBar(docState = docState, saveStatus = saveStatus, fontSp = prefs.fontSizeSp,
            darkTheme = darkTheme, layout = layout, readingMode = prefs.readingMode)
    }
}

// ---------------------------------------------------------------------------
// Toolbars
// ---------------------------------------------------------------------------

@Composable
private fun EditorToolbar(core: AppCore, session: EditorSession, docState: DocState, darkTheme: Boolean) {
    val prefs by core.prefs.collectAsState()
    val uiState by core.ui.collectAsState()
    var fileMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var markMenu by remember { mutableStateOf(false) }
    val hasLines = docState.lines.isNotEmpty()
    LaunchedEffect(prefs.readingMode) {
        if (prefs.readingMode) {
            fileMenu = false
            listMenu = false
            colorMenu = false
            markMenu = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ReadingModeButton(prefs.readingMode) { core.setReadingMode(!prefs.readingMode) }
            VDivider()
            Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                ToolDrop("File", fileMenu, { fileMenu = true }, { fileMenu = false }) {
                    MenuItemAction({ fileMenu = false; core.newTab() }, "New note", "Ctrl+N")
                    MenuItemAction({ fileMenu = false; core.requestOpenFile() }, "Open file…", "Ctrl+O")
                    MenuItemAction({ fileMenu = false; core.requestSave() }, "Save", "Ctrl+S")
                    MenuItemAction({ fileMenu = false; core.requestSaveAs() }, "Save As…", "Ctrl+Shift+S")
                    Divider()
                    MenuItemAction({ fileMenu = false; session.copyDocumentText() }, "Copy all text", null)
                    MenuItemAction({ fileMenu = false; session.selectAllLines() }, "Select all lines", null)
                    Divider()
                    MenuItemAction({ fileMenu = false; core.setSettingsOpen(true) }, "Settings…", null)
                    MenuItemAction({ fileMenu = false; core.setAboutOpen(true) }, "About…", null)
                }
                if (!prefs.readingMode) {
                    ToolButton("Undo", enabled = hasLines) { session.undo() }
                    ToolButton("Redo", enabled = hasLines) { session.redo() }
                    VDivider()
                    ToolDrop("List", listMenu, { listMenu = true }, { listMenu = false }) {
                        MenuItemAction({ listMenu = false; session.toggleList(ListType.BULLET) }, "Bullet list", "Ctrl+Shift+8")
                        MenuItemAction({ listMenu = false; session.toggleList(ListType.NUMBER) }, "Numbered list", "Ctrl+Shift+7")
                        MenuItemAction({ listMenu = false; session.toggleList(ListType.CHECK) }, "Checklist", "Ctrl+Shift+9")
                        Divider()
                        MenuItemAction({ listMenu = false; session.indentLines(+1) }, "Indent", "Tab")
                        MenuItemAction({ listMenu = false; session.indentLines(-1) }, "Outdent", "Shift+Tab")
                        Divider()
                        MenuItemAction({ listMenu = false; session.clearAllFormatting() }, "Clear formatting", null)
                    }
                    ToolDrop("Color", colorMenu, { colorMenu = true }, { colorMenu = false }) {
                        MenuItemAction({ colorMenu = false; session.clearInlineSelection() }, "Remove inline highlight", null)
                        Divider()
                        for (c in HighlightColor.entries) {
                            MenuItemColor({ colorMenu = false; session.setLineColor(c) }, c, darkTheme, "${c.display} — selection / current line")
                        }
                        Divider()
                        MenuItemAction({ colorMenu = false; session.setLineColor(null) }, "Clear selection / current line color", null)
                        Divider()
                        Text("Entire paragraph", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        for (c in HighlightColor.entries) {
                            MenuItemColor({ colorMenu = false; session.setParagraphColor(c) }, c, darkTheme, "Paragraph ${c.display}")
                        }
                        MenuItemAction({ colorMenu = false; session.setParagraphColor(null) }, "Clear paragraph background", null)
                    }
                    ToolDrop("Mark", markMenu, { markMenu = true }, { markMenu = false }) {
                        for (c in HighlightColor.entries) {
                            MenuItemColor({ markMenu = false; session.markInlineSelection(c) }, c, darkTheme, "Highlight ${c.display}")
                        }
                    }
                    VDivider()
                }
                ToolButton("Find") { core.setFindOpen(true, false) }
                if (!prefs.readingMode) ToolButton("Replace") { core.setFindOpen(true, true) }
                ToolButton("Extract") { core.setExtractOpen(!uiState.extractOpen) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (prefs.readingMode) {
                Text("Reading mode", fontSize = 11.sp, color = MaterialTheme.colors.primary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            ToolButton("A−") { core.setFontSize(-1f) }
            Text(
                text = "Zoom ${((prefs.fontSizeSp / 15f) * 100f).roundToInt()}%",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            ToolButton("A+") { core.setFontSize(+1f) }
            VDivider()
            ToolButton(if (prefs.wordWrap) "Wrap: On" else "Wrap: Off") { core.setWordWrap(!prefs.wordWrap) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ClearEditingFocus(readingMode: Boolean, overlayOpen: Boolean = false) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(readingMode, overlayOpen) {
        if (readingMode || overlayOpen) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}

@Composable
internal fun ReadingModeButton(reading: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier.height(36.dp).testTag("reading-mode-toggle").semantics {
            contentDescription = if (reading) "Exit reading mode" else "Enter reading mode"
        }) {
        Text(if (reading) "Edit" else "Read", fontSize = 12.sp)
    }
}

@Composable
private fun MenuItemAction(onClick: () -> Unit, text: String, shortcut: String?) {
    DropdownMenuItem(onClick = onClick) {
        if (shortcut == null) {
            Text(text, fontSize = 13.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(shortcut, fontSize = 10.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun MenuItemColor(onClick: () -> Unit, color: HighlightColor, darkTheme: Boolean, label: String) {
    DropdownMenuItem(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (darkTheme) Markers.accent(color, true) else Markers.solid(color), RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ToolButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp)
    ) {
        Text(text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ToolDrop(label: String, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box {
        ToolButton(label) { onExpand() }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { content() }
    }
}

@Composable
private fun VDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(18.dp)
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
    )
}

// ---------------------------------------------------------------------------
// Extract-by-color overlay
// ---------------------------------------------------------------------------

@Composable
private fun ExtractOverlay(core: AppCore, session: EditorSession, docState: DocState, reduceMotion: Boolean) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidth: Dp = if (maxWidth >= 720.dp) 320.dp else maxWidth * 0.92f
        val duration = if (reduceMotion || !PlatformInfo.animationsEnabled()) 0 else 180
        // scrim under the panel on narrow layouts
        if (maxWidth < 720.dp) {
            val bg by animateColorAsState(
                targetValue = Color.Black.copy(alpha = 0.35f),
                animationSpec = tween(duration),
                label = "scrim"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .clickable { core.setExtractOpen(false) }
            )
        }
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(duration)),
            exit = fadeOut(animationSpec = tween(duration)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(modifier = Modifier.fillMaxHeight().width(panelWidth), elevation = 6.dp) {
                ExtractPanel(core, session, docState)
            }
        }
    }
}

@Composable
private fun ExtractPanel(core: AppCore, session: EditorSession, docState: DocState) {
    val initial = setOf(HighlightColor.YELLOW, HighlightColor.GREEN)
    var selected by remember { mutableStateOf(initial) }
    var groupBy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }

    LaunchedEffect(docState.version, selected, groupBy) {
        busy = true
        delay(200) // debounce while typing
        preview = withContext(AppDispatchers.default) {
            ExtractEngine.extract(docState.lines, ExtractEngine.ExtractOptions(colors = selected, groupByColor = groupBy))
        }
        busy = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp)
        ) {
            Text("Extract by color", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { session.copyText(preview) }, enabled = preview.isNotEmpty()) {
                Text("Copy", fontSize = 12.sp)
            }
            TextButton(onClick = { core.setExtractOpen(false) }) { Text("✕", fontSize = 13.sp) }
        }
        Text("Only highlighted text — no uncolored surrounding lines.", fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        // color checkboxes
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (c in HighlightColor.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        selected = if (c in selected) selected - c else selected + c
                    }
                ) {
                    Checkbox(
                        checked = c in selected,
                        onCheckedChange = { on ->
                            selected = if (on) selected + c else selected - c
                        },
                        colors = CheckboxDefaults.colors(checkedColor = Markers.solid(c)),
                        modifier = Modifier.size(28.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Markers.solid(c), RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 0.dp)) {
            Checkbox(checked = groupBy, onCheckedChange = { groupBy = it }, modifier = Modifier.size(28.dp))
            Text("Group by color", fontSize = 13.sp)
            if (busy) {
                Spacer(Modifier.width(10.dp))
                Text("recomputing…", fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f))
            }
        }
        Divider()
        if (preview.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (selected.isEmpty()) "Select colors above" else "No text with the selected colors",
                    fontSize = 13.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val lines = remember(preview) { preview.split('\n') }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(lines) { l ->
                    Text(l, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The virtualized line list
// ---------------------------------------------------------------------------

@Composable
private fun EditorLines(
    core: AppCore,
    session: EditorSession,
    docState: DocState,
    darkTheme: Boolean,
    wordWrap: Boolean,
    fontSp: Float,
    findState: FindUiState,
    findColor: Color,
    readOnly: Boolean
) {
    val listState = rememberLazyListState()
    val registry = remember { HashMap<String, FocusRequester>() }
    var pendingCaret by remember { mutableStateOf<Pair<String, Caret>?>(null) }
    var linesFocused by remember { mutableStateOf(false) }

    val findLines = remember(findState.matches) {
        val map = HashMap<String, MutableList<IntRange>>()
        for (m in findState.matches) {
            map.getOrPut(m.lineId) { ArrayList() }.add(m.start until m.end)
        }
        map
    }

    fun registerFocus(id: String, fr: FocusRequester) {
        registry[id] = fr
    }

    fun unregisterFocus(id: String) {
        registry.remove(id)
    }

    // Keyboard-driven focus requests from the session (Enter splits, merges,
    // undo/redo, arrow moves): scroll to the row, then hand it the caret.
    LaunchedEffect(docState.focusRequest) {
        val req = docState.focusRequest ?: return@LaunchedEffect
        val idx = docState.lines.indexOfFirst { it.id == req.first }
        if (idx >= 0) listState.scrollToItem(idx)
        pendingCaret = req
        session.consumeFocusRequest()
    }

    // Follow the current find match.
    LaunchedEffect(findState.currentIndex, findState.matches) {
        val m = findState.matches.getOrNull(findState.currentIndex) ?: return@LaunchedEffect
        val idx = m.lineIndex.coerceIn(0, (docState.lines.size - 1).coerceAtLeast(0))
        if (docState.lines.getOrNull(idx)?.id == m.lineId) {
            listState.scrollToItem(idx)
        }
    }

    // Desktop nicety: focus the first line once at startup (never on Android:
    // the soft keyboard must not pop up on its own).
    LaunchedEffect(readOnly) {
        if (!readOnly && PlatformInfo.isDesktop() && docState.lines.isNotEmpty()) {
            val id = docState.activeLineId ?: docState.lines.first().id
            if (docState.activeLineId == null) listState.scrollToItem(0)
            delay(200)
            registry[id]?.requestFocus()
        }
    }

    PlatformKeyScope(enabled = linesFocused && docState.lines.isNotEmpty(), onKey = { ev ->
        handleEditorKey(session, docState, ev)
    }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { linesFocused = it.hasFocus }
        ) {
            if (docState.lines.all { it.isEmptyLine }) {
                Text(
                    text = if (readOnly) "This note is empty.\nTap Edit to start writing."
                        else "Start typing…\n\nEnter adds a line • list types under the List menu\nTab indents list items",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    count = docState.lines.size,
                    key = { docState.lines[it].id }
                ) { index ->
                    val line = docState.lines[index]
                    EditorLineRow(
                        line = line,
                        number = docState.numbers[line.id],
                        isActiveRow = docState.activeLineId == line.id,
                        darkTheme = darkTheme,
                        wordWrap = wordWrap,
                        fontSizeSp = fontSp,
                        findRanges = findLines[line.id] ?: emptyList(),
                        findColor = findColor,
                        focusRequester = remember { FocusRequester() },
                        registerFocus = { id, fr -> registerFocus(id, fr) },
                        unregisterFocus = { id -> unregisterFocus(id) },
                        caretToApply = pendingCaret?.takeIf { it.first == line.id }?.second,
                        onTextChange = { id, text, s1, s2 ->
                            session.applyTextChange(id, text, s1, s2)
                            pendingCaret = null
                        },
                        onRowFocused = { id ->
                            session.onLineFocused(id)
                            pendingCaret = null
                        },
                        onToggleCheck = { id -> session.toggleChecked(id) },
                        onSelectLine = { id -> session.selectSingleLine(id) },
                        onCaretApplied = { pendingCaret = null },
                        onVisualLineChanged = session::onVisualLineChanged,
                        currentFindRange = findState.matches.getOrNull(findState.currentIndex)
                            ?.takeIf { it.lineId == line.id }?.let { it.start until it.end },
                        readOnly = readOnly
                    )
                }
            }
        }
    }
}

private fun handleEditorKey(session: EditorSession, doc: DocState, ev: PlatformKeyEvent): Boolean {
    if (!ev.isDown) return false
    if (ev.ctrl || ev.alt) return false
    if (session.isReadOnly && ev.key != CommonKey.ARROW_UP && ev.key != CommonKey.ARROW_DOWN) return false
    val activeId = doc.activeLineId ?: return false
    val activeLine = doc.lines.firstOrNull { it.id == activeId } ?: return false
    when (ev.key) {
        CommonKey.ENTER -> {
            session.insertLineBreak(activeId)
            return true
        }
        CommonKey.BACKSPACE -> {
            val caret = doc.caret
            val atStart = caret == null || (caret.min == 0 && caret.max == 0)
            return if (atStart && (activeLine.isEmptyLine || caret == null || caret.min == 0)) {
                session.mergeWithPrevious(activeId)
            } else false
        }
        CommonKey.DELETE -> {
            val caret = doc.caret
            val len = activeLine.plainText.length
            return if (caret != null && caret.min == len && caret.max == len) {
                session.mergeWithNext(activeId)
            } else false
        }
        CommonKey.ARROW_UP -> return session.canMoveToAdjacentParagraph(-1) && session.moveActiveLine(-1, extend = ev.shift)
        CommonKey.ARROW_DOWN -> return session.canMoveToAdjacentParagraph(+1) && session.moveActiveLine(+1, extend = ev.shift)
        CommonKey.TAB -> {
            if (activeLine.listType != ListType.NONE) {
                session.indentLines(if (ev.shift) -1 else +1)
                return true
            }
            return false
        }
        else -> return false
    }
}

// ---------------------------------------------------------------------------
// Status bar
// ---------------------------------------------------------------------------

@Composable
internal fun StatusBar(
    docState: DocState,
    saveStatus: SaveStatus,
    fontSp: Float,
    darkTheme: Boolean,
    layout: DocumentLayout?,
    readingMode: Boolean
) {
    var wordCount by remember { mutableStateOf(0) }
    LaunchedEffect(docState.lines) {
        delay(250)
        wordCount = withContext(AppDispatchers.default) { countWords(docText(docState.lines)) }
    }
    val activeId = docState.activeLineId ?: docState.lines.firstOrNull()?.id
    val position = layout?.position(activeId, docState.caret?.end ?: 0)
    val textIndex = docState.lines.indexOfFirst { it.id == docState.activeLineId }.coerceAtLeast(0)
    val caretLabel = if (position != null) "Ln ${position.line}, Col ${position.column}"
        else "Text Ln ${textIndex + 1}"
    val saveLabel = when (saveStatus) {
        SaveStatus.CLEAN -> "Ready"
        SaveStatus.DIRTY -> "Editing…"
        SaveStatus.SAVING -> "Autosaving…"
        SaveStatus.SAVED -> "Saved"
        SaveStatus.ERROR -> "Save error"
    }
    val lineLabel = if (layout == null) "Counting display lines… · ${docState.lines.size} text lines"
        else "${layout.displayLines} display lines · ${docState.lines.size} text lines"
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
    BoxWithConstraints(Modifier.fillMaxWidth()
        .background(if (darkTheme) Color(0xFF1A1A1A) else Color(0xFFF5F5F5))
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        val narrow = maxWidth < 640.dp
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (readingMode) "Reading · $saveLabel" else saveLabel, fontSize = 11.sp, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (narrow) Modifier.weight(1f) else Modifier)
                Text("$wordCount words", fontSize = 11.sp, color = muted, maxLines = 1)
                if (!narrow) Text(lineLabel, fontSize = 11.sp, color = muted, modifier = Modifier.testTag("line-count"))
                if (!narrow && !readingMode) Text(caretLabel, fontSize = 11.sp, color = muted, maxLines = 1)
                if (!narrow) Spacer(Modifier.weight(1f))
                Text("${((fontSp / 15f) * 100f).roundToInt()}%", fontSize = 11.sp, color = muted)
            }
            if (narrow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(lineLabel, fontSize = 11.sp, color = muted, modifier = Modifier.weight(1f).testTag("line-count"))
                if (!readingMode) Text(caretLabel, fontSize = 11.sp, color = muted, maxLines = 1)
            } else docState.sourcePath?.let { path ->
                Text("file: $path", fontSize = 10.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
