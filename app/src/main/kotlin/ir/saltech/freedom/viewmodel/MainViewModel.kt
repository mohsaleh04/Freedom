package ir.saltech.freedom.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.*
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import ir.saltech.freedom.AngApplication
import ir.saltech.freedom.AppConfig
import ir.saltech.freedom.AppConfig.ANG_PACKAGE
import ir.saltech.freedom.R
import ir.saltech.freedom.databinding.DialogConfigFilterBinding
import ir.saltech.freedom.dto.*
import ir.saltech.freedom.dto.Saver.Companion.saver
import ir.saltech.freedom.dto.api.ApiCallback
import ir.saltech.freedom.dto.api.ApiClient
import ir.saltech.freedom.dto.api.ResponseMsg
import ir.saltech.freedom.dto.api.call
import ir.saltech.freedom.dto.otp.OtpCode
import ir.saltech.freedom.dto.otp.OtpSms
import ir.saltech.freedom.dto.user.Payment
import ir.saltech.freedom.dto.user.Service
import ir.saltech.freedom.dto.user.User
import ir.saltech.freedom.dto.user.VspList
import ir.saltech.freedom.extension.asToken
import ir.saltech.freedom.extension.toast
import ir.saltech.freedom.util.*
import ir.saltech.freedom.util.MmkvManager.KEY_ANG_CONFIGS
import kotlinx.coroutines.*
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mainStorage by lazy {
        MMKV.mmkvWithID(
            MmkvManager.ID_MAIN,
            MMKV.MULTI_PROCESS_MODE
        )
    }
    private val serverRawStorage by lazy {
        MMKV.mmkvWithID(
            MmkvManager.ID_SERVER_RAW,
            MMKV.MULTI_PROCESS_MODE
        )
    }
    private val settingsStorage by lazy {
        MMKV.mmkvWithID(
            MmkvManager.ID_SETTING,
            MMKV.MULTI_PROCESS_MODE
        )
    }

    var serverList = MmkvManager.decodeServerList()
    var subscriptionId: String = settingsStorage.decodeString(AppConfig.CACHE_SUBSCRIPTION_ID, "")!!
    var keywordFilter: String = settingsStorage.decodeString(AppConfig.CACHE_KEYWORD_FILTER, "")!!
        private set
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val updateConnectivityAction by lazy { MutableLiveData<Boolean>() }
    @SuppressLint("StaticFieldLeak")
    var context: Context? = null
    private val tcpingTestScope by lazy { CoroutineScope(Dispatchers.IO) }
    val updateOtpAction by lazy { MutableLiveData<String>() }

    fun startListenBroadcast() {
        isRunning.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<AngApplication>().registerReceiver(
                mMsgReceiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Context.RECEIVER_EXPORTED
            )
            getApplication<AngApplication>().registerReceiver(
                mOtpReceiver,
                IntentFilter("android.provider.Telephony.SMS_RECEIVED"),
                Context.RECEIVER_EXPORTED
            )
        } else {
            getApplication<AngApplication>().registerReceiver(
                mMsgReceiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
            )
            getApplication<AngApplication>().registerReceiver(
                mOtpReceiver,
                IntentFilter("android.provider.Telephony.SMS_RECEIVED")
            )
        }
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestUtil.closeAllTcpSockets()
        Log.i(ANG_PACKAGE, "Main ViewModel is cleared")
        super.onCleared()
    }

    fun reloadServerList() {
        serverList = MmkvManager.decodeServerList()
        updateCache()
        updateListAction.value = -1
    }

    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
        }
    }

    fun appendCustomConfigServer(server: String) {
        val config = ServerConfig.create(EConfigType.CUSTOM)
        config.remarks = System.currentTimeMillis().toString()
        config.subscriptionId = subscriptionId
        config.fullConfig = Gson().fromJson(server, V2rayConfig::class.java)
        val key = MmkvManager.encodeServerConfig("", config)
        serverRawStorage?.encode(key, server)
        serverList.add(0, key)
        serversCache.add(0, ServersCache(key, config))
    }

    fun swapServer(fromPosition: Int, toPosition: Int) {
        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(serversCache, fromPosition, toPosition)
        mainStorage?.encode(KEY_ANG_CONFIGS, Gson().toJson(serverList))
    }

    


    @Synchronized
    fun updateCache() {
        serversCache.clear()
        for (guid in serverList) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (subscriptionId.isNotEmpty() && subscriptionId != config.subscriptionId) {
                continue
            }

            if (keywordFilter.isEmpty() || config.remarks.contains(keywordFilter)) {
                serversCache.add(ServersCache(guid, config))
            }
        }
    }

    fun testAllTcping() {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestUtil.closeAllTcpSockets()
        MmkvManager.clearAllTestDelayResults()
        updateListAction.value = -1 // update all

        getApplication<AngApplication>().toast(R.string.connection_test_testing)
        for (item in serversCache) {
            item.config.getProxyOutbound()?.let { outbound ->
                val serverAddress = outbound.getServerAddress()
                val serverPort = outbound.getServerPort()
                if (serverAddress != null && serverPort != null) {
                    tcpingTestScope.launch {
                        val testResult = SpeedtestUtil.tcping(serverAddress, serverPort)
                        launch(Dispatchers.Main) {
                            MmkvManager.encodeServerTestDelayMillis(item.guid, testResult)
                            updateListAction.value = getPosition(item.guid)
                        }
                    }
                }
            }
        }
    }

    fun testAllRealPing() {
        MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG_CANCEL, "")
        MmkvManager.clearAllTestDelayResults()
        updateListAction.value = -1 // update all

        val serversCopy = serversCache.toList() // Create a copy of the list

        getApplication<AngApplication>().toast(R.string.connection_test_testing)
        viewModelScope.launch(Dispatchers.Default) { // without Dispatchers.Default viewModelScope will launch in main thread
            for (item in serversCopy) {
                val config = V2rayConfigUtil.getV2rayConfig(getApplication(), item.guid)
                if (config.status) {
                    MessageUtil.sendMsg2TestService(
                        getApplication(),
                        AppConfig.MSG_MEASURE_CONFIG,
                        Pair(item.guid, config.content)
                    )
                }
            }
        }
    }

    fun testCurrentServerRealPing(user: User?) {
        if (isRunning.value == true) {
            if (user != null) {
                if (user.service != null) {
                    val consumedTraffic = user.service.upload!! + user.service.download!!
                    if (user.service.totalTraffic <= consumedTraffic) {
                        Utils.stopVService(context!!, this)
                        return
                    }
                }
            }
        }
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    fun filterConfig(context: Context) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val listId = subscriptions.map { it.first }.toList().toMutableList()
        val listRemarks = subscriptions.map { it.second.remarks }.toList().toMutableList()
        listRemarks += context.getString(R.string.filter_config_all)
        val checkedItem = if (subscriptionId.isNotEmpty()) {
            listId.indexOf(subscriptionId)
        } else {
            listRemarks.count() - 1
        }

        val ivBinding = DialogConfigFilterBinding.inflate(LayoutInflater.from(context))
        ivBinding.spSubscriptionId.adapter = ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listRemarks
        )
        ivBinding.spSubscriptionId.setSelection(checkedItem)
        ivBinding.etKeyword.text = Utils.getEditable(keywordFilter)
        val builder = AlertDialog.Builder(context).setView(ivBinding.root)
        builder.setTitle(R.string.title_filter_config)
        builder.setPositiveButton(R.string.tasker_setting_confirm) { dialogInterface: DialogInterface?, _: Int ->
            try {
                val position = ivBinding.spSubscriptionId.selectedItemPosition
                subscriptionId = if (listRemarks.count() - 1 == position) {
                    ""
                } else {
                    subscriptions[position].first
                }
                keywordFilter = ivBinding.etKeyword.text.toString()
                settingsStorage?.encode(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
                settingsStorage?.encode(AppConfig.CACHE_KEYWORD_FILTER, keywordFilter)
                reloadServerList()

                dialogInterface?.dismiss()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        builder.show()
//        AlertDialog.Builder(context)
//            .setSingleChoiceItems(listRemarks.toTypedArray(), checkedItem) { dialog, i ->
//                try {
//                    subscriptionId = if (listRemarks.count() - 1 == i) {
//                        ""
//                    } else {
//                        subscriptions[i].first
//                    }
//                    reloadServerList()
//                    dialog.dismiss()
//                } catch (e: Exception) {
//                    e.printStackTrace()
//                }
//            }.show()
    }

    fun getPosition(guid: String): Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    fun removeDuplicateServer() {
        val deleteServer = mutableListOf<String>()
        serversCache.forEachIndexed { index, it ->
            val outbound = it.config.getProxyOutbound()
            serversCache.forEachIndexed { index2, it2 ->
                if (index2 > index) {
                    val outbound2 = it2.config.getProxyOutbound()
                    if (outbound == outbound2 && !deleteServer.contains(it2.guid)) {
                        deleteServer.add(it2.guid)
                    }
                }
            }
        }
        for (it in deleteServer) {
            MmkvManager.removeServer(it)
        }
        reloadServerList()
        getApplication<AngApplication>().toast(
            getApplication<AngApplication>().getString(
                R.string.title_del_duplicate_config_count,
                deleteServer.count()
            )
        )
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    //getApplication<AngApplication>().toast(R.string.toast_services_success)
                    Log.i("START_SERVICE", getApplication<AngApplication>().getString(R.string.toast_services_success))
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    getApplication<AngApplication>().toast(R.string.toast_services_failure)
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val resultPair = intent.getSerializableExtra("content") as Pair<String, Long>
                    MmkvManager.encodeServerTestDelayMillis(resultPair.first, resultPair.second)
                    updateListAction.value = getPosition(resultPair.first)
                }
            }
        }
    }
    
    /** PROX DATA **/
    fun saveUser(user: User?) {
        context!!.saver.setUser(user)
    }

    fun loadUser(): User? {
        return context!!.saver.getUser()
    }


    fun sendSignUpRequest(user: User, callback: ApiCallback<ResponseMsg>) {
        ApiClient.freedom.signUp(user.copy(job = ir.saltech.freedom.dto.user.Job.SignUp)).call(
            callback
        )
    }

    fun sendVerifyPhoneRequest(user: User, callback: ApiCallback<ResponseMsg>) {
        ApiClient.freedom.verifyPhone(user.copy(job = ir.saltech.freedom.dto.user.Job.Verify)).call(
            callback
        )
    }

    fun sendSignInRequest(user: User, callback: ApiCallback<User>) {
        ApiClient.freedom.signIn(user.copy(job = ir.saltech.freedom.dto.user.Job.SignIn)).call(
            callback
        )
    }

    fun sendGetServiceRequest(user: User, callback: ApiCallback<Service>) {
        if (user.accessToken != null)
            ApiClient.freedom.getService(user.accessToken.asToken(), user).call(callback)
    }

    fun sendAllocateServiceRequest(user: User, callback: ApiCallback<ResponseMsg>) {
        if (user.accessToken != null)
            ApiClient.freedom.allocateService(user.accessToken.asToken(), user).call(callback)
    }

    fun sendGetLinksRequest(user: User, callback: ApiCallback<Service>) {
        if (user.accessToken != null)
            ApiClient.freedom.getLinks(user.accessToken.asToken(), user).call(callback)
    }

    fun sendGetVSPListRequest(callback: ApiCallback<VspList>, service: Service? = null) {
        if (service != null)
            ApiClient.freedom.getVspList(service).call(callback)
        else
            ApiClient.freedom.getVspList().call(callback)
    }

    fun sendCalculatePriceRequest(user: User, callback: ApiCallback<Service>) {
        if (user.accessToken != null)
            ApiClient.freedom.calculatePrice(user.accessToken.asToken(), user.service!!).call(
                callback
            )
    }

    fun sendPurchaseServiceRequest(user: User, callback: ApiCallback<ResponseMsg>) {
        if (user.accessToken != null)
            ApiClient.freedom.purchaseService(user.accessToken.asToken(), user.service!!).call(
                callback
            )
    }

    fun sendGetNotificationRequest(callback: ApiCallback<ResponseMsg>) {
        ApiClient.freedom.getNotification().call(callback)
    }

    fun sendBeginPaymentRequest(payment: Payment, callback: ApiCallback<Payment>) {
        ApiClient.payment.beginPayment(payment).call(callback)
    }

    fun sendInquiryPaymentRequest(payment: Payment, callback: ApiCallback<Payment>) {
        ApiClient.payment.inquiryPayment(payment).call(callback)
    }

    fun sendRefundPaymentRequest(payment: Payment, callback: ApiCallback<Payment>) {
        ApiClient.payment.refundPayment(payment).call(callback)
    }

    private val mOtpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val code = getOtpFromSms(getNewOtpSms(intent.extras!!)!!) ?: return
                if (code.otp.isNotEmpty()) {
                    updateOtpAction.value = code.otp
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Exception smsReceiver: $e")
            }
        }
    }

    private fun getOtpFromSms(newOtpSms: OtpSms): OtpCode? {
        newOtpSms.body.lines()[newOtpSms.body.lines().size - 1].let {
            try {
                val base64Decode = Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
                if (base64Decode.isEmpty()) return null
                if (!newOtpSms.body.contains(base64Decode)) return null
                return OtpCode(base64Decode, newOtpSms.sentTime)
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
    }

    private fun getNewOtpSms(bundle: Bundle?): OtpSms? {
        if (bundle != null) {
            if (bundle.containsKey("pdus")) {
                val pdus = bundle.get("pdus")
                if (pdus != null) {
                    val pdusObj = pdus as Array<*>
                    var message = ""
                    var date = 0L
                    for (i in pdusObj.indices) {
                        val currentMessage =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                SmsMessage.createFromPdu(pdusObj[i] as ByteArray?, "3gpp")
                            } else {
                                SmsMessage.createFromPdu(pdusObj[i] as ByteArray?)
                            }
                        if (currentMessage.displayMessageBody.startsWith("<#>")) {
                            message += currentMessage.displayMessageBody
                            date = currentMessage.timestampMillis
                        }
                    } // end for loop
                    return OtpSms(message, date)
                } else {
                    return null
                }
            } else {
                return null
            }
        } else {
            return null
        }
    }
}
