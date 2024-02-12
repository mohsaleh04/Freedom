package ir.saltech.freedom.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener
import com.google.android.material.navigation.NavigationView
import com.tbruyelle.rxpermissions.RxPermissions
import com.tencent.mmkv.MMKV
import ir.saltech.freedom.AppConfig
import ir.saltech.freedom.AppConfig.ANG_PACKAGE
import ir.saltech.freedom.AppConfig.PREF_SPEED_ENABLED
import ir.saltech.freedom.BuildConfig
import ir.saltech.freedom.R
import ir.saltech.freedom.databinding.ActivityMainBinding
import ir.saltech.freedom.databinding.ItemQrcodeBinding
import ir.saltech.freedom.dto.EConfigType
import ir.saltech.freedom.dto.api.ApiCallback
import ir.saltech.freedom.dto.api.PAYMENT_URL
import ir.saltech.freedom.dto.api.ResponseMsg
import ir.saltech.freedom.dto.user.Payment
import ir.saltech.freedom.dto.user.Service
import ir.saltech.freedom.dto.user.User
import ir.saltech.freedom.dto.user.VspList
import ir.saltech.freedom.extension.asPrice
import ir.saltech.freedom.extension.asTime
import ir.saltech.freedom.extension.getDays
import ir.saltech.freedom.extension.percentOf
import ir.saltech.freedom.extension.toBytes
import ir.saltech.freedom.extension.toGigabytes
import ir.saltech.freedom.extension.toast
import ir.saltech.freedom.helper.SimpleItemTouchHelperCallback
import ir.saltech.freedom.service.V2RayServiceManager
import ir.saltech.freedom.util.AngConfigManager
import ir.saltech.freedom.util.MmkvManager
import ir.saltech.freedom.util.NetworkMonitor
import ir.saltech.freedom.util.SimUtils
import ir.saltech.freedom.util.SpeedtestUtil
import ir.saltech.freedom.util.Utils
import ir.saltech.freedom.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.drakeet.support.toast.ToastCompat
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.round
import kotlin.random.Random


private const val OTP_EXPIRATION_TIME: Long = 120000

