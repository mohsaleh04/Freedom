package ir.saltech.freedom.dto.user

import com.google.gson.annotations.SerializedName

/**
 *  TODO: If purchased service was canceled, must discard it immediately.
 */
data class Service(
    @SerializedName("xusrid") val id: String? = null,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("down") val download: Long? = null,
    @SerializedName("expiry_time") val expiryTime: Long? = null,
    @SerializedName("global_link") val globalLink: String? = null,
    @SerializedName("local_link") val localLink: String? = null,
    @SerializedName("traffic", alternate = ["total"]) val totalTraffic: Long,
    @SerializedName("up") val upload: Long? = null,
    @SerializedName("ipcount") val ipCount: Int = 2,
    @SerializedName("period") val period: Int = 1, /// This is based on months, like: 1, 3, 6, 12 -> 1 month, 3 months, 6 months, 12 months
    @SerializedName("price") val price: Long? = null,
    @SerializedName("trackId") val trackId: Long? = null,
    @SerializedName("orderId") val orderId: String? = null,
    @SerializedName("phone", alternate = ["mobile"]) val mobile: String? = null
)
