package com.allnetworktools.ui.tools

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf

/** Shared state machine of every tool. */
enum class Phase { Idle, Running, Results, Empty, Error }

val Phase.active get() = this == Phase.Running || this == Phase.Results
val Phase.form get() = this == Phase.Idle || this == Phase.Error

fun mono(size: Number, line: Number = size.toFloat() * 1.5f, weight: Int = 400) = TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = size.toFloat().sp, lineHeight = line.toFloat().sp,
    fontWeight = androidx.compose.ui.text.font.FontWeight(weight),
)

/** Filled text field with a 2 dp accent indicator, clear button and recent-value chips. */
@Composable
fun HostInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: String = Sym.Dns,
    recent: List<String> = emptyList(),
    keyboardType: KeyboardType = KeyboardType.Uri,
    onDone: () -> Unit = {},
) {
    val acc = AntTheme.accent
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)).background(cs.surfaceContainerHigh)
                .drawBehind { drawRect(acc.accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(icon, size = 24.dp, tint = cs.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(label, style = rf(12, 16, 500), color = acc.accent)
                BasicTextField(
                    value, onValueChange, Modifier.fillMaxWidth(),
                    textStyle = rf(16, 24).copy(color = cs.onSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(acc.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { onDone() }),
                )
            }
            if (value.isNotEmpty()) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { onValueChange("") }, contentAlignment = Alignment.Center) {
                    Symbol(Sym.Cancel, size = 22.dp, tint = cs.onSurfaceVariant)
                }
            }
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { r ->
                    Row(
                        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable { onValueChange(r) }.padding(start = 8.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Symbol(Sym.History, size = 18.dp, tint = cs.onSurfaceVariant)
                        Text(r, style = rf(13, 18, 500), color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Card of parameter rows separated by hairlines. */
@Composable
fun ParamCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 4.dp),
        content = content,
    )
}

@Composable
fun ParamRow(label: String, subtitle: String? = null, first: Boolean = false, trailing: @Composable () -> Unit) {
    if (!first) Hairline()
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = rf(15, 20, 500))
            if (subtitle != null) Text(subtitle, style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun Stepper(value: Int, onDec: () -> Unit, onInc: () -> Unit) {
    val acc = AntTheme.accent
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(cs.surface).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onDec), contentAlignment = Alignment.Center) {
            Symbol(Sym.Remove, size = 20.dp, tint = cs.onSurface)
        }
        Text(value.toString(), Modifier.widthIn(min = 36.dp), style = gs(18, 24, 600, tnum = true), textAlign = TextAlign.Center)
        Box(Modifier.size(36.dp).clip(CircleShape).background(acc.accent).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onInc), contentAlignment = Alignment.Center) {
            Symbol(Sym.Add, size = 20.dp, tint = acc.onAccent)
        }
    }
}

/** accentContainer hero card (32 dp corners). */
@Composable
fun HeroCard(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val acc = AntTheme.accent
    CompositionLocalProvider(LocalContentColor provides acc.onContainer) {
        Column(
            modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(acc.container).padding(padding),
            content = content,
        )
    }
}

enum class BtnKind { Fill, Outline, OutlineOnSurface, Big }

data class ToolButton(val text: String, val icon: String, val kind: BtnKind = BtnKind.Fill, val onClick: () -> Unit)

@Composable
fun RowScope.ToolBtn(b: ToolButton, grow: Boolean = true) {
    val acc = AntTheme.accent
    val shape = RoundedCornerShape(28.dp)
    val (bg, fg, border) = when (b.kind) {
        BtnKind.Fill, BtnKind.Big -> Triple(acc.accent, acc.onAccent, null)
        BtnKind.Outline -> Triple(Color.Transparent, acc.onContainer, acc.onContainer)
        BtnKind.OutlineOnSurface -> Triple(Color.Transparent, acc.accent, cs.outline)
    }
    Row(
        (if (grow) Modifier.weight(1f) else Modifier).height(if (b.kind == BtnKind.Big) 56.dp else 48.dp).clip(shape)
            .background(bg).then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(onClick = b.onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Symbol(b.icon, size = 20.dp, filled = true, tint = fg)
        Text(b.text, style = rf(if (b.kind == BtnKind.Big) 16 else 15, 20, 600), color = fg, maxLines = 1)
    }
}

@Composable
fun ToolButtons(vararg buttons: ToolButton, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { ToolBtn(it) }
    }
}

/** Full-width 56 dp start button. */
@Composable
fun StartButton(text: String, icon: String = Sym.PlayArrow, enabled: Boolean = true, onClick: () -> Unit) {
    val acc = AntTheme.accent
    // Start ↔ stop and enabled ↔ disabled ease over instead of switching at once.
    val bg by androidx.compose.animation.animateColorAsState(if (enabled) acc.accent else cs.onSurface.copy(alpha = 0.12f), tween(250), label = "startBg")
    val fg by androidx.compose.animation.animateColorAsState(if (enabled) acc.onAccent else cs.onSurface.copy(alpha = 0.38f), tween(250), label = "startFg")
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(bg)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(
            icon to text,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(220, 60)) + androidx.compose.animation.scaleIn(tween(220, 60), initialScale = 0.92f))
                    .togetherWith(androidx.compose.animation.fadeOut(tween(90)))
            },
            label = "startLabel",
        ) { (i, t) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(i, size = 22.dp, filled = true, tint = fg)
                Text(t, style = rf(16, 20, 600), color = fg)
            }
        }
    }
}

