package com.queuekiosk.data.api

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * Same contract as fleQ's apicall/APIInterface.java.
 *
 * Paths are relative, so a base URL with a path prefix (http://host/fleq/) also works. fleQ used
 * "/api/..." which always hits the host root. For a plain http://host base URL both behave the same.
 */
interface ApiService {

    @FormUrlEncoded
    @POST("api/v1/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
    ): Response<LoginResponse>

    @FormUrlEncoded
    @POST("api/v1/add-queue")
    suspend fun addQueue(
        @Field("workflow_id") workflowId: String,
    ): Response<AddQueueResponse>
}
