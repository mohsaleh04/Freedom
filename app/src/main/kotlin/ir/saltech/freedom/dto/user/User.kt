package ir.saltech.freedom.dto.user

import com.google.gson.annotations.SerializedName

data class User(
	@SerializedName("user_id") val id: String? = null,
	@SerializedName("flag") val job: String? = null,
	@SerializedName("access_token") val accessToken: String? = null,
	@SerializedName("username") val userName: String? = null,
	@SerializedName("phone") val phoneNumber: String,
	val otp: String? = null,
	val service: Service? = null
)
