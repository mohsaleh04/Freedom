package ir.saltech.freedom.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object SimUtils {
	fun getCurrentSimPhoneNumber(context: Context): String? {
		// Check if the READ_PHONE_STATE permission is granted
		if (ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.READ_PHONE_STATE
			) == PackageManager.PERMISSION_GRANTED
		) {
			// Get the TelephonyManager instance
			val telephonyManager =
				context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

			// Get the phone number
			return telephonyManager.line1Number
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