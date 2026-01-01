package com.zynt.mangaautoscroller.data.api

import com.zynt.mangaautoscroller.data.model.GitHubRelease
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Retrofit interface for GitHub API calls
 */
interface GitHubApiService {
    
    /**
     * Get the latest release for a repository
     */
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubRelease>
    
    /**
     * Get all releases for a repository
     */
    @GET("repos/{owner}/{repo}/releases")
    suspend fun getAllReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<List<GitHubRelease>>
    
    companion object {
        private const val BASE_URL = "https://api.github.com/"
        
        /**
         * Create a GitHubApiService instance
         */
        fun create(): GitHubApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GitHubApiService::class.java)
        }
    }
}
