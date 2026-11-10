package com.nuvio.tv.ui.screens.settings

internal enum class SettingsRailGroup {
    YOU,
    LOOK,
    WATCH,
    SERVICES,
    SYSTEM
}

internal enum class SettingsCategory(val group: SettingsRailGroup) {
    ACCOUNT(SettingsRailGroup.YOU),
    PROFILES(SettingsRailGroup.YOU),
    WATCH_PARTY(SettingsRailGroup.YOU),
    APPEARANCE(SettingsRailGroup.LOOK),
    LAYOUT(SettingsRailGroup.LOOK),
    CONTENT_DISCOVERY(SettingsRailGroup.WATCH),
    PLAYBACK(SettingsRailGroup.WATCH),
    INTEGRATION(SettingsRailGroup.SERVICES),
    TRACKING(SettingsRailGroup.SERVICES),
    ADVANCED(SettingsRailGroup.SYSTEM),
    ABOUT(SettingsRailGroup.SYSTEM),
    DEBUG(SettingsRailGroup.SYSTEM),
    EXPERIENCE(SettingsRailGroup.SYSTEM)
}

internal fun visibleSettingsCategories(
    isPrimaryProfile: Boolean,
    isEssentialMode: Boolean,
    isDebugBuild: Boolean,
    isWatchPartyAvailable: Boolean = false
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    when (category) {
        // WATCHFIRST: the member's access replaces the Nuvio account, addons come from the plan, and
        // integrations, tracking and Watch Party are hidden, as on the PC and phone apps.
        SettingsCategory.ACCOUNT,
        SettingsCategory.WATCH_PARTY,
        SettingsCategory.CONTENT_DISCOVERY,
        SettingsCategory.INTEGRATION,
        SettingsCategory.TRACKING -> false
        SettingsCategory.PROFILES -> isPrimaryProfile
        SettingsCategory.DEBUG -> isDebugBuild && !isEssentialMode
        SettingsCategory.EXPERIENCE -> false
        else -> true
    }
}

internal fun List<SettingsCategory>.startsNewGroup(index: Int): Boolean =
    index > 0 && this[index - 1].group != this[index].group
