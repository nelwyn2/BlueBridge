package com.bluebridge.android.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.bluebridge.android.data.models.UiColorOverrides
import com.bluebridge.android.data.models.UiColorSlot

// ─── Hyundai-inspired color palette ──────────────────────────────────────────
val HyundaiBlue = Color(0xFF002C5F)       // Primary brand blue
val HyundaiLightBlue = Color(0xFF00AAD2)  // Accent blue
val HyundaiSilver = Color(0xFFCFD4DA)
val HyundaiDarkGray = Color(0xFF1A1A2E)
val HyundaiMidGray = Color(0xFF2D2D44)
val SurfaceCard = Color(0xFF1E1E35)

val SuccessGreen = Color(0xFF4CAF50)
val WarningAmber = Color(0xFFFFA726)
val ErrorRed = Color(0xFFEF5350)
val ChargingGreen = Color(0xFF00E676)


data class BlueBridgeDynamicColors(
    val success: Color = SuccessGreen,
    val warning: Color = WarningAmber,
    val error: Color = ErrorRed,
    val charging: Color = ChargingGreen,
    val commandBanner: Color = Color(0xFF111827),
    val dashboardCardBlend: Color = Color(0xFF0B1B48)
)

val LocalBlueBridgeDynamicColors = staticCompositionLocalOf { BlueBridgeDynamicColors() }

fun normalizeHexColor(input: String?): String? {
    val raw = input?.trim()?.removePrefix("#") ?: return null
    if (raw.length != 6 && raw.length != 8) return null
    if (!raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return "#" + if (raw.length == 6) raw.uppercase() else raw.takeLast(6).uppercase()
}

fun colorFromHexOrNull(input: String?): Color? {
    val normalized = normalizeHexColor(input) ?: return null
    return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
}

fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    return "#%02X%02X%02X".format(red, green, blue)
}

fun UiColorOverrides.colorOr(slot: UiColorSlot, fallback: Color): Color = colorFromHexOrNull(valueFor(slot)) ?: fallback

fun defaultDashboardCardBlendForTheme(theme: AppTheme): Color = when (theme) {
    AppTheme.HYUNDAI_NIGHT -> Color(0xFF0B1B48)
    AppTheme.CYBER_LIME -> Color(0xFF142A04)
    AppTheme.SUNSET_DRIVE -> Color(0xFF351020)
    AppTheme.OCEAN_PULSE -> Color(0xFF052638)
    AppTheme.ELECTRIC_GRAPE -> Color(0xFF1A0A3A)
    AppTheme.CHERRY_BOOST -> Color(0xFF320411)
    AppTheme.ARCTIC_MINT -> Color(0xFF052D28)
    AppTheme.SOLAR_FLARE -> Color(0xFF352000)
}

fun defaultColorForSlot(theme: AppTheme, slot: UiColorSlot): Color = when (slot) {
    UiColorSlot.PRIMARY -> theme.primary
    UiColorSlot.PRIMARY_CONTAINER -> theme.primaryContainer
    UiColorSlot.DASHBOARD_CARD_BLEND -> defaultDashboardCardBlendForTheme(theme)
    UiColorSlot.SECONDARY -> theme.secondary
    UiColorSlot.TERTIARY -> theme.tertiary
    UiColorSlot.BACKGROUND -> theme.background
    UiColorSlot.SURFACE -> theme.surface
    UiColorSlot.SURFACE_VARIANT -> theme.surfaceVariant
    UiColorSlot.TEXT_PRIMARY -> Color(0xFFF0F4FA)
    UiColorSlot.TEXT_SECONDARY -> Color(0xFFD0D6E0)
    UiColorSlot.COMMAND_BANNER -> Color(0xFF111827)
    UiColorSlot.SUCCESS -> SuccessGreen
    UiColorSlot.WARNING -> WarningAmber
    UiColorSlot.ERROR -> ErrorRed
    UiColorSlot.CHARGING -> ChargingGreen
}

