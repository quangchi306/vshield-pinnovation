package com.trustnet.vshield.data.remote.api

import com.trustnet.vshield.data.remote.model.*
import retrofit2.Response
import retrofit2.http.*

interface VShieldApiService {

    @GET("api/v1/blocklist/delta")
    suspend fun getDelta(
        @Query("since")    since:    Int,
        @Query("category") category: String = "all",
        @Query("limit")    limit:    Int    = 5000,
    ): Response<DeltaResponse>

    @GET("api/v1/blocklist/full")
    suspend fun getFullList(
        @Query("category") category: String = "all",
    ): Response<FullSyncResponse>

    @GET("api/v1/stats")
    suspend fun getStats(): Response<StatsResponse>

    @POST("api/v1/report/")
    suspend fun reportDomain(
        @Body body: ReportRequest,
    ): Response<ReportResponse>
}
