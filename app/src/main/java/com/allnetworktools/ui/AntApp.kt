package com.allnetworktools.ui

import androidx.activity.compose.BackHandler
import com.allnetworktools.ui.pages.LocalToolBack
import com.allnetworktools.ui.pages.ToolBackStack
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.NavState
import com.allnetworktools.Page
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.model.ToolParent
import com.allnetworktools.ui.home.DockTab
import com.allnetworktools.ui.home.FloatingDock
import com.allnetworktools.ui.home.HomeData
import com.allnetworktools.ui.home.HomeScreen
import com.allnetworktools.ui.onboarding.Onboarding
import com.allnetworktools.ui.pages.PageHostContent
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.ProvideAccent
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AntApp(vm: MainViewModel) {
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    AntTheme(settings) {
        val toaster = remember { Toaster() }
        val actions = rememberAppActions(vm, toaster)
        CompositionLocalProvider(LocalActions provides actions) {
            Box(Modifier.fillMaxSize().background(cs.surface)) {
                if (!settings.onboardingDone) {
                    Onboarding(vm, onDone = { vm.updateSettings { setOnboardingDone() } })
                } else {
                    MainShell(vm)
                }
                UpdateBanner(vm, Modifier.align(Alignment.TopCenter))
                if (settings.onboardingDone) UpdateDialog(vm)
                Snackbar(toaster, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private fun parentPage(page: Page): Page = when (page) {
    // A neighbouring cell opened from the list goes back to the list; the serving cell goes back to the tools.
    is Page.ToolPage -> if (page.tool == Tool.CellDetail && page.arg != null && page.arg != "serving") Page.ToolPage(Tool.Neighbors) else when (val p = page.tool.parent) {
        ToolParent.Tools -> Page.Tools
        ToolParent.Dashboard -> Page.Dashboard
        is ToolParent.Other -> Page.ToolPage(p.tool)
    }
    Page.Changelog -> Page.Settings
    else -> Page.Home
}

@Composable
private fun MainShell(vm: MainViewModel) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val haptics = AntTheme.haptics
    val cardBounds = remember { mutableStateMapOf<Network, Rect>() }
    var settingsBounds by remember { mutableStateOf(Rect.Zero) }
    val pageOpen = nav.page != Page.Home
    val keepAwake = AntTheme.settings.keepAwake && pageOpen
    val view = LocalView.current
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    fun select(n: Network) {
        val s = vm.nav.value
        if (s.network == n && s.page == Page.Home) {
            haptics.confirm()
            vm.navigate { it.copy(page = Page.Dashboard) }
        } else {
            if (s.network != null && s.network != n) haptics.segment() else haptics.tick()
            vm.navigate { it.copy(network = n) }
        }
    }

    // Container transform state.
    val progress = remember { Animatable(0f) }
    val contentAlpha = remember { Animatable(0f) }
    val back = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<NavState?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pageOpen) {
        if (pageOpen) {
            shown = nav
            back.snapTo(0f)
            progress.snapTo(0f)
            contentAlpha.snapTo(0f)
            haptics.confirm()
            launch { contentAlpha.animateTo(1f, tween(300, 120, Motion.Emphasized)) }
            progress.animateTo(1f, tween(520, easing = Motion.Emphasized))
        } else if (shown != null) {
            launch { contentAlpha.animateTo(0f, tween(120)) }
            launch { back.animateTo(0f, tween(380, easing = Motion.EmphasizedAccelerate)) }
            progress.animateTo(0f, tween(380, easing = Motion.EmphasizedAccelerate))
            shown = null
        }
    }
    LaunchedEffect(nav) { if (pageOpen) shown = nav }

    fun closePage() = vm.navigate { it.copy(page = Page.Home) }

    val toolBack = remember { ToolBackStack() }
    val toolSteps = toolBack.entries.isNotEmpty()
    val closesToHome = pageOpen && !toolSteps && vm.backTarget(parentPage(nav.page)) == Page.Home
    PredictiveBackHandler(enabled = closesToHome) { events ->
        var buzzed = false
        try {
            events.collect { e ->
                back.snapTo(e.progress)
                if (!buzzed && e.progress > 0.3f) {
                    buzzed = true; haptics.threshold()
                }
            }
            closePage()
        } catch (e: CancellationException) {
            scope.launch { back.animateTo(0f, Motion.standard()) }
            throw e
        }
    }
    BackHandler(enabled = !closesToHome && (pageOpen || nav.network != null)) {
        when {
            pageOpen && toolBack.pop() -> Unit
            pageOpen -> vm.back(parentPage(nav.page))
            else -> vm.navigate { it.copy(network = null) }
        }
    }

    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        val homeVisible = shown == null || progress.value < 1f || back.value > 0f || progress.isRunning
        if (homeVisible) {
            val data = HomeData(blockers = vm.blockers.collectAsStateWithLifecycle().value)
            val actions = LocalActions.current
            HomeScreen(
                data = data,
                selected = nav.network,
                cardBounds = cardBounds,
                onSettingsBounds = { settingsBounds = it },
                onCard = ::select,
                onBlockerAction = actions::resolve,
                onDeselect = { vm.navigate { it.copy(network = null) } },
                onSettings = { vm.navigate { it.copy(network = null, page = Page.Settings) } },
            )
        }

        val s = shown
        if (s != null) {
            val net = s.network ?: Network.Wifi
            val isSettings = s.page == Page.Settings || s.page == Page.Changelog
            val origin = (if (isSettings) settingsBounds else cardBounds[net])?.takeIf { it != Rect.Zero }
                ?: Rect(0f, 0f, rootSize.width.toFloat(), rootSize.height.toFloat())
            val full = Rect(0f, 0f, rootSize.width.toFloat(), rootSize.height.toFloat())
            val p = progress.value
            val b = back.value
            val density = LocalDensity.current
            val cornerPx = with(density) { (if (isSettings) 24.dp else 28.dp).toPx() }
            // A network card grows into its page; the settings slide in from the right instead of swelling out of the
            // small gear button, which stretched the whole screen out of a 44 dp circle.
            val rect = if (isSettings) full else lerpRect(origin, full, p)
            val radius = if (isSettings) with(density) { 28.dp.toPx() } * b else maxOf(cornerPx * (1f - p), with(density) { 28.dp.toPx() } * b)
            val startBg = if (isSettings) cs.surface else AntTheme.net[net].container
            ProvideAccent(net) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val sc = 1f - 0.1f * b
                            scaleX = sc; scaleY = sc
                            if (isSettings) {
                                translationX = (1f - p) * size.width * 0.25f
                                alpha = p
                            }
                            clip = true
                            shape = RoundRectShape(rect, radius)
                        }
                        .background(lerp(startBg, cs.surface, p)),
                ) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha.value }) {
                        CompositionLocalProvider(LocalToolBack provides toolBack) {
                            PageHostContent(vm, s, onBack = { if (!toolBack.pop()) vm.back(parentPage(s.page)) })
                        }
                    }
                }
            }
        }

        DockLayer(vm, nav, Modifier.align(Alignment.BottomCenter), onClose = ::closePage)
    }
}

