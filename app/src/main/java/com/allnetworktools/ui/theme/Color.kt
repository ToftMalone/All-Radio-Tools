package com.allnetworktools.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.allnetworktools.model.Network
import com.google.android.material.color.MaterialColors

/** Seed #8C4A5A, used when the platform cannot provide wallpaper colors. */
val RoseLight = lightColorScheme(
    primary = Color(0xFF8C4A5A), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E0), onPrimaryContainer = Color(0xFF3A0718),
    secondary = Color(0xFF75565C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E0), onSecondaryContainer = Color(0xFF2B151A),
    tertiary = Color(0xFF7B5733), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBE), onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F7), onBackground = Color(0xFF22191B),
    surface = Color(0xFFFFF8F7), onSurface = Color(0xFF22191B),
    surfaceVariant = Color(0xFFF3DDE0), onSurfaceVariant = Color(0xFF524345),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCF0F1),
    surfaceContainer = Color(0xFFF6EAEB), surfaceContainerHigh = Color(0xFFF1E4E5),
    surfaceContainerHighest = Color(0xFFEBDEE0),
    outline = Color(0xFF847375), outlineVariant = Color(0xFFD7C1C4),
    inverseSurface = Color(0xFF382E30), inverseOnSurface = Color(0xFFFEEDEF),
    inversePrimary = Color(0xFFFFB1C1),
)

val RoseDark = darkColorScheme(
    primary = Color(0xFFFFB1C1), onPrimary = Color(0xFF551D2D),
    primaryContainer = Color(0xFF713343), onPrimaryContainer = Color(0xFFFFD9E0),
    secondary = Color(0xFFE4BDC3), onSecondary = Color(0xFF43292E),
    secondaryContainer = Color(0xFF5C3F44), onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = Color(0xFFEDBD91), onTertiary = Color(0xFF462A09),
    tertiaryContainer = Color(0xFF60401E), onTertiaryContainer = Color(0xFFFFDCBE),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191113), onBackground = Color(0xFFEFDFE1),
    surface = Color(0xFF191113), onSurface = Color(0xFFEFDFE1),
    surfaceVariant = Color(0xFF524345), onSurfaceVariant = Color(0xFFD7C1C4),
    surfaceContainerLowest = Color(0xFF140C0E), surfaceContainerLow = Color(0xFF22191B),
    surfaceContainer = Color(0xFF261D1F), surfaceContainerHigh = Color(0xFF312829),
    surfaceContainerHighest = Color(0xFF3C3234),
    outline = Color(0xFF9F8C8F), outlineVariant = Color(0xFF524345),
    inverseSurface = Color(0xFFEFDFE1), inverseOnSurface = Color(0xFF382E30),
    inversePrimary = Color(0xFF8C4A5A),
)

/** Neutral base palette shown when Dynamic Color is turned off. */
val NeutralLight = RoseLight.copy(
    primary = Color(0xFF455E83), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF565F71), secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF131C2B),
    background = Color(0xFFF8F9FB), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FB), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEEF2), surfaceContainerHigh = Color(0xFFE6E8EC),
    surfaceContainerHighest = Color(0xFFE0E2E6),
    outline = Color(0xFF73777F), outlineVariant = Color(0xFFC3C6CF),
    inverseSurface = Color(0xFF2E3035), inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFADC6EE),
)

val NeutralDark = RoseDark.copy(
    primary = Color(0xFFADC6EE), onPrimary = Color(0xFF133053),
    primaryContainer = Color(0xFF2D476A), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC), secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E), onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8D9199), outlineVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFFE2E2E9), inverseOnSurface = Color(0xFF2E3035),
    inversePrimary = Color(0xFF455E83),
)

@Immutable
data class AccentRoles(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
)

