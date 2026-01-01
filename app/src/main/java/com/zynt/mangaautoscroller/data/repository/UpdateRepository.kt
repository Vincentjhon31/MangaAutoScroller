package com.zynt.mangaautoscroller.data.repository

import android.util.Log
import com.zynt.mangaautoscroller.BuildConfig
import com.zynt.mangaautoscroller.data.api.GitHubApiService
import com.zynt.mangaautoscroller.data.model.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for checking app updates from GitHub releases
 */
class UpdateRepository(
    private val apiService: GitHubApiService = GitHubApiService.create()
) {
    companion object {
        private const val TAG = "UpdateRepository"
    }
    
    /**
     * Check for updates and return the latest release if newer version available
     * @return GitHubRelease if update available, null otherwise
     */
    suspend fun checkForUpdates(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getLatestRelease(
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO
            )
            
            if (response.isSuccessful) {
                val release = response.body()
                if (release != null && !release.draft && !release.prerelease) {
                    val latestVersion = release.getVersionNumber()
                    val currentVersion = BuildConfig.VERSION_NAME
                    
                    Log.d(TAG, "Current version: $currentVersion, Latest version: $latestVersion")
                    
                    if (isNewerVersion(latestVersion, currentVersion)) {
                        Log.i(TAG, "✅ Update available: $currentVersion -> $latestVersion")
                        return@withContext release
                    } else {
                        Log.d(TAG, "App is up to date")
                    }
                }
            } else {
                Log.w(TAG, "Failed to check for updates: ${response.code()} - ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
        return@withContext null
    }
    
    /**
     * Compare two semantic version strings
     * @return true if newVersion is greater than currentVersion
     */
    fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        try {
            val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            
            // Pad shorter version with zeros
            val maxLength = maxOf(newParts.size, currentParts.size)
            val paddedNew = newParts + List(maxLength - newParts.size) { 0 }
            val paddedCurrent = currentParts + List(maxLength - currentParts.size) { 0 }
            
            for (i in 0 until maxLength) {
                when {
                    paddedNew[i] > paddedCurrent[i] -> return true
                    paddedNew[i] < paddedCurrent[i] -> return false
                }
            }
            return false // versions are equal
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions: $newVersion vs $currentVersion", e)
            return false
        }
    }
}