var isDisconnectingServer = false

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private var isServiceRegistrationWanted: Boolean = false
    private var usingLocalLink: Boolean = false
    private var userInitialized: Boolean = false
    private var connectLevel: Int = 0
    private lateinit var binding: ActivityMainBinding
    private lateinit var payment: Payment
    private var user: User? = null
    private lateinit var vspList: VspList
    private var startupCheckLink: Boolean = true
    private val activity = this
    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mainStorage by lazy {
        MMKV.mmkvWithID(
            MmkvManager.ID_MAIN,
            MMKV.MULTI_PROCESS_MODE
        )
    }
    private val settingsStorage by lazy {
        MMKV.mmkvWithID(
            MmkvManager.ID_SETTING,
            MMKV.MULTI_PROCESS_MODE
        )
    }
    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                startV2Ray()
            }
        }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()
    private val networkCallback = object : NetworkCallback() {

        override fun onAvailable(network: Network) {
            mainViewModel.updateConnectivityAction.postValue(true)
            settingsStorage?.encode(PREF_SPEED_ENABLED, true)
        }

        override fun onLost(network: Network) {
            if (!isDisconnectingServer) {
                mainViewModel.updateConnectivityAction.postValue(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)


        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        "v${BuildConfig.VERSION_NAME} (${SpeedtestUtil.getLibVersion()})".also {
            binding.version.text = it
        }
        mainViewModel.updateConnectivityAction.value = NetworkMonitor(this).isNetworkAvailable()
        settingsStorage?.encode(PREF_SPEED_ENABLED, true)
        getPermissions()
        setupViewModel()
        onClicks()
        copyAssets()
        migrateLegacy()
    }

    private fun getUserDefaults() {
        if (!userInitialized) {
            try {
                MmkvManager.removeAllServer()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val loadedUser = mainViewModel.loadUser()
            if (loadedUser == null) {
                setLoginLayout()
            } else {
                user = loadedUser
                setHomeLayout()
            }
            userInitialized = true
        }
    }

    private fun getPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val request = RxPermissions(this)
            request.request(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.RECEIVE_SMS
            )
                .subscribe {
                    if (!it)
                        toast(R.string.toast_permission_denied)
                }
            request.setLogging(true)
        } else {
            val request = RxPermissions(this)
            request.request(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECEIVE_SMS
            )
                .subscribe {
                    if (!it)
                        toast(R.string.toast_permission_denied)
                }
            request.setLogging(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val appLinkIntent: Intent = intent
        val appLinkAction: String? = appLinkIntent.action
        val appLinkData: Uri? = appLinkIntent.data
        if (Intent.ACTION_VIEW == appLinkAction) {
            appLinkData?.lastPathSegment?.also { recipeId ->
                val uriPath = "content://ir.saltech.prox/app/"
                Uri
                    .parse(uriPath)
                    .buildUpon()
                    .appendPath(recipeId)
                    .build()
                    .also { appData ->
                        val payload = appData.toString().substring(uriPath.length)
                        when (payload) {
                            "payment" -> {
                                checkPaymentResult()
                            }
                        }
                    }
            }
        }
    }

    private fun checkPaymentResult() {
        binding.refreshPaymentStatus.visibility = GONE
        binding.checkingServices.visibility = VISIBLE
        binding.checkingServicesText.text = "در حال بررسی وضعیت پرداخت..."
        mainViewModel.sendInquiryPaymentRequest(
            payment = payment,
            object : ApiCallback<Payment> {
                override fun onSuccessful(responseObject: Payment) {
                    if (responseObject.status == 1) { // Paid and Verified successfully
                        if (user!!.service != null) {
                            binding.checkingServicesText.text = "در حال ثبت سرویس ..."
                            user =
                                user!!.copy(service = user!!.service!!.copy(mobile = user!!.phoneNumber))
                            mainViewModel.sendPurchaseServiceRequest(
                                user!!,
                                object : ApiCallback<ResponseMsg> {
                                    override fun onSuccessful(responseObject: ResponseMsg) {
                                        binding.checkingServices.visibility = GONE
                                        binding.homeLayout.visibility = VISIBLE
                                        user =
                                            user!!.copy(
                                                service = user!!.service!!.copy(trackId = payment.trackId)
                                            )
                                        mainViewModel.saveUser(user!!)
                                        toast("سرویس با موفقیت ثبت شد.")
                                        getUserService(true)
                                    }

                                    @SuppressLint("SetTextI18n")
                                    override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                                        Log.i(
                                            "TAG",
                                            "Service registration error: ${response?.message}"
                                        )
                                        if (response != null) {
                                            user = user!!.copy(service = null)
                                            mainViewModel.saveUser(user!!)
                                            saveUsersPayment(null)
                                            binding.checkingServicesText.text =
                                                "در حال ثبت درخواست عودت وجه ..."
                                            mainViewModel.sendRefundPaymentRequest(
                                                payment,
                                                object : ApiCallback<Payment> {
                                                    override fun onSuccessful(responseObject: Payment) {
                                                        binding.checkingServices.visibility = GONE
                                                        binding.errorOccurred.visibility = VISIBLE
                                                        binding.errorOccurredText.text =
                                                            "حین ثبت سرویس خطایی رخ داد.\nوجه شما تا 72 ساعت آتی عودت داده می شود."
                                                    }

                                                    override fun onFailure(
                                                        response: ResponseMsg?,
                                                        t: Throwable?
                                                    ) {
                                                        binding.checkingServices.visibility = GONE
                                                        binding.errorOccurred.visibility = VISIBLE
                                                        binding.errorOccurredText.text =
                                                            "حین ثبت سرویس خطایی رخ داد.\nجهت عودت وجه، با پشتیبانی تماس بگیرید."
                                                    }

                                                })
                                        } else {
                                            mainViewModel.sendGetServiceRequest(user!!, object : ApiCallback<Service> {
                                                override fun onSuccessful(responseObject: Service) {
                                                    binding.checkingServices.visibility = GONE
                                                    binding.homeLayout.visibility = VISIBLE
                                                    toast("سرویس با موفقیت ثبت و فعالسازی شد.")
                                                    user = user!!.copy(payment = null, service = user!!.service!!.copy(trackId = payment.trackId))
                                                    mainViewModel.saveUser(user!!)
                                                    saveUsersPayment(null) // FIXME: May be this command incorrect
                                                    AlertDialog.Builder(this@MainActivity)
                                                        .setIcon(R.drawable.ic_warning)
                                                        .setTitle("لطفاً صبر کنید!!!")
                                                        .setMessage("ممکن است تا فعالسازی کامل حداکثر 5 دقیقه زمان نیاز باشد!\nلطفاً شکیبا باشید.")
                                                        .setPositiveButton("متوجه شدم"){ dialog, _ ->
                                                            dialog.dismiss()
                                                        }
                                                        .setCancelable(false)
                                                        .show()
                                                    doConfigService(responseObject)
                                                }

                                                override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                                                    binding.checkingServicesText.text =
                                                        "در حال ثبت درخواست عودت وجه ..."
                                                    mainViewModel.sendRefundPaymentRequest(
                                                        payment,
                                                        object : ApiCallback<Payment> {
                                                            override fun onSuccessful(responseObject: Payment) {
                                                                binding.checkingServices.visibility = GONE
                                                                binding.errorOccurred.visibility = VISIBLE
                                                                binding.errorOccurredText.text =
                                                                    "حین ثبت سرویس خطایی رخ داد.\nوجه شما تا 72 ساعت آتی عودت داده می شود."
                                                            }

                                                            override fun onFailure(
                                                                response: ResponseMsg?,
                                                                t: Throwable?
                                                            ) {
                                                                binding.checkingServices.visibility = GONE
                                                                binding.errorOccurred.visibility = VISIBLE
                                                                binding.errorOccurredText.text =
                                                                    "حین ثبت سرویس خطایی رخ داد.\nجهت عودت وجه، با پشتیبانی تماس بگیرید."
                                                            }

                                                        })
                                                }
                                            })
                                        }
                                    }

                                })
                        } else {
                            binding.checkingServicesText.text =
                                "در حال ثبت درخواست عودت وجه ..."
                            user = user!!.copy(service = null)
                            mainViewModel.saveUser(user!!)
                            saveUsersPayment(null)
                            mainViewModel.sendRefundPaymentRequest(
                                payment,
                                object : ApiCallback<Payment> {
                                    override fun onSuccessful(responseObject: Payment) {
                                        binding.checkingServices.visibility = GONE
                                        binding.errorOccurred.visibility = VISIBLE
                                        binding.errorOccurredText.text =
                                            "حین ثبت سرویس خطایی رخ داد.\nوجه شما تا 72 ساعت آتی عودت داده می شود."
                                    }

                                    override fun onFailure(
                                        response: ResponseMsg?,
                                        t: Throwable?
                                    ) {
                                        binding.checkingServices.visibility = GONE
                                        binding.errorOccurred.visibility = VISIBLE
                                        binding.errorOccurredText.text =
                                            "حین ثبت سرویس خطایی رخ داد.\nجهت عودت وجه، با پشتیبانی تماس بگیرید."
                                    }

                                })
                        }
                    } else {
                        binding.checkingServices.visibility = GONE
                        binding.errorOccurred.visibility = VISIBLE
                        binding.errorOccurredText.text = "پرداخت ناموفق بود."
                        user = user!!.copy(service = null)
                        mainViewModel.saveUser(user!!)
                        saveUsersPayment(null)
                    }
                }

                @SuppressLint("SetTextI18n")
                override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                    binding.checkingServices.visibility = GONE
                    binding.errorOccurred.visibility = VISIBLE
                    binding.errorOccurredText.text =
                        "خطای بررسی وضعیت پرداخت: ${response?.message}"
                }

            })
    }

    private fun saveUsersPayment(p: Payment?) {
        user = user!!.copy(payment = p)
        val nUser = mainViewModel.loadUser()
        mainViewModel.saveUser(nUser!!.copy(payment = p))
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.context = this
        mainViewModel.updateConnectivityAction.observe(this) {
            if (it) {
                binding.noInternetConnection.visibility = GONE
                getUserDefaults()
                if (user != null) {
                    if (user!!.service != null) {
                        if (userInitialized && binding.checkingServices.visibility == GONE && !isServiceRegistrationWanted) {
                            setConnectionLayout()
                        }
                    }
                }
            } else {
                binding.noInternetConnection.visibility = VISIBLE
            }
        }
        mainViewModel.updateOtpAction.observe(this) {
            checkOtpDoLogin(it)
        }
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) {
            if (it.toLongOrNull() != null) {
                connectLevel = 1
                binding.fab.setImageResource(R.drawable.service_connected)
                binding.fab.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_orange))
                binding.connectServiceStatus.setTextColor(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_orange))
                )
                binding.remainingOfServiceLayout.visibility = GONE
                binding.connectServiceStatus.text = "متصل به سرور"
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
                binding.tryingToConnectService.visibility = GONE
                binding.networkPingLayout.visibility = VISIBLE
                binding.networkPingImg.setImageDrawable(getPingStatus(it.toLong()))
                binding.showLinksBtn.visibility = VISIBLE
                binding.connectionTypeLayout.visibility = VISIBLE
                if (usingLocalLink)
                    binding.connectionTypeSchema.setImageResource(R.drawable.using_tunnelling)
                else
                    binding.connectionTypeSchema.setImageResource(R.drawable.using_direct)
                binding.networkPingText.text = getString(R.string.connection_ping_text, it)
                Observable.timer(3000, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe {
                        if (mainViewModel.isRunning.value == true) {
                            mainViewModel.testCurrentServerRealPing()
                        }
                    }
            } else {
                Log.i("SERVER_CONNECTION", "Connection failed: $it")
                if (it != "اتصال به اینترنت شناسایی نشد:  context canceled") {
                    Utils.stopVService(this, mainViewModel)
                    if (it != "اتصال به اینترنت شناسایی نشد:  io: read/write on closed pipe") {
                        if (startupCheckLink) {
                            // If the current link (global link) is corrupt, uses the tunnel link
                            if (user?.service!!.localLink != null) {
                                MmkvManager.removeAllServer()
                                setupLink(user?.service!!.localLink!!)
                                startConnection()
                                usingLocalLink = true
                                Log.i("TAG", "Now using tunnel!")
                            }
                            startupCheckLink = false
                        } else {
                            AlertDialog.Builder(this)
                                .setIcon(R.drawable.failed_to_connect)
                                .setTitle("عدم توانایی اتصال به سرویس")
                                .setMessage("\nبه علت خطای زیر\n$it:در اتصال به سرور ناتوان بود!\nبرای اطلاعات بیشتر با پشتیبانی در تماس باشید.")
                                .setPositiveButton("متوجه شدم") { dialog, _ ->
                                    dialog.dismiss()
                                }
                                .show()
                        }
                    }
                }
            }
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.tryingToConnectService.visibility = VISIBLE
                binding.connectServiceStatus.text = "در حال اتصال ..."
                connectLevel = 0
                showTitleConnectingEffect()
                mainViewModel.testCurrentServerRealPing()
                //binding.checkConnectionPing.isEnabled = true
            } else {
                if (binding.checkingServices.visibility == GONE && user != null) {
                    setConnectionLayout()
                    sendGetUserRequestAgain()
                }
                binding.fab.setImageResource(R.drawable.service_not_connected)
                binding.fab.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_grey))
                binding.connectServiceStatus.setTextColor(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_grey))
                )
                binding.tryingToConnectService.visibility = GONE
                binding.connectServiceStatus.text = "اتصال به سرور"
                binding.networkPingLayout.visibility = GONE
                binding.networkPingText.text = "   ..."
                binding.networkPingImg.setImageResource(R.drawable.gathering_network_ping)
                binding.connectionTypeLayout.visibility = GONE
                binding.shareLinkLayout.visibility = GONE
                binding.showLinksBtn.visibility = GONE
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
                connectLevel = -1
                //binding.checkConnectionPing.isEnabled = false
            }
            hideCircle()
        }
        mainViewModel.startListenBroadcast()
    }

    private fun sendGetUserRequestAgain() {
        mainViewModel.sendGetServiceRequest(user!!, object : ApiCallback<Service> {
            override fun onSuccessful(responseObject: Service) {
                user = user!!.copy(
                    service = user!!.service!!.copy(
                        upload = responseObject.upload,
                        download = responseObject.download
                    )
                )
                mainViewModel.saveUser(user!!)
                setConnectionLayout()
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
            }

        })
    }

    private fun showTitleConnectingEffect() {
        when (connectLevel) {
            0 -> {
                Observable.timer(500, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe {
                        binding.connectServiceStatus.setTextColor(
                            ColorStateList.valueOf(
                                ContextCompat.getColor(
                                    this,
                                    R.color.color_secondary
                                )
                            )
                        )
                        when (connectLevel) {
                            0 -> {
                                Observable.timer(500, TimeUnit.MILLISECONDS)
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe {
                                        binding.connectServiceStatus.setTextColor(
                                            ColorStateList.valueOf(
                                                ContextCompat.getColor(this, R.color.color_fab_grey)
                                            )
                                        )
                                        when (connectLevel) {
                                            0 -> {
                                                showTitleConnectingEffect()
                                            }

                                            1 -> {
                                                binding.connectServiceStatus.setTextColor(
                                                    ColorStateList.valueOf(
                                                        ContextCompat.getColor(
                                                            this,
                                                            R.color.color_fab_orange
                                                        )
                                                    )
                                                )
                                            }

                                            else -> {
                                                binding.connectServiceStatus.setTextColor(
                                                    ColorStateList.valueOf(
                                                        ContextCompat.getColor(
                                                            this,
                                                            R.color.color_fab_grey
                                                        )
                                                    )
                                                )
                                            }
                                        }
                                    }
                            }

                            1 -> {
                                binding.connectServiceStatus.setTextColor(
                                    ColorStateList.valueOf(
                                        ContextCompat.getColor(this, R.color.color_fab_orange)
                                    )
                                )
                            }

                            else -> {
                                binding.connectServiceStatus.setTextColor(
                                    ColorStateList.valueOf(
                                        ContextCompat.getColor(this, R.color.color_fab_grey)
                                    )
                                )
                            }
                        }
                    }
            }

            1 -> {
                binding.connectServiceStatus.setTextColor(
                    ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.color_fab_orange)
                    )
                )
            }

            else -> {
                binding.connectServiceStatus.setTextColor(
                    ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.color_fab_grey)
                    )
                )
            }
        }
    }

    private fun getPingStatus(ping: Long): Drawable {
        return when (ping) {
            in 1..100 -> ContextCompat.getDrawable(this, R.drawable.network_ping_excellent)!!
            in 101..600 -> ContextCompat.getDrawable(this, R.drawable.network_ping_good)!!
            in 601..1000 -> ContextCompat.getDrawable(this, R.drawable.network_ping_normal)!!
            in 1001..2500 -> ContextCompat.getDrawable(this, R.drawable.network_ping_bad)!!
            else -> ContextCompat.getDrawable(this, R.drawable.network_ping_very_bad)!!
        }
    }

    private fun copyAssets() {
        val extFolder = Utils.userAssetPath(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geo = arrayOf("geosite.dat", "geoip.dat")
                assets.list("")
                    ?.filter { geo.contains(it) }
                    ?.filter { !File(extFolder, it).exists() }
                    ?.forEach {
                        val target = File(extFolder, it)
                        assets.open(it).use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(
                            ANG_PACKAGE,
                            "Copied from apk assets folder to ${target.absolutePath}"
                        )
                    }
            } catch (e: Exception) {
                Log.e(ANG_PACKAGE, "asset copy failed", e)
            }
        }
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.migrateLegacyConfig(this@MainActivity)
            if (result != null) {
                launch(Dispatchers.Main) {
                    if (result) {
                        toast(getString(R.string.migration_success))
                        mainViewModel.reloadServerList()
                    } else {
                        toast(getString(R.string.migration_fail))
                    }
                }
            }
        }
    }

    private fun startV2Ray() {
        if (mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).isNullOrEmpty()) {
            return
        }
        showCircle()
