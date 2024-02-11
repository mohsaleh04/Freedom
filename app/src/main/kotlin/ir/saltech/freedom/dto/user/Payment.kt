package ir.saltech.freedom.dto.user

import com.google.gson.annotations.SerializedName

data class Payment(
    @SerializedName("trackId") val trackId: Long? = null,
    @SerializedName("amount") val amount: Long? = null,
    @SerializedName("orderId") val orderId: String? = null,
    @SerializedName("referer") val referer: String = "https://api.prox.saltech.ir/paymentResult",
    @SerializedName("description") val description: String,
    @SerializedName("mobile") val mobile: String? = null,
    @SerializedName("refundedId") val refundedId: Long? = null,
    @SerializedName("payment_status") val status: Int? = null
)
