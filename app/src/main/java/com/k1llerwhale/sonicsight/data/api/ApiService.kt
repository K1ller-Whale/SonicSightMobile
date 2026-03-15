package com.k1llerwhale.sonicsight.data.api

import com.k1llerwhale.sonicsight.data.model.PredictionResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

@Deprecated("Replaced by gRPC implementation in GrpcModule")
interface ApiService {
    @Multipart
    @POST("predict")
    suspend fun uploadVideo(
        @Part video: MultipartBody.Part
    ): Response<PredictionResponse>
}