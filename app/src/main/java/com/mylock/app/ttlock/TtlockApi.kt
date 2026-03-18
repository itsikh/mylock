package com.mylock.app.ttlock

import com.google.gson.annotations.SerializedName

// ── Models ──────────────────────────────────────────────────────────────────

data class TtlockTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Int,  // seconds
    @SerializedName("uid") val uid: Long,
    @SerializedName("errcode") val errcode: Int = 0,
    @SerializedName("errmsg") val errmsg: String = ""
)

data class TtlockLock(
    @SerializedName("lockId") val lockId: Long,
    @SerializedName("lockAlias") val lockAlias: String,
    @SerializedName("lockName") val lockName: String,
    @SerializedName("lockMac") val lockMac: String,
    @SerializedName("electricQuantity") val battery: Int,
    @SerializedName("lockData") val lockData: String,
    @SerializedName("date") val date: Long
)

data class TtlockLockListResponse(
    @SerializedName("list") val list: List<TtlockLock>,
    @SerializedName("pageNo") val pageNo: Int,
    @SerializedName("pageSize") val pageSize: Int,
    @SerializedName("pages") val pages: Int,
    @SerializedName("errcode") val errcode: Int = 0,
    @SerializedName("errmsg") val errmsg: String = ""
)

data class TtlockCommandResponse(
    @SerializedName("errcode") val errcode: Int = 0,
    @SerializedName("errmsg") val errmsg: String = ""
) {
    val isSuccess get() = errcode == 0
}

// ── Result wrapper ───────────────────────────────────────────────────────────

sealed class TtlockResult<out T> {
    data class Success<T>(val data: T) : TtlockResult<T>()
    data class Error(val code: Int, val message: String) : TtlockResult<Nothing>()
}
