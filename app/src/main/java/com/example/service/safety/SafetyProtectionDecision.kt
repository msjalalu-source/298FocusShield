package com.example.service.safety

/**
 * Result of a Safety Protection evaluation.
 */
sealed class SafetyProtectionDecision {
    /**
     * Action or screen view is allowed.
     */
    data class Allow(val reason: String = "") : SafetyProtectionDecision()

    /**
     * Action or screen view is blocked.
     */
    data class Block(
        val targetName: String,
        val targetType: ProtectionTargetType,
        val reason: String = "",
        val actionType: BlockActionType = BlockActionType.NAVIGATE_BACK_OR_HOME
    ) : SafetyProtectionDecision()
}

enum class BlockActionType {
    NAVIGATE_BACK_OR_HOME,
    BACK_ONLY,
    NAVIGATION_BACK,
    NAVIGATION_HOME,
    GLOBAL_BACK,
    GLOBAL_HOME,
    INTERCEPT;

    companion object {
        fun fromString(str: String?): BlockActionType {
            return when (str?.trim()) {
                "Navigation Home" -> NAVIGATION_HOME
                "Global Back" -> GLOBAL_BACK
                "Global Home" -> GLOBAL_HOME
                "Intercept" -> INTERCEPT
                "Back Only", "BACK_ONLY" -> BACK_ONLY
                "Navigation Back", "NAVIGATE_BACK_OR_HOME" -> NAVIGATION_BACK
                else -> NAVIGATE_BACK_OR_HOME
            }
        }
    }
}