@Immutable
data class NetworkColors(
    val wifi: AccentRoles,
    val bt: AccentRoles,
    val cell: AccentRoles,
    val gnss: AccentRoles,
    val good: Color,
    val fair: Color,
    val poor: Color,
    val gps: Color,
    val galileo: Color,
    val glonass: Color,
    val beidou: Color,
    val qzss: Color,
    val shadow: Color,
    val sdr: AccentRoles,
) {
    operator fun get(network: Network): AccentRoles = when (network) {
        Network.Wifi -> wifi
        Network.Bluetooth -> bt
        Network.Cellular -> cell
        Network.Gnss -> gnss
        Network.Sdr -> sdr
    }
}

private val BaseAccents = mapOf(
    Network.Wifi to Color(0xFF3A5BA9),
    Network.Bluetooth to Color(0xFF5E4DB2),
    Network.Cellular to Color(0xFF2F6A3E),
    Network.Gnss to Color(0xFF7F5700),
    Network.Sdr to Color(0xFF006A6A),
)

val NetworkColorsLight = NetworkColors(
    wifi = AccentRoles(Color(0xFF3A5BA9), Color.White, Color(0xFFDAE2FF), Color(0xFF001946)),
    bt = AccentRoles(Color(0xFF5E4DB2), Color.White, Color(0xFFE6DEFF), Color(0xFF1A0063)),
    cell = AccentRoles(Color(0xFF2F6A3E), Color.White, Color(0xFFB2F1BA), Color(0xFF00210C)),
    gnss = AccentRoles(Color(0xFF7F5700), Color.White, Color(0xFFFFDEA6), Color(0xFF281800)),
    good = Color(0xFF2F6A3E), fair = Color(0xFF8A5A00), poor = Color(0xFFBA1A1A),
    gps = Color(0xFF3A5BA9), galileo = Color(0xFF5E4DB2), glonass = Color(0xFFB3261E),
    beidou = Color(0xFF2F6A3E), qzss = Color(0xFF8A5A00),
    shadow = Color(0x47461423),
    sdr = AccentRoles(Color(0xFF006A6A), Color.White, Color(0xFF9CF1F0), Color(0xFF002020)),
)

val NetworkColorsDark = NetworkColors(
    wifi = AccentRoles(Color(0xFFB1C5FF), Color(0xFF002C71), Color(0xFF23438F), Color(0xFFDAE2FF)),
    bt = AccentRoles(Color(0xFFCBBEFF), Color(0xFF2F1A83), Color(0xFF46339A), Color(0xFFE6DEFF)),
    cell = AccentRoles(Color(0xFF97D5A0), Color(0xFF003919), Color(0xFF155129), Color(0xFFB2F1BA)),
    gnss = AccentRoles(Color(0xFFF8BD49), Color(0xFF432C00), Color(0xFF604100), Color(0xFFFFDEA6)),
    good = Color(0xFF97D5A0), fair = Color(0xFFF8BD49), poor = Color(0xFFFFB4AB),
    gps = Color(0xFFB1C5FF), galileo = Color(0xFFCBBEFF), glonass = Color(0xFFFFB4AB),
    beidou = Color(0xFF97D5A0), qzss = Color(0xFFF8BD49),
    shadow = Color(0x99000000),
    sdr = AccentRoles(Color(0xFF80D5D4), Color(0xFF003737), Color(0xFF004F4F), Color(0xFF9CF1F0)),
)

/** Network accents shifted toward the dynamic primary, each expanded into its 4 Compose roles. */
fun harmonizedNetworkColors(primary: Color, dark: Boolean): NetworkColors {
    val base = if (dark) NetworkColorsDark else NetworkColorsLight
    fun roles(network: Network): AccentRoles {
        val harmonized = MaterialColors.harmonize(BaseAccents.getValue(network).toArgb(), primary.toArgb())
        val r = MaterialColors.getColorRoles(harmonized, !dark)
        return AccentRoles(Color(r.accent), Color(r.onAccent), Color(r.accentContainer), Color(r.onAccentContainer))
    }
    return base.copy(
        wifi = roles(Network.Wifi),
        bt = roles(Network.Bluetooth),
        cell = roles(Network.Cellular),
        gnss = roles(Network.Gnss),
        sdr = roles(Network.Sdr),
    )
}

fun staticColorScheme(dark: Boolean): ColorScheme = if (dark) NeutralDark else NeutralLight
