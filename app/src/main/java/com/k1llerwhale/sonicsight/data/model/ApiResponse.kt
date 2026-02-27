package com.k1llerwhale.sonicsight.data.model

import com.google.gson.annotations.SerializedName

data class PredictionResponse(
    @SerializedName("status")
    val status: String,

    @SerializedName("heatmap_base64")
    val heatmapBase64: String,

    // UPDATE: We now expect two separate audio sources
    @SerializedName("audio_base64_1")
    val audioBase64Num1: String,

    @SerializedName("audio_base64_2")
    val audioBase64Num2: String
)