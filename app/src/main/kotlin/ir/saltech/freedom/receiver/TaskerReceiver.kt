package ir.saltech.freedom.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import com.tencent.mmkv.MMKV
import ir.saltech.freedom.AppConfig
import ir.saltech.freedom.service.V2RayServiceManager
import ir.saltech.freedom.util.MmkvManager

import ir.saltech.freedom.util.Utils

class TaskerReceiver : BroadcastReceiver() {
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }

    override fun onReceive(context: Context, intent: Intent?) {

        try {
            val bundle = intent?.getBundleExtra(AppConfig.TASKER_EXTRA_BUNDLE)
            val switch = bundle?.getBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, false)
            val guid = bundle?.getString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, "")

            if (switch == null || guid == null || TextUtils.isEmpty(guid)) {
                return
            } else if (switch) {
                if (guid == AppConfig.TASKER_DEFAULT_GUID) {
                    Utils.startVServiceFromToggle(context)
                } else {
                    mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, guid)
                    V2RayServiceManager.startV2Ray(context)
                }
            } else {
                Utils.stopVService(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
