package com.example.service.protection

/**
 * Result model returned by ProtectionEngine for a ProtectionRequest.
 */
sealed class ProtectionDecision {
    data object Allow : ProtectionDecision()

    data class Block(
        val type: ProtectionType,
        val reason: String = "",
        val targetName: String = "",
        val customMessage: String? = null
    ) : ProtectionDecision()
}
