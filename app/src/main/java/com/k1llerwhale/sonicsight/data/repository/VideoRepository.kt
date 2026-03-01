package com.k1llerwhale.sonicsight.data.repository

import com.k1llerwhale.sonicsight.data.api.NetworkModule
import com.k1llerwhale.sonicsight.data.model.PredictionResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class VideoRepository {

    suspend fun uploadVideo(videoFile: File): Result<PredictionResponse> {
        return try {
            // Prepare the file for upload
            val requestFile = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("video", videoFile.name, requestFile)
            print("hamadeh")
            // Make the call
            val response = NetworkModule.api.uploadVideo(body)
            print(response);

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}