//        toast(R.string.toast_services_start)
        V2RayServiceManager.startV2Ray(this)
        hideCircle()
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            Utils.stopVService(this, mainViewModel)
        }
        Observable.timer(500, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                startV2Ray()
            }
    }

    public override fun onResume() {
        super.onResume()
        NetworkMonitor(this).registerNetworkCallback(networkCallback)
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    public override fun onDestroy() {
        super.onDestroy()
        NetworkMonitor(this).unregisterNetworkCallback(networkCallback)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode(true)
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_config_custom_clipboard -> {
            importConfigCustomClipboard()
            true
        }

        R.id.import_config_custom_local -> {
            importConfigCustomLocal()
            true
        }

        R.id.import_config_custom_url -> {
            importConfigCustomUrlClipboard()
            true
        }

        R.id.import_config_custom_url_scan -> {
            importQRcode(false)
            true
        }

//        R.id.sub_setting -> {
//            startActivity<SubSettingActivity>()
//            true
//        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.export_all -> {
            if (AngConfigManager.shareNonCustomConfigsToClipboard(
                    this,
                    mainViewModel.serverList
                ) == 0
            ) {
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            true
        }

        R.id.ping_all -> {
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeAllServer()
                    mainViewModel.reloadServerList()
                }
                .show()
            true
        }

        R.id.del_duplicate_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    mainViewModel.removeDuplicateServer()
                }
                .show()
            true
        }

        R.id.del_invalid_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeInvalidServer()
                    mainViewModel.reloadServerList()
                }
                .show()
            true
        }

        R.id.sort_by_test_results -> {
            MmkvManager.sortByTestResults()
            mainViewModel.reloadServerList()
            true
        }

        R.id.filter_config -> {
            mainViewModel.filterConfig(this)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(forConfig: Boolean): Boolean {
//        try {
//            startActivityForResult(Intent("com.google.zxing.client.android.SCAN")
//                    .addCategory(Intent.CATEGORY_DEFAULT)
//                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), requestCode)
//        } catch (e: Exception) {
        RxPermissions(this)
            .request(Manifest.permission.CAMERA)
            .subscribe {
                if (it)
                    if (forConfig)
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
                    else
                        scanQRCodeForUrlToCustomConfig.launch(
                            Intent(
                                this,
                                ScannerActivity::class.java
                            )
                        )
                else
                    toast(R.string.toast_permission_denied)
            }
//        }
        return true
    }

    private val scanQRCodeForConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
            }
        }

    private val scanQRCodeForUrlToCustomConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                importConfigCustomUrl(it.data?.getStringExtra("SCAN_RESULT"))
            }
        }

    /**
     * import config from clipboard
     */
    private fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?, subid: String = "") {
        val subid2 = subid.ifEmpty {
            mainViewModel.subscriptionId
        }
        val append = subid.isEmpty()

        var count = AngConfigManager.importBatchConfig(server, subid2, append)
        if (count <= 0) {
            count = AngConfigManager.importBatchConfig(Utils.decode(server!!), subid2, append)
        }
        if (count <= 0) {
            count = AngConfigManager.appendCustomConfigServer(server, subid2)
        }
        if (count > 0) {
            //toast(R.string.toast_success)
            Log.i("IMPORT_CONFIG", getString(R.string.toast_success))
            mainViewModel.reloadServerList()
        } else {
            Log.e("IMPORT_CONFIG", getString(R.string.toast_failure))
            //toast(R.string.toast_failure)
        }
    }

    private fun importConfigCustomClipboard(): Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun importConfigCustomUrlClipboard(): Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from url
     */
    private fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    Utils.getUrlContentWithCustomUserAgent(url)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                launch(Dispatchers.Main) {
                    importCustomizeConfig(configText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        try {
            toast(R.string.title_sub_update)
            MmkvManager.decodeSubscriptions().forEach {
                if (TextUtils.isEmpty(it.first)
                    || TextUtils.isEmpty(it.second.remarks)
                    || TextUtils.isEmpty(it.second.url)
                ) {
                    return@forEach
                }
                if (!it.second.enabled) {
                    return@forEach
                }
                val url = Utils.idnToASCII(it.second.url)
                if (!Utils.isValidUrl(url)) {
                    return@forEach
                }
                Log.d(ANG_PACKAGE, url)
                lifecycleScope.launch(Dispatchers.IO) {
                    val configText = try {
                        Utils.getUrlContentWithCustomUserAgent(url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        launch(Dispatchers.Main) {
                            toast("\"" + it.second.remarks + "\" " + getString(R.string.toast_failure))
                        }
                        return@launch
                    }
                    launch(Dispatchers.Main) {
                        importBatchConfig(configText, it.first)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            chooseFileForCustomConfig.launch(
                Intent.createChooser(
                    intent,
                    getString(R.string.title_file_chooser)
                )
            )
        } catch (ex: ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    private val chooseFileForCustomConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val uri = it.data?.data
            if (it.resultCode == RESULT_OK && uri != null) {
                readContentFromUri(uri)
            }
        }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        RxPermissions(this)
            .request(permission)
            .subscribe {
                if (it) {
                    try {
                        contentResolver.openInputStream(uri).use { input ->
                            importCustomizeConfig(input?.bufferedReader()?.readText())
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else
                    toast(R.string.toast_permission_denied)
            }
    }

    /**
     * import customize config
     */
    private fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                toast(R.string.toast_none_data)
                return
            }
            mainViewModel.appendCustomConfigServer(server)
            mainViewModel.reloadServerList()
            toast(R.string.toast_success)
            //adapter.notifyItemInserted(mainViewModel.serverList.lastIndex)
        } catch (e: Exception) {
            ToastCompat.makeText(
                this,
                "${getString(R.string.toast_malformed_josn)} ${e.cause?.message}",
                Toast.LENGTH_LONG
            ).show()
            e.printStackTrace()
            return
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    fun showCircle() {
        binding.fabProgressCircle.show()
    }

    fun hideCircle() {
        try {
            Observable.timer(300, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    try {
                        if (binding.fabProgressCircle.isShown) {
                            binding.fabProgressCircle.hide()
                        }
                    } catch (e: Exception) {
                        Log.w(ANG_PACKAGE, e)
                    }
                }
        } catch (e: Exception) {
            Log.d(ANG_PACKAGE, e.toString())
        }
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            //super.onBackPressed()
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            //R.id.server_profile -> activityClass = MainActivity::class.java
            R.id.sub_setting -> {
                startActivity(Intent(this, SubSettingActivity::class.java))
            }

            R.id.settings -> {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .putExtra("isRunning", mainViewModel.isRunning.value == true)
                )
            }

            R.id.user_asset_setting -> {
                startActivity(Intent(this, UserAssetActivity::class.java))
            }

            R.id.feedback -> {
                Utils.openUri(this, AppConfig.v2rayNGIssues)
            }

            R.id.promotion -> {
                Utils.openUri(
                    this,
                    "${Utils.decode(AppConfig.promotionUrl)}?t=${System.currentTimeMillis()}"
                )
            }

            R.id.logcat -> {
                startActivity(Intent(this, LogcatActivity::class.java))
            }

            R.id.privacy_policy -> {
                Utils.openUri(this, AppConfig.v2rayNGPrivacyPolicy)
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    /** My Codes */
    private fun setHomeLayout() {
        binding.signupLoginLayout.visibility = GONE
        binding.homeLayout.visibility = VISIBLE
        binding.connectService.visibility = GONE
        if (user!!.payment == null)
            getUserService()
        else {
            payment = user!!.payment!!
            checkPaymentResult()
        }
    }

    private fun getUserService(activate: Boolean = false) {
        if (user != null) {
            binding.checkingServices.visibility = VISIBLE
            binding.errorOccurred.visibility = GONE
            if (activate) {
                binding.checkingServicesText.text = "در حال فعالسازی سرویس ..."
            } else {
                binding.checkingServicesText.text = "در حال بررسی سرویس ..."
            }
            mainViewModel.sendGetServiceRequest(user!!, object : ApiCallback<Service> {
                override fun onSuccessful(responseObject: Service) {
                    if (activate) {
                        toast("سرویس با موفقیت فعالسازی شد.")
                        user = user!!.copy(payment = null)
                        mainViewModel.saveUser(user!!)
                        AlertDialog.Builder(this@MainActivity)
                            .setIcon(R.drawable.ic_warning)
                            .setTitle("لطفاً صبر کنید!!!")
                            .setMessage("ممکن است تا فعالسازی کامل حداکثر 5 دقیقه زمان نیاز باشد!\nلطفاً شکیبا باشید.")
                            .setPositiveButton("متوجه شدم") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    doConfigService(responseObject)
                }

                override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                    if (activate) {
                        toast("خطا در فعالسازی سرویس: ${response?.message}")
                        binding.checkingServicesText.text =
                            "در حال ثبت درخواست عودت وجه ..."
                        mainViewModel.sendRefundPaymentRequest(
                            payment,
                            object : ApiCallback<Payment> {
                                override fun onSuccessful(responseObject: Payment) {
                                    binding.checkingServices.visibility = GONE
                                    binding.errorOccurred.visibility = VISIBLE
                                    binding.errorOccurredText.text =
                                        "حین فعالسازی سرویس خطایی رخ داد.\nوجه شما تا 72 ساعت آتی عودت داده می شود."
                                    saveUsersPayment(null)
                                }

                                override fun onFailure(
                                    response: ResponseMsg?,
                                    t: Throwable?
                                ) {
                                    binding.checkingServices.visibility = GONE
                                    binding.errorOccurred.visibility = VISIBLE
                                    binding.errorOccurredText.text =
                                        "حین فعالسازی سرویس خطایی رخ داد.\nجهت عودت وجه، با پشتیبانی تماس بگیرید."
                                    saveUsersPayment(null)
                                }
                            })
                        user = user!!.copy(service = null, payment = null)
                        mainViewModel.saveUser(user!!)
                    } else {
                        Utils.stopVService(this@MainActivity, mainViewModel)
                        if (response?.message == "xuser not found") {
                            binding.checkingServices.visibility = VISIBLE
                            binding.checkingServicesText.text = "در حال تخصیص سرویس ..."
                            mainViewModel.sendAllocateServiceRequest(
                                user!!,
                                object : ApiCallback<ResponseMsg> {
                                    override fun onSuccessful(responseObject: ResponseMsg) {
                                        binding.checkingServicesText.text =
                                            "در حال دستیابی سرویس ..."
                                        mainViewModel.sendGetServiceRequest(
                                            user!!,
                                            object : ApiCallback<Service> {
                                                override fun onSuccessful(responseObject: Service) {
                                                    doConfigService(responseObject)
                                                }

                                                override fun onFailure(
                                                    response: ResponseMsg?,
                                                    t: Throwable?
                                                ) {
                                                    binding.checkingServices.visibility = GONE
                                                    if (response?.message == "xuser not found" || response?.message == "service not found") {
                                                        doPurchaseService()
                                                    } else {
                                                        binding.errorOccurred.visibility = VISIBLE
                                                        Toast.makeText(
                                                            activity,
                                                            "خطا حین دستیابی به سرویس: ${response?.message}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }

                                            })
                                    }

                                    override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                                        binding.checkingServices.visibility = GONE
                                        if (response?.message == "user not found in service db") {
                                            doPurchaseService()
                                        } else {
                                            binding.errorOccurred.visibility = VISIBLE
                                            Toast.makeText(
                                                activity,
                                                "خطا حین تخصیص سرویس: ${response?.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }

                                })
                        }
                        else if (response?.message?.contains("service not found") == true) {
                            binding.checkingServices.visibility = GONE
                            user = user!!.copy(service = null)
                            mainViewModel.saveUser(user!!)
                            toast("سرویس شما به پایان رسیده است یا یافت نشد!")
                            doPurchaseService()
                        } else {
                            if (response != null) {
                                if (response.message.contains("token handle error") || response.message == "user not found") {
                                    Toast.makeText(
                                        activity,
                                        "به دلیل مسائل امنیتی، ملزم به ورود مجدد به برنامه هستید.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    user = null
                                    mainViewModel.saveUser(null)
                                    finishAndRemoveTask()
                                }
                                binding.checkingServices.visibility = GONE
                                Toast.makeText(
                                    activity,
                                    "خطا حین دستیابی به سرویس: ${response.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                toast("... بررسی مجدد ...")
                                getUserService()
                            }
                        }
                    }
                }
            })
        }
    }

    fun doConfigService(service: Service) {
        user = user!!.copy(service = service)
        mainViewModel.saveUser(user!!)
        // Get V2RAY Links
        mainViewModel.sendGetLinksRequest(user!!, object : ApiCallback<Service> {
            override fun onSuccessful(responseObject: Service) {
                binding.checkingServices.visibility = GONE
                val aLink = responseObject.globalLink
                if (aLink != null) {
                    if (aLink.startsWith("vless://")) {
                        val id = aLink.substring(8..43)
                        setupLink(aLink)
                        val provider = aLink.substringAfterLast("#").split("-")[0]
                        user = user!!.copy(
                            service = user!!.service!!.copy(
                                id = id,
                                provider = provider,
                                globalLink = responseObject.globalLink,
                                localLink = responseObject.localLink
                            )
                        )
                        mainViewModel.saveUser(user!!)
                        setConnectionLayout()
                    } else {
                        Toast.makeText(
                            activity,
                            "پروتکل ${aLink.substringBefore("://", "unknown")} پشتیبانی نمی شود!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                binding.checkingServices.visibility = GONE
                Toast.makeText(
                    activity,
                    "خطا حین دریافت اطلاعات سرویس: ${response?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

        })
    }

    private fun setupLink(aLink: String) {
        importBatchConfig(aLink)
        mainStorage?.encode(
            MmkvManager.KEY_SELECTED_SERVER,
            mainViewModel.serversCache[0].guid
        )
    }

    private fun setConnectionLayout() {
        binding.connectService.visibility = VISIBLE
        if (user!!.service?.totalTraffic != null) {
            val consumedTraffic = user!!.service!!.upload!! + user!!.service!!.download!!
            val remainingTraffic = user!!.service!!.totalTraffic - consumedTraffic
            val percentOfRemaining = consumedTraffic percentOf user!!.service!!.totalTraffic
            val currentTimeMillis = System.currentTimeMillis()
            val remainingDays = user!!.service!!.expiryTime!! - currentTimeMillis
            binding.remainingTrafficText.text =
                getString(
                    R.string.remaining_traffic_text,
                    remainingTraffic.toGigabytes(),
                    user!!.service!!.totalTraffic.toGigabytes()
                )
            binding.remainingTrafficBar.progress = percentOfRemaining.toInt()
            if (remainingDays.getDays() < 0) {
                binding.remainingDaysText.text = "سرویس شما، دارای محدودیت زمانی نمی باشد."
            } else {
                binding.remainingDaysText.text =
                    getString(R.string.remaining_days_text, remainingDays.getDays())
            }
            if (mainViewModel.isRunning.value == false)
                binding.remainingOfServiceLayout.visibility = VISIBLE
            else
                binding.remainingOfServiceLayout.visibility = GONE
        }
    }

    fun doPurchaseService() {
        isServiceRegistrationWanted = true
        binding.wantedTrafficText.text =
            getString(R.string.wanted_traffic_text, 10)
        vspList = VspList()
        vspList.vspList.add("انتخاب کنید")
        mainViewModel.sendGetVSPListRequest(object : ApiCallback<VspList> {
            override fun onSuccessful(responseObject: VspList) {
                binding.errorOccurred.visibility = GONE
                vspList.vspList.addAll(1, responseObject.vspList)
                binding.vspSelection.adapter =
                    ArrayAdapter(activity, android.R.layout.simple_spinner_item, vspList.vspList)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    binding.wantedTrafficBar.min = 1
                } else {
                    binding.wantedTrafficBar.progress = 1
                }
                var selectedVsp: String? = null
                var wantedTraffic = 10
                binding.wantedTrafficBar.setOnSeekBarChangeListener(object :
                    SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (progress == 0) {
                            binding.wantedTrafficBar.progress = 1
                        } else {
                            wantedTraffic = progress * 10
                            binding.wantedTrafficText.text =
                                getString(R.string.wanted_traffic_text, wantedTraffic)
                            if (selectedVsp != null)
                                calculatePrice(selectedVsp!!, wantedTraffic)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    }

                })
                binding.vspSelection.onItemSelectedListener = object : OnItemSelectedListener,
                    AdapterView.OnItemSelectedListener {
                    override fun onNavigationItemSelected(p0: MenuItem): Boolean {
                        return true
                    }

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        selectedVsp = vspList.vspList[position]
                        calculatePrice(selectedVsp!!, wantedTraffic)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {
                        selectedVsp = null
                    }
                }
                binding.submitRequest1.setOnClickListener {
                    if (selectedVsp != null) {
                        if (selectedVsp != "انتخاب کنید") {
                            AlertDialog.Builder(this@MainActivity)
                                .setIcon(R.drawable.ic_action_done)
                                .setTitle("تأیید درخواست")
                                .setMessage("آیا از درخواست خود اطمینان دارید؟" + "\n-- اطلاعات سرویس --\nحجم ترافیک: ${user!!.service!!.totalTraffic.toGigabytes()}GB\nفراهم کننده خدمات: ${user!!.service!!.provider}\nتعداد کاربر: ${user!!.service!!.ipCount} کاربر\nمدت دوره: ${user!!.service!!.period} ماهه" + "\nپس از پرداخت هزینه، سرویس تا حداکثر ۵ دقیقه آینده برای شما فعالسازی می شود.")
                                .setPositiveButton("بله") { dialog, _ ->
                                    doPaymentForPurchasedService()
                                    dialog.dismiss()
                                }
                                .setNegativeButton("خیر") { dialog, _ ->
                                    //user = user!!.copy(service = null)
                                    dialog.dismiss()
                                }
                                .setCancelable(false)
                                .show()
                        } else {
                            toast("یک فراهم کننده خدمات برگزینید.")
                        }
                    } else {
                        toast("یک فراهم کننده خدمات برگزینید.")
                    }
                }
                binding.purchaseServiceLayout.visibility = VISIBLE
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                binding.errorOccurred.visibility = VISIBLE
                Log.e("TAG", "Failed to fetch virtual service providers list: ${response?.message}")
            }

        })
    }

    private fun doPaymentForPurchasedService() {
        if (user != null) {
            if (user!!.service != null) {
                binding.purchaseServiceLayout.visibility = GONE
                binding.checkingServices.visibility = VISIBLE
                binding.checkingServicesText.text = "در حال شروع فرایند پرداخت..."
                payment = Payment(
                    amount = user!!.service!!.price,
                    mobile = user!!.phoneNumber,
                    orderId = generateOrderId(),
                    description = "خرید سرویس از فراهم کننده خدمات ${user!!.service!!.provider}"
                )
                mainViewModel.sendBeginPaymentRequest(payment, object : ApiCallback<Payment> {
                    override fun onSuccessful(responseObject: Payment) {
                        if (responseObject.trackId != null) {
                            binding.checkingServices.visibility = VISIBLE
                            binding.checkingServicesText.text = "به درگاه پرداخت منتقل می شوید..."
                            payment = payment.copy(trackId = responseObject.trackId)
                            mainViewModel.saveUser(user!!)
                            binding.refreshPaymentStatus.visibility = VISIBLE
                            saveUsersPayment(payment)
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("${PAYMENT_URL}d85fe720caa225dcaa1ee2b6d53366bcc05d4439/payment?trackId=${payment.trackId}")
                                )
                            )
                            // TODO: Now must check through inquiry when a payment intent given
                        } else {
                            user = user!!.copy(service = null)
                            binding.checkingServices.visibility = GONE
                            binding.errorOccurred.visibility = VISIBLE
                            binding.errorOccurredText.text =
                                "فراهم کننده خدمات پرداخت، تراکنش را لغو کرد."
                        }
                    }

                    @SuppressLint("SetTextI18n")
                    override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                        user = user!!.copy(service = null)
                        binding.checkingServices.visibility = GONE
                        binding.errorOccurred.visibility = VISIBLE
                        binding.errorOccurredText.text = "خطا حین پرداخت: ${response?.message}"
                    }

                })
            }
        }
    }

    private fun generateOrderId(): String {
        return "PRX-${Random.nextInt(100000, 1000000)}"
    }

    private fun calculatePrice(
        selectedVsp: String,
        wantedTraffic: Int,
        ipCount: Int = 2,
        period: Int = 1
    ) {
        binding.calculatedPriceLayout.visibility = GONE
        binding.checkCalculatedPrice.visibility = VISIBLE
        // TODO: Now, We now using just 2 ip counts..
        var service =
            Service(
                provider = selectedVsp,
                totalTraffic = wantedTraffic.toLong().toBytes(),
                ipCount = 1,
                period = period
            )
        mainViewModel.sendCalculatePriceRequest(
            user!!.copy(service = service),
            object : ApiCallback<Service> {
                override fun onSuccessful(responseObject: Service) {
                    binding.checkCalculatedPrice.visibility = GONE
                    binding.calculatedPriceLayout.visibility = VISIBLE
                    var wage = round(responseObject.price!! * 0.01).toLong()
                    if (wage > 60000) {
                        wage = 60000
                    } else if (wage < 5000) {
                        wage = 5000
                    }
                    val realPrice = responseObject.price + wage
                    binding.calculatedPriceText.text =
                        getString(R.string.calculated_costumer_price_text, realPrice.asPrice())
                    service = service.copy(price = responseObject.price, ipCount = ipCount)
                    user = user!!.copy(service = service)
                }

                override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                    binding.checkCalculatedPrice.visibility = GONE
                    toast("خطا حین تخمین قیمت: ${response?.message}")
                }

            })
    }

    private fun checkOtpDoLogin(it: String?) {
        if (user != null) {
            binding.verifyCode.editText?.setText(it)
            binding.loadingBar.visibility = VISIBLE
            mainViewModel.sendSignInRequest(user!!.copy(otp = it), object : ApiCallback<User> {
                override fun onSuccessful(responseObject: User) {
                    binding.loadingBar.visibility = GONE
                    user = user!!.copy(
                        id = responseObject.id,
                        accessToken = responseObject.accessToken
                    )
                    mainViewModel.saveUser(
                        user!!.copy(
                            id = responseObject.id,
                            accessToken = responseObject.accessToken
                        )
                    )
                    setHomeLayout()
                    Toast.makeText(activity, "خوش آمدید!", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                    binding.loadingBar.visibility = GONE
                    if (response != null) {
                        Toast.makeText(
                            activity,
                            "خطای رمز یکبار مصرف: ${response.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            })
        }
    }

    private fun onClicks() {
        binding.connectionTypeLayout.setOnClickListener {
            if (user?.service!!.localLink != null) {
                AlertDialog.Builder(this)
                    .setTitle("تغییر نوع اتصال")
                    .setMessage(if (usingLocalLink) "آیا میخواهید اتصال خود را از واسط به مستقیم تغییر دهید؟" else "آیا میخواهید اتصال خود را از مستقیم به واسط تغییر دهید؟")
                    .setPositiveButton("بله") { dialog, _ ->
                        if (!usingLocalLink) {
                            MmkvManager.removeAllServer()
                            setupLink(user?.service!!.localLink!!)
                            startConnection()
                            usingLocalLink = true
                            Log.i("TAG", "TTLink: ${user?.service!!.localLink!!}")
                            Log.i("TAG", "TTLink: ${user?.service!!.globalLink!!}")
                            Log.i("CONNECTION_TYPE", "Now using tunnel!")
                        } else {
                            MmkvManager.removeAllServer()
                            setupLink(user?.service!!.globalLink!!)
                            startConnection()
                            usingLocalLink = false
                            Log.i("CONNECTION_TYPE", "Now using direct!")
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("خیر") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
        binding.refreshPaymentStatus.setOnClickListener {
            binding.refreshPaymentStatus.visibility = GONE
            checkPaymentResult()
        }
        binding.resendVerifyCode.setOnClickListener {
            sendVerifyRequest()
        }
        binding.fab.setOnClickListener {
            startConnection()
        }
        binding.checkConnectionPing.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                binding.checkConnectionPing.text = "در حال بررسی ..."
                binding.checkConnectionPing.isEnabled = false
                mainViewModel.testCurrentServerRealPing()
            }
        }
        binding.connectionTypeLayout.setOnLongClickListener {
            if (usingLocalLink)
                Toast.makeText(this, "اتصال شما به صورت واسط است.", Toast.LENGTH_SHORT).show()
            else
                Toast.makeText(this, "اتصال شما به صورت مستقیم است.", Toast.LENGTH_SHORT).show()
            true
        }
        binding.showLinksBtn.setOnClickListener {
            binding.shareLinkText.text =
                if (usingLocalLink) user!!.service?.localLink else user!!.service?.globalLink
            binding.shareLinkLayout.visibility = VISIBLE
            Observable.timer(5000, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    binding.shareLinkLayout.visibility = GONE
                }
        }
        binding.shareLinkLayout.setOnClickListener {
            val shareOptions = resources.getStringArray(R.array.share_method).asList()
            AlertDialog.Builder(this).setItems(shareOptions.toTypedArray()) { _, i ->
                try {
                    when (i) {
                        0 -> {
                            val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(this))
                            ivBinding.ivQcode.setImageBitmap(
                                AngConfigManager.share2QRCode(
                                    mainViewModel.serversCache[0].guid
                                )
                            )
                            AlertDialog.Builder(this).setView(ivBinding.root).show()
                        }

                        1 -> {
                            if (AngConfigManager.share2Clipboard(
                                    this,
                                    mainViewModel.serversCache[0].guid
                                ) == 0
                            ) {
                                toast("لینک در کلیپ بورد، کپی شد.")
                            } else {
                                toast(R.string.toast_failure)
                            }
                        }

                        2 -> Toast.makeText(this, "این قابلیت پشتیبانی نمی شود", Toast.LENGTH_SHORT)
                            .show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.show()
        }
    }

    private fun startConnection() {
        if (mainViewModel.isRunning.value == true) {
            Utils.stopVService(this, mainViewModel)
        } else if ((settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN") == "VPN") {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun setLoginLayout() {
        binding.signupLoginLayout.visibility = VISIBLE
        binding.homeLayout.visibility = GONE
        binding.signupUsername.visibility = GONE
        binding.signupLoginBtn.isEnabled = false
        binding.signupLoginBtn.text = "ورود به برنامه"
        binding.signupLoginBtn.icon = AppCompatResources.getDrawable(this, R.drawable.login)!!
        binding.signupPhone.setEndIconOnClickListener {
            binding.signupPhone.editText!!.setText(SimUtils.getCurrentSimPhoneNumber(this))
        }
        binding.signupPhone.setEndIconOnLongClickListener {
            Toast.makeText(this, "دریافت شماره سیم کارت اصلی روی گوشی", Toast.LENGTH_SHORT).show()
            true
        }
        binding.signupPhone.editText!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null) {
                    if (s.isNotEmpty()) {
                        if (s.length == 11) {
                            if (s.startsWith("09")) {
                                if (s.isDigitsOnly()) {
                                    binding.signupPhone.error = null
                                    binding.signupLoginBtn.isEnabled = true
                                    return
                                } else {
                                    binding.signupPhone.error = "شماره تلفن همراه نامعتبر است!"
                                }
                            } else {
                                binding.signupPhone.error = "شماره تلفن همراه نامعتبر است!"
                            }
                        } else {
                            binding.signupPhone.error = "شماره تلفن همراه، ۱۱ رقمی است!"
                        }
                    } else {
                        binding.signupPhone.error = "شماره تلفن همراه نباید خالی باشد!"
                    }
                }
                binding.signupLoginBtn.isEnabled = false
            }

            override fun afterTextChanged(s: Editable?) {
            }

        })
        binding.signupLoginBtn.setOnClickListener {
            binding.signupPhone.error = null
            binding.signupPhone.isEnabled = false
            binding.signupLoginBtn.isEnabled = false
            binding.customerPhoneHelp.visibility = GONE
            sendVerifyRequest()
        }
    }

    private fun setSignUpLayout() {
        binding.signupUsername.visibility = VISIBLE
        binding.signupLoginBtn.isEnabled = false
        binding.signupLoginBtn.text = "ثبت نام در برنامه"
        binding.signupLoginBtn.icon = AppCompatResources.getDrawable(this, R.drawable.signup)!!
        binding.signupUsername.editText!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null) {
                    if (s.isNotEmpty()) {
                        binding.signupUsername.error = null
                        if (binding.signupPhone.error == null && !binding.signupPhone.editText!!.text.isNullOrBlank()) {
                            binding.signupLoginBtn.isEnabled = true
                            return
                        }
                    } else {
                        binding.signupUsername.error = "نام و نام خانوادگی نمی تواند خالی باشد!"
                    }
                }
                binding.signupLoginBtn.isEnabled = false
            }

            override fun afterTextChanged(s: Editable?) {
            }

        })
        binding.signupPhone.editText!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null) {
                    if (s.isNotEmpty()) {
                        if (s.length == 11) {
                            if (s.startsWith("09")) {
                                if (s.isDigitsOnly()) {
                                    binding.signupPhone.error = null
                                    if (binding.signupUsername.error == null && !binding.signupUsername.editText!!.text.isNullOrBlank()) {
                                        binding.signupLoginBtn.isEnabled = true
                                        return
                                    }
                                } else {
                                    binding.signupPhone.error = "شماره تلفن همراه نامعتبر است!"
                                }
                            } else {
                                binding.signupPhone.error = "شماره تلفن همراه نامعتبر است!"
                            }
                        } else {
                            binding.signupPhone.error = "شماره تلفن همراه، ۱۱ رقمی است!"
                        }
                    } else {
                        binding.signupPhone.error = "شماره تلفن همراه نباید خالی باشد!"
                    }
                }
                binding.signupLoginBtn.isEnabled = false
            }

            override fun afterTextChanged(s: Editable?) {
            }

        })
        binding.signupLoginBtn.setOnClickListener {
            binding.signupUsername.error = null
            binding.signupPhone.error = null
            binding.signupUsername.isEnabled = false
            binding.signupPhone.isEnabled = false
            binding.signupLoginBtn.isEnabled = false
            sendSignUpRequest()
        }
    }

    private fun sendSignUpRequest() {
        val username = binding.signupUsername.editText!!.text.toString()
        val phone = binding.signupPhone.editText!!.text.toString()
        user = User(userName = username, phoneNumber = phone)
        binding.loadingBar.visibility = VISIBLE
        //val user2 = mainViewModel.loadUser()!!
        //Toast.makeText(activity, "Saved User => name: ${user2.userName} || phoneNumber: ${user2.phoneNumber}", Toast.LENGTH_SHORT).show()
        mainViewModel.sendSignUpRequest(user!!, object : ApiCallback<ResponseMsg> {
            override fun onSuccessful(responseObject: ResponseMsg) {
                binding.signupLoginBtn.isEnabled = true
                binding.signupUsername.isEnabled = true
                binding.signupPhone.isEnabled = true
                sendVerifyRequest()
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                binding.signupUsername.isEnabled = true
                binding.signupLoginBtn.isEnabled = true
                binding.signupPhone.isEnabled = true
                binding.loadingBar.visibility = GONE
                if (response != null) {
                    if (response.message == "user already exist") {
                        sendVerifyRequest()
                    } else {
                        Toast.makeText(activity, response.message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    if (t != null)
                        Toast.makeText(activity, t.message, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun sendVerifyRequest() {
        val phone = binding.signupPhone.editText!!.text.toString()
        binding.loadingBar.visibility = VISIBLE
        user = User(phoneNumber = phone)
        mainViewModel.sendVerifyPhoneRequest(user!!, object : ApiCallback<ResponseMsg> {
            override fun onSuccessful(responseObject: ResponseMsg) {
                binding.signupLayout.visibility = GONE
                binding.verifyLayout.visibility = VISIBLE
                binding.customerPhoneHelp.visibility = GONE
                binding.loadingBar.visibility = GONE
                binding.resendVerifyCode.isEnabled = false
                binding.verifyCode.isEnabled = false
                binding.resendVerifyCode.text = "ارسال مجدد"
                binding.resendVerifyCode.icon =
                    AppCompatResources.getDrawable(activity, R.drawable.resend_otp)!!
                object : CountDownTimer(OTP_EXPIRATION_TIME, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        binding.resendVerifyCode.text =
                            "ارسال مجدد   (${millisUntilFinished.asTime()})"
                    }

                    override fun onFinish() {
                        binding.resendVerifyCode.text = "ارسال مجدد"
                        binding.resendVerifyCode.isEnabled = true
                    }
                }.start()
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                binding.signupLoginBtn.isEnabled = true
                binding.customerPhoneHelp.visibility = VISIBLE
                binding.loadingBar.visibility = GONE
                if (response != null) {
                    if (response.message == "user not found") {
                        setSignUpLayout()
                    } else {
                        binding.signupPhone.isEnabled = true
                        Toast.makeText(activity, response.message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    if (t != null)
                        Toast.makeText(activity, t.message, Toast.LENGTH_SHORT).show()
                }
            }

        })
    }
}
