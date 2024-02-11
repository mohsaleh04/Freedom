package ir.saltech.freedom.dto.user

import com.google.gson.annotations.SerializedName

data class Service(
    @SerializedName("xusrid") val id: String? = null,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("down") val download: Long,
    @SerializedName("expiry_time") val expiryTime: Long,
    @SerializedName("global_link") val globalLink: String,
    @SerializedName("local_link") val localLink: String,
    @SerializedName("total", alternate = ["traffic"]) val totalTraffic: Long,
    @SerializedName("up") val upload: Long,
    @SerializedName("ipcount") val ipCount: Int = 2,
    @SerializedName("period") val period: Int = 1, /// This is based on months, like: 1, 3, 6, 12 -> 1 month, 3 months, 6 months, 12 months
    val purchasedTime: Long
)
