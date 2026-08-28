package ai.opensquilla.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : Activity() {

    private val port = 18790
    private val fileChooserRequestCode = 4201
    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val shellColor = Color.parseColor("#202022")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the native system bars and the WebView shell on the same dark
        // surface. Otherwise Android's black window leaks above the rounded
        // web panel as a visible horizontal strip.
        window.statusBarColor = shellColor
        window.navigationBarColor = shellColor
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(shellColor)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = request.url.host != "127.0.0.1" && request.url.host != "localhost"

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !(url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))

                // Plain HTTP loopback never hits TLS, but stay tolerant if TLS is enabled.
                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError?
                ) {
                    val host = try {
                        java.net.URI(view.url ?: "").host ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    if (host == "127.0.0.1" || host == "localhost") handler.proceed() else handler.cancel()
                }
            }

            webChromeClient = object : WebChromeClient() {
                // Without this, <input type="file"> in the WebUI silently does nothing.
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams.createIntent()
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        startActivityForResult(intent, fileChooserRequestCode)
                        true
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        Toast.makeText(this@MainActivity, "无法打开文件选择器: ${e.message}", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            }

            // Exports / generated-file downloads -> system DownloadManager.
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val req = DownloadManager.Request(Uri.parse(url)).apply {
                        setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (cookies != null) addRequestHeader("Cookie", cookies)
                        addRequestHeader("User-Agent", userAgent)
                    }
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this@MainActivity, "已开始下载: $name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Chromium renders content across the whole WebView bounds and ignores
        // View padding, so edge-to-edge insets must be applied to a container
        // around the WebView instead of the WebView itself.
        val root = android.widget.FrameLayout(this).apply {
            // Also cover the native layer behind WebView corners; the default
            // window background must never leak through as black.
            setBackgroundColor(shellColor)
        }
        root.addView(
            webView,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.setOnApplyWindowInsetsListener { v, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            // Some ROMs report a suspiciously small status-bar inset; never let
            // content ride closer to the top than the real status-bar height.
            v.setPadding(left, maxOf(top, statusBarHeightPx()), right, bottom)
            insets
        }
        setContentView(root)
        Log.d("SQLaunch", "step1 setContentView done")
        ensureStoragePermissions()
        // IMPORTANT: never block the UI thread on Chaquopy init — it takes
        // seconds on cold start and the window would look frozen (ANR-ish).
        // Show the loading overlay, boot Python + gateway on a background
        // thread, then swap to the real page.
        showLoadingOverlay(root)
        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this@MainActivity))
                }
                Log.d("SQLaunch", "step3 python started")
                val py = Python.getInstance()
                py.getModule("opensquilla_android")
                    .callAttr("serve", filesDir.absolutePath)
                Log.d("SQLaunch", "step4 serve thread started")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
        // Background keeper: foreground service + notification + battery exemption,
        // so switching away does not kill the local gateway (Android 12+ / OEM killers).
        GatewayService.start(this)
        Log.d("SQLaunch", "step5 gateway service started")
        ensureNotificationPermission()
        Log.d("SQLaunch", "step6 notification done")
        ensureBatteryExemption()
        Log.d("SQLaunch", "step7 battery done")
        ensureBackgroundKeepAliveHint()
        Log.d("SQLaunch", "step8 background keepalive hint done")
        Thread {
            waitPort("127.0.0.1", port, timeoutMs = 90_000)
            Log.d("SQLaunch", "step8 port ready, loading url")
            runOnUiThread {
                loadingView?.visibility = View.GONE
                webView.loadUrl("http://127.0.0.1:$port/")
                Log.d("SQLaunch", "step9 loadUrl called")
            }
        }.start()
    }

    private var loadingView: android.view.View? = null

    private fun showLoadingOverlay(root: android.view.ViewGroup) {
        loadingView = android.widget.TextView(this).apply {
            text = "正在启动本地 AI 网关…"
            setTextColor(Color.parseColor("#8AA0B0"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(shellColor)
        }
        root.addView(
            loadingView,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == fileChooserRequestCode) {
            val cb = fileChooserCallback
            fileChooserCallback = null
            val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            cb?.onReceiveValue(results)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ── Storage permission onboarding ────────────────────────────────────────
    // "All files access" (MANAGE_EXTERNAL_STORAGE) is a special permission:
    // the system only grants it from its own settings page, so the app can
    // detect the gap and jump straight there. Legacy devices (<API 30) use a
    // normal runtime permission request instead.
    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun ensureStoragePermissions() {
        if (hasAllFilesAccess()) return
        if (Build.VERSION.SDK_INT >= 30) {
            AlertDialog.Builder(this)
                .setTitle("需要「所有文件」访问权限")
                .setMessage(
                    "OpenSquilla 的文件浏览、工作区与附件功能需要读取手机存储。" +
                        "\n\n点击「去设置」，在打开的页面中开启「允许管理所有文件」，然后返回即可。"
                )
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (ignored: Exception) {
                        }
                    }
                }
                .setNegativeButton("暂不", null)
                .show()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                4210
            )
        }
    }

    // ── Background keeper onboarding ─────────────────────────────────────────
    // Android 13+ needs a runtime grant for the ongoing service notification;
    // battery-optimization exemption must be granted from the system dialog.
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4211)
        }
    }
    private fun ensureBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("允许后台常驻")
            .setMessage(
                "切换应用后仍能使用 OpenSquilla 本地 AI 网关，需要允许它不受电池优化限制。" +
                    "\n\n点击「去设置」，将 OpenSquilla 设为「不限制」，然后返回即可。"
            )
            .setPositiveButton("去设置") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (ignored: Exception) {
                    }
                }
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    // ── Background-freeze onboarding (all OEMs) ──────────────────────────────
    // Most Android OEMs freeze or throttle background processes regardless of
    // the active foreground service; freezing pauses the asyncio event loop
    // while clocks keep running, so in-flight turns die the moment the process
    // thaws ("connection to the model provider was interrupted"). The engine
    // deadlines now tolerate ordinary freezes, but the durable fix is the
    // OEM's own keep-alive switches (autostart / background activity /
    // battery unrestricted / Recents lock). Show the steps once on every
    // device and deep-link the vendor's own management page when known.
    private fun ensureBackgroundKeepAliveHint() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (prefs.getBoolean("background_keepalive_hint_dismissed", false)) return
        AlertDialog.Builder(this)
            .setTitle("允许后台运行")
            .setMessage(
                "切出 OpenSquilla 后，部分系统会冻结或限制后台进程，导致进行中的任务中断。\n\n建议在系统设置中：\n" +
                    "1. 允许 OpenSquilla「自启动 / 关联启动 / 后台运行」（各厂商叫法不同）；\n" +
                    "2. 电池/省电策略设为不限制（此前已引导）；\n" +
                    "3. 最近任务界面下拉 OpenSquilla 卡片加锁。"
            )
            .setPositiveButton("打开设置") { _, _ ->
                prefs.edit().putBoolean("background_keepalive_hint_dismissed", true).apply()
                openOemBackgroundSettings()
            }
            .setNegativeButton("下次再说") { _, _ ->
                prefs.edit().putBoolean("background_keepalive_hint_dismissed", true).apply()
            }
            .show()
    }

    /**
     * Open the vendor's autostart / background-activity management page for
     * this app. Every vendor entry is best-effort: unknown components fall
     * through to the system app-details page, which every Android device has.
     */
    private fun openOemBackgroundSettings() {
        val manufacturer = (Build.MANUFACTURER ?: "").uppercase()
        val brand = (Build.BRAND ?: "").uppercase()
        val vendorKey = when {
            manufacturer.contains("HUAWEI") || manufacturer.contains("HONOR") -> "huawei"
            manufacturer.contains("XIAOMI") || brand.contains("REDMI") || brand.contains("POCO") -> "xiaomi"
            manufacturer.contains("OPPO") || manufacturer.contains("REALME")
                || manufacturer.contains("ONEPLUS") -> "oppo"
            manufacturer.contains("VIVO") || manufacturer.contains("IQOO") -> "vivo"
            manufacturer.contains("SAMSUNG") -> "samsung"
            manufacturer.contains("MEIZU") -> "meizu"
            manufacturer.contains("TRANSSESSION") || brand.contains("TECNO")
                || brand.contains("INFINIX") || brand.contains("ITEL") -> "transsion"
            else -> "generic"
        }
        val components: List<Pair<String, String>> = when (vendorKey) {
            "huawei" -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            )
            "xiaomi" -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
            )
            "oppo" -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
            "vivo" -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            )
            "meizu" -> listOf(
                "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
                "com.meizu.safe" to "com.meizu.safe.security.ShowAppListActivity",
            )
            "transsion" -> listOf(
                "com.transsion.permissionmanager" to "com.transsion.permissionmanager.permission.StartupAPPsControlActivity",
            )
            "samsung" -> listOf(
                "com.samsung.android.lox" to "com.samsung.android.lox.activity.MainActivity",
            )
            else -> emptyList()
        }
        for ((packageName, className) in components) {
            try {
                startActivity(Intent().setComponent(ComponentName(packageName, className)))
                return
            } catch (e: Exception) {
                // Try the next known component for this vendor.
            }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (ignored: Exception) {
        }
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (36 * resources.displayMetrics.density).toInt()
    }

    private fun waitPort(host: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 800)
                }
                return
            } catch (e: Exception) {
                Thread.sleep(400)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.apply {
                loadUrl("about:blank")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
        }
        super.onDestroy()
    }
}