package ir.saltech.freedom.dto

import com.google.gson.annotations.SerializedName

data class Notification(
    val id: Int,
    val message: String,
    @SerializedName("sent_time") val time: Long
)
