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
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.google.android.material.navigation.NavigationView
import com.tbruyelle.rxpermissions.RxPermissions
import com.tencent.mmkv.MMKV
import ir.saltech.freedom.AppConfig
import ir.saltech.freedom.AppConfig.ANG_PACKAGE
import ir.saltech.freedom.AppConfig.PREF_SPEED_ENABLED
import ir.saltech.freedom.BuildConfig
import ir.saltech.freedom.R
import ir.saltech.freedom.databinding.ActivityMainBinding
import ir.saltech.freedom.dto.EConfigType
import ir.saltech.freedom.dto.api.ApiCallback
import ir.saltech.freedom.dto.api.ResponseMsg
import ir.saltech.freedom.dto.user.Service
import ir.saltech.freedom.dto.user.User
import ir.saltech.freedom.dto.user.VspList
import ir.saltech.freedom.extension.asTime
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val OTP_EXPIRATION_TIME: Long = 120000

var isDisconnectingServer = false

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private var userInitialized: Boolean = false
    private var connectLevel: Int = 0
    private lateinit var binding: ActivityMainBinding
    private lateinit var user: User
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
        val appLinkAction = intent.action
        val appLinkData: Uri? = intent.data
        if (Intent.ACTION_VIEW == appLinkAction) {
            appLinkData?.lastPathSegment?.also { recipeId ->
                Uri
                    .parse("content://ir.saltech.freedom/app/")
                    .buildUpon()
                    .appendPath(recipeId)
                    .build()
                    .also { appData ->
                        Toast.makeText(this, "App : $appData", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun setupViewModel() {
        mainViewModel.context = this
        mainViewModel.updateConnectivityAction.observe(this) {
            if (it) {
                binding.noInternetConnection.visibility = View.GONE
                getUserDefaults()
            } else {
                binding.noInternetConnection.visibility = View.VISIBLE
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
                binding.connectServiceStatus.text = "متصل به سرور"
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
                binding.tryingToConnectService.visibility = View.GONE
                binding.networkPingLayout.visibility = View.VISIBLE
                binding.networkPingImg.setImageDrawable(getPingStatus(it.toLong()))
                binding.networkPingText.text = "$it   ms"
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
                            MmkvManager.removeAllServer()
                            setupLink(user.service!!.localLink)
                            startupCheckLink = false
                            startConnection()
                            toast("Now using tunnel!")
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
                binding.tryingToConnectService.visibility = View.VISIBLE
                binding.connectServiceStatus.text = "در حال اتصال ..."
                connectLevel = 0
                showTitleConnectingEffect()
                mainViewModel.testCurrentServerRealPing()
                //binding.checkConnectionPing.isEnabled = true
            } else {
                binding.fab.setImageResource(R.drawable.service_not_connected)
                binding.fab.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_grey))
                binding.connectServiceStatus.setTextColor(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_grey))
                )
                binding.tryingToConnectService.visibility = View.GONE
                binding.connectServiceStatus.text = "اتصال به سرور"
                binding.networkPingLayout.visibility = View.GONE
                binding.networkPingText.text = "   ..."
                binding.networkPingImg.setImageResource(R.drawable.gathering_network_ping)
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
                connectLevel = -1
                //binding.checkConnectionPing.isEnabled = false
            }
            hideCircle()
        }
        mainViewModel.startListenBroadcast()
    }

    private fun showTitleConnectingEffect() {
        if (connectLevel == 0) {
            Observable.timer(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    binding.connectServiceStatus.setTextColor(
                        ColorStateList.valueOf(
                            ContextCompat.getColor(
                                this,
                                android.R.color.white
                            )
                        )
                    )
                    if (connectLevel == 0) {
                        Observable.timer(500, TimeUnit.MILLISECONDS)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe {
                                binding.connectServiceStatus.setTextColor(
                                    ColorStateList.valueOf(
                                        ContextCompat.getColor(this, R.color.color_fab_grey)
                                    )
                                )
                                if (connectLevel == 0) {
                                    showTitleConnectingEffect()
                                } else if (connectLevel == 1) {
                                    binding.connectServiceStatus.setTextColor(
                                        ColorStateList.valueOf(
                                            ContextCompat.getColor(this, R.color.color_fab_orange)
                                        )
                                    )
                                } else {
                                    binding.connectServiceStatus.setTextColor(
                                        ColorStateList.valueOf(
                                            ContextCompat.getColor(this, R.color.color_fab_grey)
                                        )
                                    )
                                }
                            }
                    } else if (connectLevel == 1) {
                        binding.connectServiceStatus.setTextColor(
                            ColorStateList.valueOf(
                                ContextCompat.getColor(this, R.color.color_fab_orange)
                            )
                        )
                    } else {
                        binding.connectServiceStatus.setTextColor(
                            ColorStateList.valueOf(
                                ContextCompat.getColor(this, R.color.color_fab_grey)
                            )
                        )
                    }
                }
        } else if (connectLevel == 1) {
            binding.connectServiceStatus.setTextColor(
                ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_fab_orange)
                )
            )
        } else {
            binding.connectServiceStatus.setTextColor(
                ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_fab_grey)
                )
            )
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
        binding.signupLoginLayout.visibility = View.GONE
        binding.homeLayout.visibility = View.VISIBLE
        // TODO: Start here
        getUserService()
    }

    private fun getUserService() {
        binding.checkingServices.visibility = View.VISIBLE
        mainViewModel.sendGetServiceRequest(user, object : ApiCallback<Service> {
            override fun onSuccessful(responseObject: Service) {
                binding.checkingServices.visibility = View.GONE
                doConfigService(responseObject)
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                if (response?.message == "xuser not found" || response?.message == "service not found") {
                    binding.checkingServices.visibility = View.VISIBLE
                    binding.checkingServicesText.text = "در حال تخصیص سرویس"
                    mainViewModel.sendAllocateServiceRequest(
                        user,
                        object : ApiCallback<ResponseMsg> {
                            override fun onSuccessful(responseObject: ResponseMsg) {
                                binding.checkingServicesText.text = "در حال دستیابی سرویس"
                                mainViewModel.sendGetServiceRequest(
                                    user,
                                    object : ApiCallback<Service> {
                                        override fun onSuccessful(responseObject: Service) {
                                            binding.checkingServices.visibility = View.GONE
                                            doConfigService(responseObject)
                                        }

                                        override fun onFailure(
                                            response: ResponseMsg?,
                                            t: Throwable?
                                        ) {
                                            binding.checkingServices.visibility = View.GONE
                                            if (response?.message == "xuser not found" || response?.message == "service not found") {
                                                doPurchaseService()
                                            } else {
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
                                binding.checkingServices.visibility = View.GONE
                                if (response?.message == "user not found in service db") {
                                    doPurchaseService()
                                } else {
                                    Toast.makeText(
                                        activity,
                                        "خطا حین تخصیص سرویس: ${response?.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

                        })
                } else {
                    if (response?.message?.contains("token handle error") == true || response?.message == "user not found") {
                        Toast.makeText(
                            activity,
                            "به دلیل مسائل امنیتی، ملزم به ورود مجدد به برنامه هستید.",
                            Toast.LENGTH_LONG
                        ).show()
                        finishAndRemoveTask()
                    }
                    binding.checkingServices.visibility = View.GONE
                    Toast.makeText(
                        activity,
                        "خطا حین دستیابی به سرویس: ${response?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        })
    }

    fun doConfigService(service: Service) {
        user = user.copy(service = service)
        mainViewModel.saveUser(user)
        mainViewModel.sendGetLinksRequest(user, object : ApiCallback<Service> {
            override fun onSuccessful(responseObject: Service) {
                val aLink = responseObject.globalLink
                if (aLink.startsWith("vless://")) {
                    val id = aLink.substring(8..43)
                    var mergedId = ""
                    id.split("-").forEach {
                        mergedId += it
                    }
                    setupLink(aLink)
                    val provider = aLink.substringAfterLast("#").split("-")[0]
                    user = user.copy(
                        service = user.service!!.copy(
                            id = id,
                            provider = provider,
                            globalLink = responseObject.globalLink,
                            localLink = responseObject.localLink
                        )
                    )
                    mainViewModel.saveUser(user)
                    setConnectionLayout()
                } else {
                    Toast.makeText(
                        activity,
                        "پروتکل ${aLink.substringBefore("://", "unknown")} پشتیبانی نمی شود!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
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
        binding.connectService.visibility = View.VISIBLE
    }

    fun doPurchaseService() {
        mainViewModel.sendGetVSPListRequest(object : ApiCallback<VspList> {
            override fun onSuccessful(responseObject: VspList) {
                vspList = responseObject
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                Log.e("TAG", "Failed to fetch virtual service providers list: ${response?.message}")
            }

        })
    }

    private fun checkOtpDoLogin(it: String?) {
        binding.verifyCode.editText?.setText(it)
        binding.loadingBar.visibility = View.VISIBLE
        mainViewModel.sendSignInRequest(user.copy(otp = it), object : ApiCallback<User> {
            override fun onSuccessful(responseObject: User) {
                binding.loadingBar.visibility = View.GONE
                user = user.copy(id = responseObject.id, accessToken = responseObject.accessToken)
                mainViewModel.saveUser(
                    user.copy(
                        id = responseObject.id,
                        accessToken = responseObject.accessToken
                    )
                )
                setHomeLayout()
                Toast.makeText(activity, "خوش آمدید!", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(response: ResponseMsg?, t: Throwable?) {
                binding.loadingBar.visibility = View.GONE
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

    private fun onClicks() {
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
        binding.signupLoginLayout.visibility = View.VISIBLE
        binding.homeLayout.visibility = View.GONE
        binding.signupUsername.visibility = View.GONE
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
            sendVerifyRequest()
        }
    }

    private fun setSignUpLayout() {
        binding.signupUsername.visibility = View.VISIBLE
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
        binding.loadingBar.visibility = View.VISIBLE
        //val user2 = mainViewModel.loadUser()!!
        //Toast.makeText(activity, "Saved User => name: ${user2.userName} || phoneNumber: ${user2.phoneNumber}", Toast.LENGTH_SHORT).show()
        mainViewModel.sendSignUpRequest(user, object : ApiCallback<ResponseMsg> {
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
                binding.loadingBar.visibility = View.GONE
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
        binding.loadingBar.visibility = View.VISIBLE
        user = User(phoneNumber = phone)
        mainViewModel.sendVerifyPhoneRequest(user, object : ApiCallback<ResponseMsg> {
            override fun onSuccessful(responseObject: ResponseMsg) {
                binding.signupLayout.visibility = View.GONE
                binding.verifyLayout.visibility = View.VISIBLE
                binding.loadingBar.visibility = View.GONE
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
                binding.loadingBar.visibility = View.GONE
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
