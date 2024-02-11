package ir.saltech.freedom.dto
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import ir.saltech.freedom.dto.user.User

class Saver @SuppressLint("CommitPrefEdits") private constructor(private val context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("application_preferences", Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sp.edit()

    fun setUser(user: User?) {
        val gson = Gson()
        val json = gson.toJson(user)
        editor.putString(USER_DATA, json)
        editor.apply()
    }

    fun getUser(): User? {
        val gson = Gson()
        val json = sp.getString(USER_DATA, "")
        return gson.fromJson(json, User::class.java)
    }

    companion object {
        private const val USER_DATA = "user_data"

        private var instance: Saver? = null
        val Context.saver: Saver
            get() {
                if (instance == null) {
                    return Saver(this)
                }
                return instance!!
            }
    }
}