/** errorContainer card with the fix-it action. */
@Composable
fun ToolError(icon: String, title: String, message: String, button: String, onClick: () -> Unit) {
    Row(
        Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.errorContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(icon, size = 24.dp, filled = true, tint = cs.onErrorContainer)
        Column(Modifier.weight(1f)) {
            Text(title, style = rf(15, 20, 600), color = cs.onErrorContainer)
            Text(message, Modifier.padding(top = 2.dp), style = rf(13, 18), color = cs.onErrorContainer)
            Box(
                Modifier.padding(top = 12.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(cs.error)
                    .clickable(onClick = onClick).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(button, style = rf(14, 20, 600), color = cs.onError) }
        }
    }
}

/** Neutral empty state inside a tool (cookie 96 dp, tonal action). */
@Composable
fun ToolEmpty(icon: String, title: String, message: String, button: String?, onClick: () -> Unit = {}) {
    Column(
        Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(cs.surfaceContainerLow)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShapeBadge(icon, cookieShape(), 96.dp, cs.surfaceContainerHighest, cs.onSurfaceVariant, 44.dp, spinMs = 40_000)
        Text(title, Modifier.padding(top = 18.dp), style = gs(22, 28, 500), textAlign = TextAlign.Center)
        Text(message, Modifier.padding(top = 8.dp).widthIn(max = 300.dp), style = rf(14, 20), color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        if (button != null) {
            val acc = AntTheme.accent
            Box(
                Modifier.padding(top = 20.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(acc.container)
                    .clickable(onClick = onClick).padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { Text(button, style = rf(15, 20, 600), color = acc.onContainer) }
        }
    }
}

/**
 * A progress value that eases forward but jumps back: a test started again starts from empty instead of rewinding
 * from full.
 */
@Composable
fun rememberProgress(fraction: Float): androidx.compose.runtime.State<Float> {
    val target = fraction.coerceIn(0f, 1f)
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(target) }
    androidx.compose.runtime.LaunchedEffect(target) {
        if (target < a.value - 0.001f) a.snapTo(target) else a.animateTo(target, Motion.standard())
    }
    return a.asState()
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, track: Color = cs.surfaceContainerHighest, height: Dp = 8.dp) {
    val f by rememberProgress(fraction)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(AntTheme.accent.accent))
    }
}

@Composable
fun IndeterminateBar(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "indet")
    val p by t.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1400, easing = Motion.Emphasized)), label = "indet")
    val acc = AntTheme.accent.accent
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.surfaceContainerHighest)) {
        val w = maxWidth
        Box(Modifier.width(w * 0.4f).fillMaxHeight().offset { androidx.compose.ui.unit.IntOffset((w * p).roundToPx(), 0) }.clip(RoundedCornerShape(2.dp)).background(acc))
    }
}

@Composable
fun Spinner(size: Dp = 28.dp) {
    val t = rememberInfiniteTransition(label = "spinner")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spinner")
    val acc = AntTheme.accent.accent
    val track = cs.surfaceContainerHighest
    Canvas(Modifier.size(size)) {
        val sw = 3.dp.toPx()
        drawCircle(track, size.toPx() / 2 - sw / 2, style = Stroke(sw))
        drawArc(acc, a, 90f, false, Offset(sw / 2, sw / 2), Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Circular progress ring with a centred label. */
@Composable
fun ProgressRing(fraction: Float, label: String, size: Dp = 96.dp, stroke: Dp = 10.dp, track: Color = cs.surface) {
    val f by rememberProgress(fraction)
    val acc = AntTheme.accent.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            if (f > 0.002f) drawArc(acc, -90f, 360f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(label, style = gs(22, 28, 600, tnum = true))
    }
}

/** Small tile shown inside a hero card, on the surface color. */
@Composable
fun HeroTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(cs.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = gs(24, 30, 500, tnum = true), color = cs.onSurface, maxLines = 1)
        Text(label, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
    }
}

/** Status pill on the surface color, used in hero cards. */
@Composable
fun HeroChip(text: String, dot: Color, blink: Boolean = false) {
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (blink) com.allnetworktools.ui.components.BlinkDot(dot, 8.dp, 1200) else Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, style = rf(12, 16, 600), color = cs.onSurface, maxLines = 1)
    }
}

/** Big metric with unit, for hero cards. */
@Composable
fun HeroMetric(value: String, unit: String, size: Int = 56, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = gs(size, size + 4, 500, -1.5f, tnum = true))
        Text(unit, Modifier.padding(bottom = (size / 8).dp), style = rf(18, 24, 500))
    }
}

/** Monospace log card. */
@Composable
fun LogCard(title: String, lines: List<Pair<String, Color?>>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, Modifier.padding(bottom = 8.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
        lines.forEachIndexed { i, (t, c) ->
            Text(t, style = mono(11.5f, 22), color = c ?: if (i == 0) cs.onSurface else cs.onSurfaceVariant, maxLines = 1)
        }
    }
}