enum class AppTheme(
    val id: String,
    val displayName: String,
    val description: String,
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onPrimary: Color = Color(0xFF071018)
) {
    HYUNDAI_NIGHT(
        id = "hyundai_night",
        displayName = "Hyundai Night",
        description = "Midnight blue, cyan, and violet glow",
        primary = Color(0xFF00D4FF),
        primaryContainer = Color(0xFF003965),
        secondary = Color(0xFF8B72FF),
        tertiary = Color(0xFF35F2B6),
        background = Color(0xFF030716),
        surface = Color(0xFF0B1027),
        surfaceVariant = Color(0xFF121B3E),
        onPrimary = Color(0xFF001F2A)
    ),
    CYBER_LIME(
        id = "cyber_lime",
        displayName = "Cyber Lime",
        description = "Lime, aqua, and arcade yellow",
        primary = Color(0xFFC8FF00),
        primaryContainer = Color(0xFF294800),
        secondary = Color(0xFF35F7FF),
        tertiary = Color(0xFFFFEA00),
        background = Color(0xFF020604),
        surface = Color(0xFF081008),
        surfaceVariant = Color(0xFF152412)
    ),
    SUNSET_DRIVE(
        id = "sunset_drive",
        displayName = "Sunset Drive",
        description = "Hot orange, magenta, and violet dusk",
        primary = Color(0xFFFF8A00),
        primaryContainer = Color(0xFF5A1C00),
        secondary = Color(0xFFFF4DA5),
        tertiary = Color(0xFFFFD166),
        background = Color(0xFF12030D),
        surface = Color(0xFF1F0A1A),
        surfaceVariant = Color(0xFF35142B)
    ),
    OCEAN_PULSE(
        id = "ocean_pulse",
        displayName = "Ocean Pulse",
        description = "Aqua, cobalt, and seafoam neon",
        primary = Color(0xFF00F5D4),
        primaryContainer = Color(0xFF004C4A),
        secondary = Color(0xFF21CFFF),
        tertiary = Color(0xFF80FFDB),
        background = Color(0xFF021018),
        surface = Color(0xFF061B28),
        surfaceVariant = Color(0xFF0D3044)
    ),
    ELECTRIC_GRAPE(
        id = "electric_grape",
        displayName = "Electric Grape",
        description = "Violet, laser blue, and neon pink",
        primary = Color(0xFFD000FF),
        primaryContainer = Color(0xFF461067),
        secondary = Color(0xFF21CFFF),
        tertiary = Color(0xFFFF63FF),
        background = Color(0xFF0B0016),
        surface = Color(0xFF170629),
        surfaceVariant = Color(0xFF280D45),
        onPrimary = Color(0xFFFFFFFF)
    ),
    CHERRY_BOOST(
        id = "cherry_boost",
        displayName = "Cherry Boost",
        description = "Cherry red, hot pink, and turbo orange",
        primary = Color(0xFFFF1744),
        primaryContainer = Color(0xFF650018),
        secondary = Color(0xFFFF6FA0),
        tertiary = Color(0xFFFFB000),
        background = Color(0xFF100004),
        surface = Color(0xFF20050D),
        surfaceVariant = Color(0xFF360B19),
        onPrimary = Color(0xFFFFFFFF)
    ),
    ARCTIC_MINT(
        id = "arctic_mint",
        displayName = "Arctic Mint",
        description = "Mint, frost blue, and soft lavender",
        primary = Color(0xFF64FFDA),
        primaryContainer = Color(0xFF004C43),
        secondary = Color(0xFF92E8F7),
        tertiary = Color(0xFFC1A2FF),
        background = Color(0xFF02100F),
        surface = Color(0xFF071C1A),
        surfaceVariant = Color(0xFF0F2E2B)
    ),
    SOLAR_FLARE(
        id = "solar_flare",
        displayName = "Solar Flare",
        description = "Solar yellow, orange, and electric cyan",
        primary = Color(0xFFFFD400),
        primaryContainer = Color(0xFF5C3A00),
        secondary = Color(0xFFFF7A1A),
        tertiary = Color(0xFF32EAFF),
        background = Color(0xFF100900),
        surface = Color(0xFF1E1200),
        surfaceVariant = Color(0xFF352100)
    );

    companion object {
        const val DEFAULT_ID = "hyundai_night"

        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: HYUNDAI_NIGHT
    }
}

fun availableAppThemes(): List<AppTheme> = AppTheme.entries

val md_theme_dark_primary = HyundaiLightBlue
val md_theme_dark_onPrimary = Color(0xFF003547)
val md_theme_dark_primaryContainer = Color(0xFF004D65)
val md_theme_dark_onPrimaryContainer = Color(0xFFB3E5FC)
val md_theme_dark_secondary = Color(0xFFB0BEC5)
val md_theme_dark_onSecondary = Color(0xFF1C3040)
val md_theme_dark_secondaryContainer = Color(0xFF334A5A)
val md_theme_dark_onSecondaryContainer = Color(0xFFCDE7F8)
val md_theme_dark_tertiary = Color(0xFF80DEEA)
val md_theme_dark_onTertiary = Color(0xFF003740)
val md_theme_dark_tertiaryContainer = Color(0xFF004E5B)
val md_theme_dark_onTertiaryContainer = Color(0xFFA6EEFF)
val md_theme_dark_error = ErrorRed
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = Color(0xFF0F0F1E)
val md_theme_dark_onBackground = Color(0xFFE2E2EC)
val md_theme_dark_surface = Color(0xFF141425)
val md_theme_dark_onSurface = Color(0xFFE2E2EC)
val md_theme_dark_surfaceVariant = Color(0xFF1E2030)
val md_theme_dark_onSurfaceVariant = Color(0xFFC3C6CF)
val md_theme_dark_outline = Color(0xFF404759)
val md_theme_dark_inverseOnSurface = Color(0xFF1B1B23)
val md_theme_dark_inverseSurface = Color(0xFFE2E2EC)
val md_theme_dark_inversePrimary = HyundaiBlue
val md_theme_dark_surfaceTint = HyundaiLightBlue
val md_theme_dark_outlineVariant = Color(0xFF44474F)
val md_theme_dark_scrim = Color(0xFF000000)

