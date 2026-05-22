package com.example.haptok.network

import com.example.haptok.models.HapticTimeline
import com.example.haptok.models.JobStatus
import com.example.haptok.models.UploadResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Retrofit interface for the Haptok backend API.
 *
 * Base URL is configured in [NetworkModule] and defaults to
 * `http://10.0.2.2:8000` (Android emulator → host loopback).
 */
interface HaptokApiService {

    /**
     * Upload a video file along with the device profile for haptic
     * timeline generation.
     */
    @Multipart
    @POST("/api/v1/videos/upload")
    suspend fun uploadVideo(
        @Part video: MultipartBody.Part,
        @Part("device_profile") deviceProfile: RequestBody,
    ): Response<UploadResponse>

    /**
     * Poll the status of a haptic-generation job.
     */
    @GET("/api/v1/jobs/{jobId}/status")
    suspend fun getJobStatus(
        @Path("jobId") jobId: String,
    ): Response<JobStatus>

    /**
     * Download the completed haptic timeline for a job.
     */
    @GET("/api/v1/jobs/{jobId}/haptics")
    suspend fun getHapticTrack(
        @Path("jobId") jobId: String,
    ): Response<HapticTimeline>
}
