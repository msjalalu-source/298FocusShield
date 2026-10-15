package com.example.service.protection

import com.example.service.search.SearchBlockingTarget

/**
 * Interface providing fast in-memory access to protection settings.
 * Hot path: zero Room DB queries, zero disk I/O, zero reflection.
 */
interface ProtectionStateProvider {
    fun isSearchBlockingEnabled(target: SearchBlockingTarget): Boolean
}
