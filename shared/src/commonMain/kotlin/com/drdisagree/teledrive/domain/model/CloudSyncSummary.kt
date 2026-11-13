package com.drdisagree.teledrive.domain.model

data class CloudSyncSummary(
    val username: String,
    val syncedDrives: Int,
    val syncedAt: Long,
    val usedCustomServer: Boolean = false
)
