package ir.saltech.freedom.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.content.ContextCompat

object SimUtils {
	fun getCurrentSimPhoneNumber(context: Context): String? {
		// Check if the READ_PHONE_STATE permission is granted
		if (ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.READ_PHONE_STATE
			) == PackageManager.PERMISSION_GRANTED
		) {
			try {
				// Get the TelephonyManager instance
				val telephonyManager =
					context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

				// Get the phone number
				return telephonyManager.line1Number
			} catch (e: SecurityException) {
				Toast.makeText(context, "این قابلیت پشتیبانی نمی شود!", Toast.LENGTH_SHORT).show()
				return null
			}
		} else {
			// Permission not granted, return null
			return null
		}
	}

	// Add a function to get name of sim provider

	@Deprecated(message = "Use NetworkUtils instead")
	fun getSimProviderName(context: Context): String? {
		// Check if the READ_PHONE_STATE permission is granted
		if (ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.READ_PHONE_STATE
			) == PackageManager.PERMISSION_GRANTED
		) {
			// Get the TelephonyManager instance
			val telephonyManager =
				context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

			// Get the sim provider name
			return telephonyManager.simOperatorName
		} else {
			// Permission not granted, return null
			return null
		}
	}
}