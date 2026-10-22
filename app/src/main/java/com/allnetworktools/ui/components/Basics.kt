package com.allnetworktools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.SymbolsFilled
import com.allnetworktools.ui.theme.SymbolsOutlined
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf

/** A Material Symbols Rounded glyph rendered from the bundled variable font. */
@Composable
fun Symbol(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    filled: Boolean = false,
    tint: Color = LocalContentColor.current,
) {
    val sp = with(LocalDensity.current) { size.toSp() }
    Text(
        text = name,
        modifier = modifier,
        style = TextStyle(
            fontFamily = if (filled) SymbolsFilled else SymbolsOutlined,
            fontSize = sp,
            lineHeight = sp,
            color = tint,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        softWrap = false,
    )
}

/** Grouped list shape: 24 dp outer corners, [inner] between items. */
fun groupShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 8.dp): Shape = when {
    count == 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
    index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
    else -> RoundedCornerShape(inner)
}


/** surfaceContainerLow card with 24 dp corners. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    color: Color = cs.surfaceContainerLow,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, color: Color = AntTheme.accent.accent, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 8.dp), style = rf(14, 20, 600), color = color)
}

@Composable
fun CardHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = rf(16, 22, 600))
        trailing()
    }
}

@Composable
fun MetricTile(label: String, value: String, unit: String = "", icon: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Symbol(icon, size = 16.dp, tint = cs.onSurfaceVariant)
            Text(label, style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = gs(28, 32, 500, tnum = true), maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, Modifier.padding(bottom = 3.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
        }
    }
}

/** Small key/value tile used on the GNSS and compass screens. */
@Composable
fun ValueTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, style = rf(12, 16), color = cs.onSurfaceVariant)
        Text(value, style = gs(20, 28, 500, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Two tiles per row. */
@Composable
fun <T> TileGrid(items: List<T>, gap: Dp = 8.dp, tile: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(2).forEach { row ->
            // Same height for both tiles of a row, whatever their text.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 48 dp key/value row separated by a hairline; optional copy action. */
@Composable
fun InfoRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
    HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onCopy != null) Modifier.clickable(onClick = onCopy) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = rf(14, 20), color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = rf(14, 20, 500, tnum = true), textAlign = TextAlign.End, modifier = Modifier.padding(vertical = 8.dp))
        if (onCopy != null) Symbol(Sym.ContentCopy, size = 16.dp, tint = cs.onSurfaceVariant)
    }
}

@Composable
fun InfoList(title: String, titleColor: Color = AntTheme.accent.accent, trailing: @Composable RowScope.() -> Unit = {}, rows: @Composable ColumnScope.() -> Unit) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = rf(14, 20, 600), color = titleColor)
            trailing()
        }
        rows()
    }
}

/** Status pill with a colored dot, on the surface color. */
@Composable
fun StatusChip(text: String, dot: Color, modifier: Modifier = Modifier, blink: Boolean = false) {
    Row(
        modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (blink) BlinkDot(dot, 8.dp) else Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, style = rf(12, 16, 500), color = cs.onSurface, maxLines = 1)
    }
}

/** Filled label pill (e.g. "5G SA"). */
@Composable
fun TechChip(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = rf(12, 16, 700, tracking = 0.3f), color = fg, maxLines = 1)
    }
}

/** Outlined info chip (profiles, capabilities). */
@Composable
fun OutlineChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = rf(12, 16, 500)) }
}

/** Chip with a leading icon on the surface color (Wi-Fi dashboard hero). */
@Composable
fun IconChip(icon: String, text: String, iconTint: Color) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(cs.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(icon, size = 18.dp, filled = true, tint = iconTint)
        Text(text, style = rf(13, 18, 500), color = cs.onSurface, maxLines = 1)
    }
}

