package com.drdisagree.teledrive.domain.repository

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.domain.model.CloudSyncSummary
import kotlinx.coroutines.flow.StateFlow

interface CloudSyncRepository {

    val syncing: StateFlow<Boolean>

    /**
     * Registers a new cloud account and saves the encrypted session & settings userdata.
     */
    suspend fun registerAndSave(
        username: String,
        password: String,
        serverUrl: String = ""
    ): AppResult<CloudSyncSummary>

    /**
     * Signs in to an existing cloud account, restores encrypted session & settings userdata,
     * and saves the merged state back to the cloud.
     */
    suspend fun signInAndSync(
        username: String,
        password: String,
        serverUrl: String = ""
    ): AppResult<CloudSyncSummary>

    /**
     * Pulls and restores encrypted session & settings userdata from the cloud.
     */
    suspend fun pullFromCloud(
        username: String? = null,
        password: String? = null,
        serverUrl: String? = null
    ): AppResult<CloudSyncSummary>

    /**
     * Encrypts and pushes current session & settings userdata to the cloud.
     */
    suspend fun pushToCloud(
        passwordOverride: String? = null
    ): AppResult<CloudSyncSummary>

    /**
     * Performs a two-way session & settings sync using stored credentials.
     */
    suspend fun syncNow(): AppResult<CloudSyncSummary>

    /** Uploads only TDLib root database files and content/logger/TDLib key files. */
    suspend fun pushSessionMigration(): AppResult<CloudSyncSummary>

    /** Downloads and restores the latest session-only migration. */
    suspend fun pullSessionMigration(): AppResult<CloudSyncSummary>

    /**
     * Disconnects the cloud connection on this device without deleting remote userdata.
     */
    suspend fun signOut()
}
