package to.eyed.inferno.data

/**
 * Developer-mode sub-toggles (SettingsState.show*). One enum instead of eight setters: the Developer page
 * iterates it for the toggle list and AppPrefs.setDevFlag writes through [set]. Labels live in ui/Strings.kt.
 */
enum class DevFlag(val get: (SettingsState) -> Boolean, val set: (SettingsState, Boolean) -> SettingsState) {
    GENERATION_STATS({ it.showGenerationStats }, { s, v -> s.copy(showGenerationStats = v) }),
    CONTEXT_METER({ it.showContextMeter }, { s, v -> s.copy(showContextMeter = v) }),
    TURN_DETAILS({ it.showTurnDetails }, { s, v -> s.copy(showTurnDetails = v) }),
    BENCHMARK({ it.showBenchmark }, { s, v -> s.copy(showBenchmark = v) }),
    MODEL_TECH_SPECS({ it.showModelTechSpecs }, { s, v -> s.copy(showModelTechSpecs = v) }),
    THERMAL_INFO({ it.showThermalInfo }, { s, v -> s.copy(showThermalInfo = v) }),
    TOKEN_COUNTER({ it.showTokenCounter }, { s, v -> s.copy(showTokenCounter = v) }),
}

// The readers every surface gates on: a sub-toggle counts only while the master switch is on, so a normal user
// (developerMode = false, the default) sees none of the numbers even though the sub-toggles default to true.
val SettingsState.devGenerationStats: Boolean get() = developerMode && showGenerationStats
val SettingsState.devContextMeter: Boolean get() = developerMode && showContextMeter
val SettingsState.devTurnDetails: Boolean get() = developerMode && showTurnDetails
val SettingsState.devBenchmark: Boolean get() = developerMode && showBenchmark
val SettingsState.devModelTechSpecs: Boolean get() = developerMode && showModelTechSpecs
val SettingsState.devThermalInfo: Boolean get() = developerMode && showThermalInfo
val SettingsState.devTokenCounter: Boolean get() = developerMode && showTokenCounter
