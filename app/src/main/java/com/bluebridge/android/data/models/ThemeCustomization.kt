package com.bluebridge.android.data.models

enum class UiColorSlot(
    val key: String,
    val displayName: String,
    val description: String
) {
    PRIMARY("primary", "Primary accent", "Buttons, sliders, selected controls, active icons"),
    PRIMARY_CONTAINER("primaryContainer", "Primary container", "Selected chips, filled icon backgrounds, strong accent panels"),
    DASHBOARD_CARD_BLEND("dashboardCardBlend", "Dashboard card gradient", "Blend color for the main dashboard vehicle card"),
    SECONDARY("secondary", "Secondary accent", "Supporting controls and alternate accent elements"),
    TERTIARY("tertiary", "Tertiary accent", "Highlights and supplemental accents"),
    BACKGROUND("background", "App background", "Main screen background and top bars"),
    SURFACE("surface", "Cards / panels", "Section cards, dialogs, and major panels"),
    SURFACE_VARIANT("surfaceVariant", "Inset surfaces", "Nested cards, chips, progress backgrounds, soft panels"),
    TEXT_PRIMARY("textPrimary", "Primary text", "Main labels, values, and high-emphasis icons"),
    TEXT_SECONDARY("textSecondary", "Secondary text", "Subtitles, helper text, dividers, and muted icons"),
    COMMAND_BANNER("commandBanner", "Command banner", "Sending and refreshing command status background"),
    SUCCESS("success", "Success", "Successful command and good status indicators"),
    WARNING("warning", "Warning", "Caution and partial status indicators"),
    ERROR("error", "Error", "Failed commands and destructive actions"),
    CHARGING("charging", "Charging", "Charging state and EV energy indicators");

    companion object {
        fun fromKey(key: String): UiColorSlot? = entries.firstOrNull { it.key == key }
    }
}

data class UiColorOverrides(
    val primary: String? = null,
    val primaryContainer: String? = null,
    val dashboardCardBlend: String? = null,
    val secondary: String? = null,
    val tertiary: String? = null,
    val background: String? = null,
    val surface: String? = null,
    val surfaceVariant: String? = null,
    val textPrimary: String? = null,
    val textSecondary: String? = null,
    val commandBanner: String? = null,
    val success: String? = null,
    val warning: String? = null,
    val error: String? = null,
    val charging: String? = null
) {
    fun valueFor(slot: UiColorSlot): String? = when (slot) {
        UiColorSlot.PRIMARY -> primary
        UiColorSlot.PRIMARY_CONTAINER -> primaryContainer
        UiColorSlot.DASHBOARD_CARD_BLEND -> dashboardCardBlend
        UiColorSlot.SECONDARY -> secondary
        UiColorSlot.TERTIARY -> tertiary
        UiColorSlot.BACKGROUND -> background
        UiColorSlot.SURFACE -> surface
        UiColorSlot.SURFACE_VARIANT -> surfaceVariant
        UiColorSlot.TEXT_PRIMARY -> textPrimary
        UiColorSlot.TEXT_SECONDARY -> textSecondary
        UiColorSlot.COMMAND_BANNER -> commandBanner
        UiColorSlot.SUCCESS -> success
        UiColorSlot.WARNING -> warning
        UiColorSlot.ERROR -> error
        UiColorSlot.CHARGING -> charging
    }

    fun withValue(slot: UiColorSlot, hex: String?): UiColorOverrides = when (slot) {
        UiColorSlot.PRIMARY -> copy(primary = hex)
        UiColorSlot.PRIMARY_CONTAINER -> copy(primaryContainer = hex)
        UiColorSlot.DASHBOARD_CARD_BLEND -> copy(dashboardCardBlend = hex)
        UiColorSlot.SECONDARY -> copy(secondary = hex)
        UiColorSlot.TERTIARY -> copy(tertiary = hex)
        UiColorSlot.BACKGROUND -> copy(background = hex)
        UiColorSlot.SURFACE -> copy(surface = hex)
        UiColorSlot.SURFACE_VARIANT -> copy(surfaceVariant = hex)
        UiColorSlot.TEXT_PRIMARY -> copy(textPrimary = hex)
        UiColorSlot.TEXT_SECONDARY -> copy(textSecondary = hex)
        UiColorSlot.COMMAND_BANNER -> copy(commandBanner = hex)
        UiColorSlot.SUCCESS -> copy(success = hex)
        UiColorSlot.WARNING -> copy(warning = hex)
        UiColorSlot.ERROR -> copy(error = hex)
        UiColorSlot.CHARGING -> copy(charging = hex)
    }

    fun activeCount(): Int = UiColorSlot.entries.count { !valueFor(it).isNullOrBlank() }

    companion object {
        val EMPTY = UiColorOverrides()
    }
}
