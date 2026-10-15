package com.example.service.safety

/**
 * Explicitly distinguishes between whole-page targets and target-specific control targets.
 */
enum class ProtectionTargetType {
    /**
     * Whole-page protection: The entire target screen or activity is protected
     * (e.g., Portable Hotspot Configuration, Battery Settings).
     */
    WHOLE_PAGE,

    /**
     * Target-specific control protection: Multi-item list or page is accessible,
     * but interactions targeting Focus Shield deactivation/disabling are blocked
     * (e.g., Accessibility list, Device Admin list, Xiaomi Security Lock Apps).
     */
    TARGET_SPECIFIC_CONTROL
}
