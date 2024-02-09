package ir.saltech.freedom.dto.api

import ir.saltech.freedom.dto.user.Service
import ir.saltech.freedom.dto.user.User
import ir.saltech.freedom.dto.user.VspList
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

private const val AUTHORIZATION_HEADER = "Authorization"

interface FreedomApi {

	/** User section */
	@POST("/user/signUp")
	fun signUp(@Body body: User): Call<ResponseMsg>

	@POST("/user/verifyPhone")
	fun verifyPhone(@Body body: User): Call<ResponseMsg>

	@POST("/user/signIn")
	fun signIn(@Body body: User): Call<User>

	/** Service Section */
	@POST("/service/get")
	fun getService(@Header(AUTHORIZATION_HEADER) authToken: String, @Body body: User): Call<Service>

	@PUT("/service/alloc")
	fun allocateService(@Header(AUTHORIZATION_HEADER) authToken: String, @Body body: User): Call<ResponseMsg>

	@POST("/service/getLinks")
	fun getLinks(@Header(AUTHORIZATION_HEADER) authToken: String, @Body body: User): Call<Service>

	@GET("/service/getVspList")
	fun getVspList(): Call<VspList>
}