/** Single-choice segmented row in the style of the mockups (pill container, filled selection). */
@Composable
fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: Color = AntTheme.accent.accent,
    onSelectedColor: Color = AntTheme.accent.onAccent,
    height: Dp = 40.dp,
    icons: Map<T, String> = emptyMap(),
    container: Color = cs.surfaceContainerHigh,
) {
    val haptics = AntTheme.haptics
    Row(
        modifier.clip(RoundedCornerShape(height / 2 + 4.dp)).background(container).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val bg by animateColorAsState(if (active) selectedColor else Color.Transparent, tween(250, easing = Motion.Emphasized), label = "seg")
            val fg by animateColorAsState(if (active) onSelectedColor else cs.onSurfaceVariant, tween(250, easing = Motion.Emphasized), label = "segFg")
            Row(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(height / 2 - 4.dp))
                    .background(bg)
                    .clickable { if (!active) { haptics.segment(); onSelect(value) } }
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons[value]?.let { Symbol(it, Modifier.padding(end = 4.dp), size = 18.dp, filled = active, tint = fg) }
                Text(label, style = rf(if (height < 40.dp) 13 else 14, 18, 600), color = fg, maxLines = 1)
            }
        }
    }
}

/** M3 filter chip look: check + container when selected (200 ms). */
@Composable
fun AntFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    color: Color = AntTheme.accent.container,
    onColor: Color = AntTheme.accent.onContainer,
    leadingDot: Color? = null,
    trailing: String? = null,
) {
    val bg by animateColorAsState(if (selected) color else Color.Transparent, tween(200, easing = Motion.Emphasized), label = "chip")
    val haptics = AntTheme.haptics
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else cs.outlineVariant, RoundedCornerShape(8.dp))
            .clickable { haptics.tick(); onClick() }
            .padding(start = if (selected || leadingDot != null) 8.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected && leadingDot == null) Symbol(Sym.Check, size = 18.dp, tint = onColor)
        if (leadingDot != null) Box(Modifier.padding(horizontal = 2.dp).size(8.dp).clip(CircleShape).background(if (selected) leadingDot else cs.outline))
        Text(text, style = rf(13, 18, 600), color = if (selected) onColor else cs.onSurfaceVariant, maxLines = 1)
        if (trailing != null) Text(trailing, style = rf(12, 16, 700, tnum = true), color = if (selected) onColor else cs.onSurfaceVariant)
    }
}

/** 56 dp (or 48/40) pill button, filled with the accent. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    bg: Color = AntTheme.accent.accent,
    fg: Color = AntTheme.accent.onAccent,
    height: Dp = 56.dp,
    outlined: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .height(height)
            .clip(shape)
            .then(if (outlined) Modifier.border(1.dp, cs.outline, shape) else Modifier.background(if (enabled) bg else cs.onSurface.copy(alpha = 0.12f)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (height >= 56.dp) 28.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val color = when {
            !enabled -> cs.onSurface.copy(alpha = 0.38f)
            outlined -> bg
            else -> fg
        }
        if (icon != null) Symbol(icon, size = 20.dp, filled = !outlined, tint = color)
        Text(text, style = rf(if (height >= 56.dp) 16 else 14, 20, 600), color = color, maxLines = 1)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = AntTheme.accent.accent, trailingIcon: String? = Sym.ChevronRight) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = rf(13, 18, 600), color = color)
        if (trailingIcon != null) Symbol(trailingIcon, size = 18.dp, tint = color)
    }
}

@Composable
fun IconCircleButton(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, bg: Color = Color.Transparent, tint: Color = LocalContentColor.current, size: Dp = 48.dp, filled: Boolean = false) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Symbol(icon, size = 24.dp, tint = tint, filled = filled) }
}

/** Rounded square holding an icon, used as list leading. */
@Composable
fun LeadingIcon(icon: String, bg: Color, fg: Color, size: Dp = 44.dp, shape: Shape = RoundedCornerShape(16.dp), iconSize: Dp = 24.dp) {
    Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Symbol(icon, size = iconSize, filled = true, tint = fg)
    }
}

/** Horizontal level bar (quality bars in lists). */
@Composable
fun LevelBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, track: Color = cs.surfaceContainerHighest) {
    val f by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.standard(), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(height / 2)).background(color))
    }
}


@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (t, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Text(t, style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = cs.outlineVariant, thickness = 1.dp)
