package com.k1llerwhale.sonicsight.data.model

import com.google.gson.annotations.SerializedName

data class PredictionResponse(
    // The backend snippet you showed didn't include "status",
    // so we make it nullable just in case it's missing.
    @SerializedName("status")
    val status: String? = null,

    // MATCH THE PYTHON KEY EXACTLY
    @SerializedName("heatmap_image")
    val heatmapBase64: String,

    // MATCH THE PYTHON KEY EXACTLY
    @SerializedName("left_audio")
    val audioBase64Num1: String,

    // MATCH THE PYTHON KEY EXACTLY
    @SerializedName("right_audio")
    val audioBase64Num2: String
)