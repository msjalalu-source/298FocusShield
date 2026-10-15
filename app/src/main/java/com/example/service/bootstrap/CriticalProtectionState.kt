package com.example.service.bootstrap

import com.example.data.BlockedSearchKeyword

/**
 * 5-State Lifecycle Readiness State Machine for Critical Security Protection:
 * NOT_READY -> STARTING -> READY / DEGRADED / FAILED
 */
enum class CriticalReadinessState {
    NOT_READY,
    STARTING,
    READY,
    DEGRADED,
    FAILED;

    companion object {
        val UNINITIALIZED = NOT_READY
        val BOOTSTRAPPING = STARTING
        val NOT_INITIALIZED = NOT_READY
        val SYSTEMUI_PROTECTION_READY = READY
        val CRITICAL_STATE_LOADED = READY
        val SERVICE_CONNECTED = READY
        val ENFORCEMENT_READY = READY
    }
}

/**
 * Immutable state model representing the authoritative Critical Protection state.
 * Loaded synchronously from Device Protected Storage with 0ms database latency.
 */
data class CriticalProtectionState(
    val readinessState: CriticalReadinessState = CriticalReadinessState.NOT_READY,
    val controlCenterLockEnabled: Boolean = false,
    val controlCenterProtectionReady: Boolean = false,
    val isProtectionEnabled: Boolean = true,
    val lockedApps: Map<String, String> = emptyMap(),
    val knownApps: Set<String> = emptySet(),
    val blockedKeywords: List<BlockedSearchKeyword> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val schemaVersion: Int = 2,
    val createdAtMs: Long = 0L,
    val isStale: Boolean = false,
    val isValid: Boolean = true
) {
    companion object {
        val UNINITIALIZED = CriticalProtectionState(
            readinessState = CriticalReadinessState.NOT_READY,
            controlCenterLockEnabled = false,
            controlCenterProtectionReady = false,
            isProtectionEnabled = true,
            isValid = false
        )
    }
}

