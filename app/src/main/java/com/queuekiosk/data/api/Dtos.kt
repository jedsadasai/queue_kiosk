package com.queuekiosk.data.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// Mirrors fleQ's pojo/LoginPojo.java and pojo/AddQueuePojo.java. Everything is nullable
// because Gson ignores Kotlin nullability, so we validate explicitly in the repositories.

data class LoginResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("data") val data: LoginData?,
)

data class LoginData(
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("token") val token: String?,
    @SerializedName("expire") val expire: Long?,
    @SerializedName("name") val name: String?,
    @SerializedName("app_name") val appName: String?,
    @SerializedName("branch") val branch: String?,
    @SerializedName("building") val building: String?,
    @SerializedName("department") val department: String?,
    @SerializedName("workflow") val workflow: Workflow?,
)

data class Workflow(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("letter") val letter: String?,
    @SerializedName("branch_name") val branchName: String?,
    @SerializedName("start") val start: Int?,
)

data class AddQueueResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("data") val data: AddQueueData?,
)

data class AddQueueData(
    @SerializedName("number") val number: String?,
    @SerializedName("total") val total: String?,
    @SerializedName("building") val building: String?,
    @SerializedName("doctor") val doctor: String?,
    @SerializedName("url_qr") val urlQr: String?,
    @SerializedName("vn") val vn: JsonElement?,
    // Typo is in the server contract (fleQ AddQueuePojo: "worklfow_name"). Keep it.
    @SerializedName("worklfow_name") val workflowName: String?,
)
