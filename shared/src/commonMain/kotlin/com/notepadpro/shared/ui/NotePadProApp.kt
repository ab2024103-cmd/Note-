package com.notepadpro.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notepadpro.shared.AppCore
import com.notepadpro.shared.Tab
import com.notepadpro.shared.data.settings.ThemeMode
import com.notepadpro.shared.domain.model.TextCodec
import com.notepadpro.shared.editor.EditorSession
import com.notepadpro.shared.platform.CommonKey
import com.notepadpro.shared.platform.PlatformBackHandler
import com.notepadpro.shared.platform.PlatformInfo
import com.notepadpro.shared.platform.PlatformKeyEvent
import com.notepadpro.shared.platform.PlatformKeyScope
import com.notepadpro.shared.ui.screens.EditorScreen
import com.notepadpro.shared.ui.screens.SidebarPane
import com.notepadpro.shared.ui.theme.NotePadProTheme
import kotlinx.coroutines.launch

/**
 * Root composable: resolves the theme, builds the app chrome (tabs, sidebar,
 * snackbar, dialogs) and hosts the active tab's editor.
 */
@Composable
fun NotePadProApp(core: AppCore, modifier: Modifier = Modifier) {
    val prefs by core.prefs.collectAsState()
    val systemDark = isSystemInDarkThemeSafe()
    val darkTheme = when (prefs.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    NotePadProTheme(darkTheme = darkTheme) {
        AppChrome(core, darkTheme, modifier)
    }
}

@Composable
private fun isSystemInDarkThemeSafe(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

@Composable
private fun AppChrome(core: AppCore, darkTheme: Boolean, modifier: Modifier = Modifier) {
    LaunchedEffect(core) {
        core.init()
    }
    DisposableEffect(core) {
        onDispose {
            // best-effort final flush when the composition goes away
            core.scope.launch { core.flushAll() }
        }
    }

    val uiState by core.ui.collectAsState()
    val tabs by core.tabs.collectAsState()
    val activeTabId by core.activeTabId.collectAsState()
    val activeSession = remember(activeTabId, tabs) {
        tabs.firstOrNull { it.localId == activeTabId }?.session
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(core) {
        core.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // System back (Android) closes overlays step by step.
    PlatformBackHandler(
        enabled = uiState.settingsOpen || uiState.aboutOpen || uiState.findOpen ||
            uiState.extractOpen || uiState.sidebarOpen
    ) {
        core.onBackPressed()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        core.setUiWide(wide)

        // Global keyboard shortcut map (desktop-first, Android hardware keys).
        PlatformKeyScope(enabled = true, onKey = { ev ->
            dispatchAppShortcut(core, ev)
        }) {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                TopTabBar(core, tabs, activeTabId)
                if (uiState.loading) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Opening notes…", fontSize = 14.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
                    }
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (wide) {
                                Surface(
                                    modifier = Modifier.fillMaxHeight().width(260.dp),
                                    color = MaterialTheme.colors.surface
                                ) {
                                    SidebarPane(core, darkTheme)
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
                                )
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                activeSession?.let { session ->
                                    EditorScreen(core, session, darkTheme, Modifier.fillMaxSize())
                                }
                            }
                        }
                        // Narrow layouts: sidebar as an overlay drawer.
                        if (!wide && uiState.sidebarOpen) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
                                        .clickable { core.setSidebarOpen(false) }
                                )
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .fillMaxHeight()
                                        .width(286.dp),
                                    color = MaterialTheme.colors.surface,
                                    elevation = 8.dp
                                ) {
                                    SidebarPane(core, darkTheme)
                                }
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
        AppDialogs(core, darkTheme)
    }
}

// ---------------------------------------------------------------------------
// Tab bar
// ---------------------------------------------------------------------------

@Composable
private fun TopTabBar(core: AppCore, tabs: List<Tab>, activeTabId: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSurfaceDark()) MaterialTheme.colors.surface else MaterialTheme.colors.primaryVariant.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { core.toggleSidebar() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
            Text("☰", fontSize = 16.sp)
        }
        Spacer(Modifier.width(2.dp))
        Text(
            "NotePad Pro",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f)
        )
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (tab in tabs) {
                TabChip(tab, tab.localId == activeTabId, onActivate = { core.activateTab(tab.localId) },
                    onClose = { core.closeTab(tab.localId) })
            }
            TextButton(onClick = { core.newTab() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                Text("＋ New", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun isSurfaceDark(): Boolean {
    val bg = MaterialTheme.colors.surface
    val luminance = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue)
    return luminance < 0.5
}

@Composable
private fun TabChip(tab: Tab, active: Boolean, onActivate: () -> Unit, onClose: () -> Unit) {
    val state by tab.session.state.collectAsState()
    val title = remember(state.lines, state.noteId) {
        TextCodec.titleFromLines(state.lines)
    }
    Surface(
        color = if (active) MaterialTheme.colors.primary.copy(alpha = if (isSurfaceDark()) 0.35f else 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onActivate() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 3.dp, bottom = 3.dp)
        ) {
            Text(
                if (state.isPinned) "★ " else "",
                fontSize = 10.sp,
                color = MaterialTheme.colors.primary
            )
            Text(
                title,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                " ✕",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(4.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// App-level keyboard shortcuts (spec section 10)
// ---------------------------------------------------------------------------

private fun dispatchAppShortcut(core: AppCore, ev: PlatformKeyEvent): Boolean {
    if (!ev.isDown) return false
    val session = core.activeSession ?: return false
    val ui = core.ui.value

    // Esc closes the top-most overlay (also closes menus/popups on their own).
    if (!ev.ctrl && !ev.alt && ev.key == CommonKey.ESCAPE) {
        if (ui.settingsOpen || ui.aboutOpen || ui.findOpen || ui.extractOpen || ui.sidebarOpen) {
            core.onBackPressed()
            return true
        }
        return false
    }
    if (ev.alt) return false

    if (!ev.ctrl) return false
    when (ev.key) {
        CommonKey.N -> { core.newTab(); return true }
        CommonKey.O -> { core.requestOpenFile(); return true }
        CommonKey.S -> {
            if (ev.shift) core.requestSaveAs() else core.requestSave()
            return true
        }
        CommonKey.F -> { core.setFindOpen(true, ev.shift); return true }
        CommonKey.H -> { core.setFindOpen(true, true); return true }
        CommonKey.B -> { core.toggleSidebar(); return true }
        CommonKey.Z -> {
            if (ev.shift) session.redo() else session.undo()
            return true
        }
        CommonKey.Y -> { session.redo(); return true }
        CommonKey.EQUALS, CommonKey.MINUS -> {
            core.setFontSize(if (ev.key == CommonKey.MINUS) -1f else +1f)
            return true
        }
        CommonKey.DIGIT_0 -> return false
        CommonKey.DIGIT_7, CommonKey.DIGIT_8, CommonKey.DIGIT_9 -> {
            if (ev.shift) {
                val type = when (ev.key) {
                    CommonKey.DIGIT_8 -> com.notepadpro.shared.domain.model.ListType.BULLET
                    CommonKey.DIGIT_7 -> com.notepadpro.shared.domain.model.ListType.NUMBER
                    else -> com.notepadpro.shared.domain.model.ListType.CHECK
                }
                session.toggleList(type)
                return true
            }
            return false
        }
        else -> return false
    }
}

@Composable
private fun AppDialogs(core: AppCore, darkTheme: Boolean) {
    val ui by core.ui.collectAsState()
    val prefs by core.prefs.collectAsState()
    if (ui.settingsOpen) {
        SettingsDialog(core, prefs)
    }
    if (ui.aboutOpen) {
        AboutDialog(core)
    }
}
