package com.zynt.mangaautoscroller.data.model

import com.google.gson.annotations.SerializedName

/**
 * Data class representing a GitHub Release from the API
 */
data class GitHubRelease(
    @SerializedName("tag_name")
    val tagName: String,
    
    @SerializedName("name")
    val name: String?,
    
    @SerializedName("body")
    val body: String?,
    
    @SerializedName("html_url")
    val htmlUrl: String,
    
    @SerializedName("published_at")
    val publishedAt: String?,
    
    @SerializedName("prerelease")
    val prerelease: Boolean = false,
    
    @SerializedName("draft")
    val draft: Boolean = false,
    
    @SerializedName("assets")
    val assets: List<ReleaseAsset> = emptyList()
) {
    /**
     * Gets the version number from tag (removes 'v' prefix if present)
     */
    fun getVersionNumber(): String {
        return tagName.removePrefix("v").removePrefix("V")
    }
    
    /**
     * Gets the APK download URL from assets
     */
    fun getApkDownloadUrl(): String? {
        return assets.firstOrNull { it.name.endsWith(".apk") }?.browserDownloadUrl
    }
}

/**
 * Data class representing a release asset (APK file, etc.)
 */
data class ReleaseAsset(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("browser_download_url")
    val browserDownloadUrl: String,
    
    @SerializedName("size")
    val size: Long,
    
    @SerializedName("download_count")
    val downloadCount: Int = 0
)
