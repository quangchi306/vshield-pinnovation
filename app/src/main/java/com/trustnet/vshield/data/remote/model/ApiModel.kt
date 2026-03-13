package com.trustnet.vshield.data.remote.model

import com.google.gson.annotations.SerializedName

data class RemoteDomain(
    @SerializedName("domain")    val domain:   String,
    @SerializedName("category")  val category: String,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("version")   val version:  Int,
)

//Blocklist — đã bỏ whitelisted

data class DeltaResponse(
    @SerializedName("current_version") val currentVersion: Int,
    @SerializedName("added")           val added:          List<RemoteDomain>,
    @SerializedName("removed")         val removed:        List<String>,
    @SerializedName("total_added")     val totalAdded:     Int,
    @SerializedName("total_removed")   val totalRemoved:   Int,
)

data class FullSyncResponse(
    @SerializedName("current_version") val currentVersion: Int,
    @SerializedName("domains")         val domains:        List<RemoteDomain>,
    @SerializedName("total")           val total:          Int,
    @SerializedName("categories")      val categories:     Map<String, Int>,
    @SerializedName("has_more")        val hasMore:        Boolean = false,
    @SerializedName("page")            val page:           Int     = 1,
    @SerializedName("page_size")       val pageSize:       Int     = 50000,
)

//Whitelist

data class WhitelistFullResponse(
    @SerializedName("whitelist_version") val whitelistVersion: Int,
    @SerializedName("domains")           val domains:          List<String>,
    @SerializedName("total")             val total:            Int,
)

data class WhitelistDeltaResponse(
    @SerializedName("whitelist_version") val whitelistVersion: Int,
    @SerializedName("added")             val added:            List<String>,
    @SerializedName("total_added")       val totalAdded:       Int,
)

//Stats / Report

data class StatsResponse(
    @SerializedName("total_domains")   val totalDomains:   Int,
    @SerializedName("by_category")     val byCategory:     Map<String, Int>,
    @SerializedName("current_version") val currentVersion: Int,
    @SerializedName("last_crawl")      val lastCrawl:      String?,
)

data class ReportRequest(
    @SerializedName("domain")   val domain:   String,
    @SerializedName("category") val category: String,
)

data class ReportResponse(
    @SerializedName("message")   val message:  String,
    @SerializedName("domain")    val domain:   String,
    @SerializedName("report_id") val reportId: Int,
)