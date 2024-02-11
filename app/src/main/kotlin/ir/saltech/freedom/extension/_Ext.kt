package ir.saltech.freedom.extension

import android.content.Context
import android.os.Build
import android.widget.Toast
import ir.saltech.freedom.AngApplication
import me.drakeet.support.toast.ToastCompat
import org.json.JSONObject
import java.net.URI
import java.net.URLConnection
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Some extensions
 */

val Context.v2RayApplication: AngApplication
    get() = applicationContext as AngApplication

fun Context.toast(message: Int): Toast = ToastCompat
    .makeText(this, message, Toast.LENGTH_SHORT)
    .apply {
        show()
    }

fun Context.toast(message: CharSequence): Toast = ToastCompat
    .makeText(this, message, Toast.LENGTH_SHORT)
    .apply {
        show()
    }

fun JSONObject.putOpt(pair: Pair<String, Any>) = putOpt(pair.first, pair.second)
fun JSONObject.putOpt(pairs: Map<String, Any>) = pairs.forEach { putOpt(it.key to it.value) }

const val threshold = 1000
const val divisor = 1024F

fun Long.toSpeedString() = toTrafficString() + "/s"

fun Long.toTrafficString(): String {
    if (this == 0L)
        return "\t\t\t0\t  B"

    if (this < threshold)
        return "${this.toFloat().toShortString()}\t  B"

    val kib = this / divisor
    if (kib < threshold)
        return "${kib.toShortString()}\t KB"

    val mib = kib / divisor
    if (mib < threshold)
        return "${mib.toShortString()}\t MB"

    val gib = mib / divisor
    if (gib < threshold)
        return "${gib.toShortString()}\t GB"

    val tib = gib / divisor
    if (tib < threshold)
        return "${tib.toShortString()}\t TB"

    val pib = tib / divisor
    if (pib < threshold)
        return "${pib.toShortString()}\t PB"

    return "∞"
}

private fun Float.toShortString(): String {
    val s = "%.2f".format(this)
    if (s.length <= 4)
        return s
    return s.substring(0, 4).removeSuffix(".")
}

val URLConnection.responseLength: Long
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) contentLengthLong else contentLength.toLong()

val URI.idnHost: String
    get() = (host!!).replace("[", "").replace("]", "")

fun String.removeWhiteSpace(): String {
    return this.replace(" ", "")
}


fun Long.asTime(): String {
    val minutes = this / 60000
    val seconds = this % 60000 / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun Long.getDays(): Int {
    return (this.toDouble() / 86400000).roundToInt()
}

fun String.asToken(): String {
    return "bearer $this"
}

fun String.mergedId(): String? {
    if (this.contains("-")) {
        var mergedId = ""
        this.split("-").forEach {
            mergedId += it
        }
        return mergedId
    } else {
        return null
    }
}

infix fun Long.percentOf(total: Long): Double {
    return (this.toDouble() / total.toDouble()) * 100
}

fun Long.toGigabyte(): Double {
    return this.toDouble() / 1024 / 1024 / 1024
}