val md_theme_light_primary = HyundaiBlue
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFD0E4FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001D36)
val md_theme_light_secondary = Color(0xFF546E7A)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFCFE5F1)
val md_theme_light_onSecondaryContainer = Color(0xFF071E28)
val md_theme_light_tertiary = Color(0xFF006876)
val md_theme_light_background = Color(0xFFF8FAFE)
val md_theme_light_onBackground = Color(0xFF1A1C23)
val md_theme_light_surface = Color(0xFFFFFFFF)
val md_theme_light_onSurface = Color(0xFF1A1C23)

private fun darkSchemeForTheme(theme: AppTheme, overrides: UiColorOverrides) = darkColorScheme(
    primary = overrides.colorOr(UiColorSlot.PRIMARY, theme.primary),
    onPrimary = theme.onPrimary,
    primaryContainer = overrides.colorOr(UiColorSlot.PRIMARY_CONTAINER, theme.primaryContainer),
    onPrimaryContainer = Color(0xFFE8F7FF),
    secondary = overrides.colorOr(UiColorSlot.SECONDARY, theme.secondary),
    onSecondary = Color(0xFF071018),
    secondaryContainer = overrides.colorOr(UiColorSlot.SECONDARY, theme.secondary),
    onSecondaryContainer = Color(0xFFE8F7FF),
    tertiary = overrides.colorOr(UiColorSlot.TERTIARY, theme.tertiary),
    onTertiary = Color(0xFF071018),
    tertiaryContainer = overrides.colorOr(UiColorSlot.TERTIARY, theme.tertiary),
    onTertiaryContainer = Color(0xFFE8F7FF),
    error = overrides.colorOr(UiColorSlot.ERROR, ErrorRed),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    background = overrides.colorOr(UiColorSlot.BACKGROUND, theme.background),
    onBackground = overrides.colorOr(UiColorSlot.TEXT_PRIMARY, Color(0xFFF0F4FA)),
    surface = overrides.colorOr(UiColorSlot.SURFACE, theme.surface),
    onSurface = overrides.colorOr(UiColorSlot.TEXT_PRIMARY, Color(0xFFF0F4FA)),
    surfaceVariant = overrides.colorOr(UiColorSlot.SURFACE_VARIANT, theme.surfaceVariant),
    onSurfaceVariant = overrides.colorOr(UiColorSlot.TEXT_SECONDARY, Color(0xFFD0D6E0)),
    outline = Color(0xFF6F7885),
    outlineVariant = Color(0xFF3F4752),
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF101820),
    inversePrimary = theme.primaryContainer,
    surfaceTint = overrides.colorOr(UiColorSlot.PRIMARY, theme.primary),
    scrim = Color(0xFF000000),
)

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
)

@Composable
fun BlueBridgeTheme(
    darkTheme: Boolean = true, // Default dark like Bluelink
    appThemeId: String = AppTheme.DEFAULT_ID,
    uiColorOverrides: UiColorOverrides = UiColorOverrides.EMPTY,
    content: @Composable () -> Unit
) {
    val selectedTheme = AppTheme.fromId(appThemeId)
    val colorScheme = if (darkTheme) {
        darkSchemeForTheme(selectedTheme, uiColorOverrides)
    } else {
        LightColorScheme
    }
    val dynamicColors = BlueBridgeDynamicColors(
        success = uiColorOverrides.colorOr(UiColorSlot.SUCCESS, SuccessGreen),
        warning = uiColorOverrides.colorOr(UiColorSlot.WARNING, WarningAmber),
        error = uiColorOverrides.colorOr(UiColorSlot.ERROR, ErrorRed),
        charging = uiColorOverrides.colorOr(UiColorSlot.CHARGING, ChargingGreen),
        commandBanner = uiColorOverrides.colorOr(UiColorSlot.COMMAND_BANNER, Color(0xFF111827)),
        dashboardCardBlend = uiColorOverrides.colorOr(UiColorSlot.DASHBOARD_CARD_BLEND, defaultDashboardCardBlendForTheme(selectedTheme))
    )

    CompositionLocalProvider(LocalBlueBridgeDynamicColors provides dynamicColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BlueBridgeTypography,
            content = content
        )
    }
}
