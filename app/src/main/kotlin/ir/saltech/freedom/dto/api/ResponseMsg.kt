package ir.saltech.freedom.dto.api

import com.google.gson.annotations.SerializedName
import ir.saltech.freedom.dto.Notification

data class ResponseMsg(
	@SerializedName("push_notification") val pushNotification: Notification? = null,
	val status: String,
	val message: String,
	val timestamp: Long
)
