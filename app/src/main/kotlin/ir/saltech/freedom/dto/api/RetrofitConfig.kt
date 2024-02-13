package ir.saltech.freedom.dto.api

import android.util.Log
import com.google.gson.Gson
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


const val PAYMENT_URL = "https://pay.saltech.ir/api/"
const val FREEDOM_URL = "https://api.prox.saltech.ir/"

object RetrofitClient {
	val freedom: Retrofit by lazy {
		Retrofit.Builder()
			.baseUrl(FREEDOM_URL)
			.addConverterFactory(GsonConverterFactory.create())
			.build()
	}
	val payment: Retrofit by lazy {
		Retrofit.Builder()
			.baseUrl(PAYMENT_URL)
			.addConverterFactory(GsonConverterFactory.create())
			.build()
	}
}

object ApiClient {
	val freedom: FreedomApi by lazy {
		RetrofitClient.freedom.create(FreedomApi::class.java)
	}
	val payment: PaymentApi by lazy {
		RetrofitClient.payment.create(PaymentApi::class.java)
	}
}

interface ApiCallback<T> {
	fun onSuccessful(responseObject: T)
	fun onFailure(response: ResponseMsg? = null, t: Throwable? = null)
}

inline fun <reified T> Call<T>.call(callback: ApiCallback<T>) {
	this.enqueue(
		object : Callback<T> {
			override fun onResponse(call: Call<T>, response: Response<T>) {
				if (response.isSuccessful) {
					callback.onSuccessful(response.body()!!)
				} else {
					val errorJson = response.errorBody()
					if (errorJson != null) {
						val errorMsg = Gson().fromJson(errorJson.string(), ResponseMsg::class.java)
						callback.onFailure(response = errorMsg)
						Log.e("TAG", "ERROR OCCURRED: ${errorJson.string()} || ${response.code()} || ${response.message()}")
					}
				}
			}

			override fun onFailure(call: Call<T>, t: Throwable) {
				t.printStackTrace()
				callback.onFailure(t = t)
			}
		}
	)
}