@Composable
private fun DockLayer(vm: MainViewModel, nav: NavState, modifier: Modifier, onClose: () -> Unit) {
    val visible = nav.network != null && nav.page != Page.Settings && nav.page != Page.Changelog
    var lastNet by remember { mutableStateOf(nav.network ?: Network.Wifi) }
    if (nav.network != null) lastNet = nav.network
    val density = LocalDensity.current
    val hidden = with(density) { 180.dp.toPx() }
    val offset = remember { Animatable(hidden) }
    val haptics = AntTheme.haptics
    LaunchedEffect(visible) {
        if (visible) offset.animateTo(0f, androidx.compose.animation.core.spring(0.6f, 500f))
        else offset.animateTo(hidden, tween(350, easing = Motion.EmphasizedAccelerate))
    }
    if (!visible && offset.value >= hidden) return
    ProvideAccent(lastNet) {
        FloatingDock(
            network = lastNet,
            page = nav.page,
            onTab = { tab ->
                val page = when (tab) {
                    DockTab.Dashboard -> Page.Dashboard
                    DockTab.Tools -> Page.Tools
                    DockTab.Featured -> lastNet.dockShortcut?.let { Page.ToolPage(it) } ?: Page.Tools
                }
                vm.navigate { it.copy(page = page) }
            },
            onHome = {
                haptics.tick()
                if (nav.page == Page.Home) vm.navigate { it.copy(network = null) } else onClose()
            },
            modifier = modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 28.dp)
                .graphicsLayer { translationY = offset.value },
        )
    }
}

@Composable
private fun Snackbar(toaster: Toaster, modifier: Modifier) {
    val msg = toaster.message
    val serial = toaster.serial
    val y = remember { Animatable(1f) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(serial, msg) {
        if (msg != null) {
            text = msg
            y.animateTo(0f, Motion.standard())
            delay(2400)
            toaster.dismiss(serial)
        } else {
            y.animateTo(1f, tween(200, easing = Motion.Emphasized))
        }
    }
    if (y.value >= 1f && msg == null) return
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 108.dp)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = y.value * 20.dp.toPx()
                alpha = 1f - y.value
            }
            .clip(RoundedCornerShape(8.dp))
            .background(cs.inverseSurface)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = rf(14, 20), color = cs.inverseOnSurface)
        Text("OK", Modifier.padding(start = 12.dp), style = rf(14, 20, 600), color = cs.inversePrimary)
    }
}

private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t),
)

/** Clip to an absolute rounded rect inside the layer's bounds. */
private class RoundRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}
