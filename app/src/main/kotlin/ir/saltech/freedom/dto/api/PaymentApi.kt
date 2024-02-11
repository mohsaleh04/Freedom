package ir.saltech.freedom.dto.api

import ir.saltech.freedom.dto.user.Payment
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface PaymentApi {
    @POST("d85fe720caa225dcaa1ee2b6d53366bcc05d4439/begin")
    fun beginPayment(@Body payment: Payment): Call<Payment>

    @POST("d85fe720caa225dcaa1ee2b6d53366bcc05d4439/inquiry")
    fun inquiryPayment(@Body payment: Payment): Call<Payment>

    @POST("refund")
    fun refundPayment(@Body payment: Payment): Call<Payment>
}