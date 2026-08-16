package com.example

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.animation.ObjectAnimator
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "ESP_TopUp"
    const val PREFS_NAME = "esp_topup_prefs"
    const val CACHE_KEY = "esp_user_website_url"
    const val LAST_NOTIF_KEY = "last_seen_notification_id"
    const val DEFAULT_URL = "https://esptopup.github.io/com/"
    const val GOOGLE_AUTH_URL = "https://esptopup.github.io/com/google-auth.html"
    
    // Notification Channel
    const val NOTIFICATION_CHANNEL_ID = "esp_topup_channel"
    const val NOTIFICATION_CHANNEL_NAME = "ESP TopUp Notifications"

    // Primary Firebase Endpoints (User's project: espopup-bd)
    const val FIREBASE_RTDB_URL_USER = "https://espopup-bd-default-rtdb.firebaseio.com/websiteUrl.json"
    const val FIREBASE_EXIT_DIALOG_URL_USER = "https://espopup-bd-default-rtdb.firebaseio.com/exitDialogConfig.json"
    const val FIREBASE_NOTIF_URL_USER_1 = "https://espopup-bd-default-rtdb.firebaseio.com/pushNotification.json"
    const val FIREBASE_NOTIF_URL_USER_2 = "https://espopup-bd-default-rtdb.firebaseio.com/notifications.json"

    // Fallback Firebase Endpoints
    const val FIREBASE_RTDB_URL_FALLBACK_1 = "https://samim-firebase-default-rtdb.firebaseio.com/websiteUrl.json"
    const val FIREBASE_RTDB_URL_FALLBACK_2 = "https://samim-firebase.firebaseio.com/websiteUrl.json"
    const val FIREBASE_EXIT_DIALOG_FALLBACK_1 = "https://samim-firebase-default-rtdb.firebaseio.com/exitDialogConfig.json"
    const val FIREBASE_EXIT_DIALOG_FALLBACK_2 = "https://samim-firebase.firebaseio.com/exitDialogConfig.json"
    const val FIREBASE_NOTIF_URL_FALLBACK_1 = "https://samim-firebase-default-rtdb.firebaseio.com/pushNotification.json"
    const val FIREBASE_NOTIF_URL_FALLBACK_2 = "https://samim-firebase.firebaseio.com/pushNotification.json"

    // Firebase Web Client ID for ESP TopUp (Project 1007807073745 / fallback)
    const val GOOGLE_SERVER_CLIENT_ID = "1007807073745-web.apps.googleusercontent.com"
    const val GOOGLE_SERVER_CLIENT_ID_FALLBACK = "521003542655-web.apps.googleusercontent.com"
  }

  private lateinit var webView: WebView
  private lateinit var rootContainer: FrameLayout
  private lateinit var customViewContainer: FrameLayout
  private var customView: View? = null
  private var customViewCallback: WebChromeClient.CustomViewCallback? = null

  private lateinit var prefs: SharedPreferences
  private lateinit var credentialManager: CredentialManager

  // Back click counter & custom dialog overlay
  private var backClickCount = 0
  private var lastBackClickTime = 0L
  private var exitDialogOverlay: FrameLayout? = null
  private lateinit var webViewLoadingOverlay: FrameLayout
  private lateinit var webViewProgressBar: ProgressBar

  // Dynamic Exit Dialog Configuration from Firebase / Admin
  private var dialogTitleText = "Do you want to exit ESP TopUp?"
  private var dialogDescText = "Are you sure you want to close the app? We hope to see you back soon!"
  private var dialogExitBtnText = "Exit App"
  private var dialogCloseBtnText = "Cancel"
  private var dialogExitBtnColor = "#E11D48" // Rose red
  private var dialogCloseBtnColor = "#334155" // Slate dark
  private var dialogBgColor = "#1E293B" // Slate 800
  private var dialogTextColor = "#FFFFFF"
  private var dialogLogoUrl = ""

  // File Upload
  private var filePathCallback: ValueCallback<Array<Uri>>? = null

  // Web Permissions handling
  private var pendingPermissionRequest: PermissionRequest? = null
  private var pendingGeoCallback: GeolocationPermissions.Callback? = null
  private var pendingGeoOrigin: String? = null

  // Notification Permission Launcher (Android 13+)
  private val notifPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
      if (isGranted) {
        Log.d(TAG, "Notification permission granted by user")
      } else {
        Log.w(TAG, "Notification permission denied by user")
      }
    }

  private val fileChooserLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (filePathCallback != null) {
        val resultUriArray = when {
          result.resultCode == RESULT_OK && result.data != null -> {
            val data = result.data
            val clipData = data?.clipData
            if (clipData != null && clipData.itemCount > 0) {
              Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else if (data?.data != null) {
              arrayOf(data.data!!)
            } else {
              null
            }
          }
          else -> null
        }
        filePathCallback?.onReceiveValue(resultUriArray)
        filePathCallback = null
      }
    }

  // System Google Account Chooser launcher
  private val accountPickerLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK && result.data != null) {
        val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!accountName.isNullOrBlank()) {
          onNativeGoogleAccountSelected(
            email = accountName,
            displayName = accountName.substringBefore("@"),
            idToken = null
          )
        }
      }
    }

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
      // WebKit Permission Request resolution
      pendingPermissionRequest?.let { request ->
        val grantedList = mutableListOf<String>()
        if (permissions[Manifest.permission.CAMERA] == true) {
          grantedList.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        }
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
          grantedList.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        }
        if (grantedList.isNotEmpty()) {
          request.grant(grantedList.toTypedArray())
        } else {
          request.deny()
        }
        pendingPermissionRequest = null
      }

      // Geolocation resolution
      if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
      ) {
        pendingGeoCallback?.invoke(pendingGeoOrigin, true, false)
      } else {
        pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
      }
      pendingGeoCallback = null
      pendingGeoOrigin = null
    }

  private val okHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS, okhttp3.ConnectionSpec.COMPATIBLE_TLS, okhttp3.ConnectionSpec.CLEARTEXT))
      .build()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    try {
      val webViewCacheDir = java.io.File(cacheDir, "WebView")
      if (!webViewCacheDir.exists()) {
        webViewCacheDir.mkdirs()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Cache dir notice: ${e.message}")
    }

    prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    credentialManager = CredentialManager.create(this)

    createNotificationChannel()
    requestNotificationPermissionIfNeeded()

    setupWindowDecor()
    setupViews()
    setupBackNavigation()

    // Handle deep link / notification target URL if present in Intent
    val intentUrl = intent?.getStringExtra("target_url")
    val initialUrl = if (!intentUrl.isNullOrBlank()) {
      intentUrl
    } else {
      prefs.getString(CACHE_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
    }
    webView.loadUrl(initialUrl)

    // Parallel background Firebase Realtime Database website synchronization
    syncFirebaseWebsiteUrl()

    // Realtime background live Push Notification listener
    startLiveNotificationListener()

    // Start persistent background push notification listener service
    NotificationBackgroundService.startService(this)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val targetUrl = intent.getStringExtra("target_url")
    if (!targetUrl.isNullOrBlank()) {
      webView.loadUrl(targetUrl)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()

      val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        NOTIFICATION_CHANNEL_NAME,
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = "Live admin notifications and alerts from ESP TopUp"
        enableLights(true)
        lightColor = Color.parseColor("#FF5722")
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 250, 150, 250)
        setSound(soundUri, audioAttributes)
        setShowBadge(true)
        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
      }

      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  /**
   * Automatically requests Android 13+ Notification Permission on app entry
   */
  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
      ) {
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  private fun setupWindowDecor() {
    WindowCompat.setDecorFitsSystemWindows(window, true)
    window.statusBarColor = Color.WHITE
    window.navigationBarColor = Color.WHITE

    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
    windowInsetsController.isAppearanceLightStatusBars = true
    windowInsetsController.isAppearanceLightNavigationBars = true
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupViews() {
    rootContainer = FrameLayout(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.WHITE)
    }

    customViewContainer = FrameLayout(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      visibility = View.GONE
      setBackgroundColor(Color.BLACK)
    }

    webView = WebView(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.WHITE)
      isScrollbarFadingEnabled = true
      isVerticalScrollBarEnabled = false
      isHorizontalScrollBarEnabled = false
    }

    configureWebSettings(webView.settings)

    // Configure Cookies
    CookieManager.getInstance().apply {
      setAcceptCookie(true)
      setAcceptThirdPartyCookies(webView, true)
    }

    // Add JavaScript Bridge (Support all interfaces: AndroidApp, AndroidBridge, AndroidInterface, Android)
    val webAppInterface = AndroidWebAppInterface()
    webView.addJavascriptInterface(webAppInterface, "AndroidBridge")
    webView.addJavascriptInterface(webAppInterface, "AndroidApp")
    webView.addJavascriptInterface(webAppInterface, "AndroidInterface")
    webView.addJavascriptInterface(webAppInterface, "Android")

    // Setup Clients
    webView.webViewClient = AppWebViewClient()
    webView.webChromeClient = AppWebChromeClient()

    // Create subtle animated loading overlay for seamless in-app website transitions
    webViewLoadingOverlay = FrameLayout(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.argb(160, 15, 23, 42)) // Semi-transparent Slate 900
      visibility = View.GONE
      isClickable = true
      isFocusable = true
    }

    val loadingCard = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      val bg = GradientDrawable().apply {
        setColor(Color.parseColor("#1E293B"))
        cornerRadius = 32f
        setStroke(2, Color.parseColor("#334155"))
      }
      background = bg
      setPadding(48, 36, 48, 36)
      val cardParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.CENTER
      }
      layoutParams = cardParams
    }

    webViewProgressBar = ProgressBar(this).apply {
      val size = (44 * resources.displayMetrics.density).toInt()
      layoutParams = LinearLayout.LayoutParams(size, size).apply {
        bottomMargin = (12 * resources.displayMetrics.density).toInt()
      }
      indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5722"))
    }

    val loadingText = TextView(this).apply {
      text = "ESP TopUp Loading..."
      setTextColor(Color.WHITE)
      textSize = 14f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    loadingCard.addView(webViewProgressBar)
    loadingCard.addView(loadingText)
    webViewLoadingOverlay.addView(loadingCard)

    rootContainer.addView(webView)
    rootContainer.addView(customViewContainer)
    rootContainer.addView(webViewLoadingOverlay)
    setContentView(rootContainer)
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun configureWebSettings(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.allowFileAccess = true
    settings.allowContentAccess = true
    settings.allowFileAccessFromFileURLs = true
    settings.allowUniversalAccessFromFileURLs = true
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.setGeolocationEnabled(true)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.javaScriptCanOpenWindowsAutomatically = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      settings.safeBrowsingEnabled = false
    }
    // Single window mode keeps all OAuth/login actions smoothly within the active WebView
    settings.setSupportMultipleWindows(false)

    // Clean user agent to standard Chrome mobile to prevent Google blocking or blank frames
    val defaultUa = settings.userAgentString
    val cleanedUa = defaultUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
    settings.userAgentString = cleanedUa
  }

  /**
   * Enhanced Multi-tap Back Navigation:
   * When the user clicks the phone's back button 2-3 times at the root level,
   * a customizable, animated dialog box appears with Exit and Close/Cancel buttons.
   */
  private fun setupBackNavigation() {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (customView != null) {
          // Exit fullscreen video
          webView.webChromeClient?.onHideCustomView()
          return
        }

        // If exit dialog is already showing, dismiss it
        if (exitDialogOverlay != null && exitDialogOverlay?.visibility == View.VISIBLE) {
          dismissExitDialog()
          return
        }

        val history = webView.copyBackForwardList()
        val currentIndex = history.currentIndex

        if (currentIndex > 0) {
          val previousItem = history.getItemAtIndex(currentIndex - 1)
          val previousUrl = previousItem?.url ?: ""

          // If the previous history entry was a blank page, google auth popup, or error redirect, go directly to cached/main app
          if (previousUrl.isBlank() ||
            previousUrl == "about:blank" ||
            previousUrl.contains("accounts.google.com") ||
            previousUrl.contains("google-auth.html") ||
            previousUrl.contains("firebaseapp.com/__/auth")
          ) {
            val mainHomeUrl = prefs.getString(CACHE_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
            webView.loadUrl(mainHomeUrl)
          } else {
            webView.goBack()
          }
        } else {
          // We are at root of history
          val currentUrl = webView.url ?: ""
          val mainHomeUrl = prefs.getString(CACHE_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
          if (currentUrl.isNotBlank() && currentUrl != mainHomeUrl && !currentUrl.contains("esptopup.github.io/com")) {
            webView.loadUrl(mainHomeUrl)
          } else {
            // Check double/triple click within 2.5 seconds
            val now = System.currentTimeMillis()
            if (now - lastBackClickTime < 2500) {
              backClickCount++
            } else {
              backClickCount = 1
            }
            lastBackClickTime = now

            if (backClickCount >= 2) {
              showCustomExitDialog()
            } else {
              android.widget.Toast.makeText(
                this@MainActivity,
                "Press back again to exit",
                android.widget.Toast.LENGTH_SHORT
              ).show()
            }
          }
        }
      }
    })
  }

  /**
   * Shows a square, fully admin-customizable Exit Confirmation Dialog box.
   */
  private fun showCustomExitDialog() {
    if (exitDialogOverlay != null) {
      rootContainer.removeView(exitDialogOverlay)
      exitDialogOverlay = null
    }

    val density = resources.displayMetrics.density

    val overlay = FrameLayout(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.argb(180, 0, 0, 0)) // Dim dark backdrop
      isClickable = true
      isFocusable = true
      setOnClickListener {
        dismissExitDialog()
      }
    }

    // Square Dialog Box Container
    val dialogBox = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL

      val parsedBgColor = try {
        Color.parseColor(dialogBgColor)
      } catch (e: Exception) {
        Color.parseColor("#1E293B")
      }

      val cardBg = GradientDrawable().apply {
        setColor(parsedBgColor)
        cornerRadius = 24 * density
        setStroke((1.5f * density).toInt(), Color.parseColor("#475569"))
      }
      background = cardBg

      val paddingPx = (22 * density).toInt()
      setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

      val boxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtMost((360 * density).toInt())
      val boxParams = FrameLayout.LayoutParams(boxWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.CENTER
      }
      layoutParams = boxParams
      isClickable = true
    }

    // Dynamic Logo (Square shape, customizable from Admin / URL or default app logo)
    val logoView = ImageView(this).apply {
      val logoSize = (68 * density).toInt()
      layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
        bottomMargin = (14 * density).toInt()
        gravity = Gravity.CENTER_HORIZONTAL
      }
      scaleType = ImageView.ScaleType.CENTER_CROP

      val logoBg = GradientDrawable().apply {
        setColor(Color.parseColor("#FF5722"))
        cornerRadius = 16 * density
      }
      background = logoBg
      clipToOutline = true

      if (dialogLogoUrl.isNotBlank() && (dialogLogoUrl.startsWith("http://") || dialogLogoUrl.startsWith("https://"))) {
        lifecycleScope.launch(Dispatchers.IO) {
          val bmp = downloadBitmap(dialogLogoUrl)
          withContext(Dispatchers.Main) {
            if (bmp != null) {
              setImageBitmap(bmp)
            } else {
              setImageResource(R.drawable.ic_esp_topup_logo)
            }
          }
        }
      } else {
        setImageResource(R.drawable.ic_esp_topup_logo)
      }
    }
    dialogBox.addView(logoView)

    // Title Text
    val titleView = TextView(this).apply {
      text = dialogTitleText
      textSize = 18f
      setTextColor(try { Color.parseColor(dialogTextColor) } catch (e: Exception) { Color.WHITE })
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        bottomMargin = (8 * density).toInt()
      }
    }
    dialogBox.addView(titleView)

    // Description / Subtitle Text
    val descView = TextView(this).apply {
      text = dialogDescText
      textSize = 13.5f
      setTextColor(Color.parseColor("#94A3B8"))
      gravity = Gravity.CENTER
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        bottomMargin = (20 * density).toInt()
      }
    }
    dialogBox.addView(descView)

    // Action Buttons Container (Side-by-side or stacked cleanly)
    val buttonsRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      weightSum = 2f
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        (46 * density).toInt()
      )
    }

    // Close / Cancel Button
    val closeBtn = TextView(this).apply {
      text = dialogCloseBtnText
      textSize = 14f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)

      val parsedCloseColor = try {
        Color.parseColor(dialogCloseBtnColor)
      } catch (e: Exception) {
        Color.parseColor("#334155")
      }

      val closeBtnBg = GradientDrawable().apply {
        setColor(parsedCloseColor)
        cornerRadius = 12 * density
      }
      background = closeBtnBg

      layoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        1f
      ).apply {
        rightMargin = (6 * density).toInt()
      }

      setOnClickListener {
        dismissExitDialog()
      }
    }

    // Exit Button
    val exitBtn = TextView(this).apply {
      text = dialogExitBtnText
      textSize = 14f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)

      val parsedExitColor = try {
        Color.parseColor(dialogExitBtnColor)
      } catch (e: Exception) {
        Color.parseColor("#E11D48")
      }

      val exitBtnBg = GradientDrawable().apply {
        setColor(parsedExitColor)
        cornerRadius = 12 * density
      }
      background = exitBtnBg

      layoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        1f
      ).apply {
        leftMargin = (6 * density).toInt()
      }

      setOnClickListener {
        finishAffinity()
      }
    }

    buttonsRow.addView(closeBtn)
    buttonsRow.addView(exitBtn)
    dialogBox.addView(buttonsRow)

    overlay.addView(dialogBox)
    exitDialogOverlay = overlay
    rootContainer.addView(overlay)

    // Entrance Animation (Scale & Fade)
    dialogBox.scaleX = 0.8f
    dialogBox.scaleY = 0.8f
    dialogBox.alpha = 0f
    dialogBox.animate()
      .scaleX(1.0f)
      .scaleY(1.0f)
      .alpha(1.0f)
      .setDuration(220)
      .start()
  }

  private fun dismissExitDialog() {
    exitDialogOverlay?.let { overlay ->
      overlay.animate()
        .alpha(0f)
        .setDuration(160)
        .withEndAction {
          rootContainer.removeView(overlay)
          exitDialogOverlay = null
          backClickCount = 0
        }
        .start()
    }
  }

  /**
   * Smoothly transitions to a new Website URL with a soft in-app loading overlay
   * so the user sees a pristine animation and zero white screen or error lock.
   */
  fun loadWebsiteSmoothly(newUrl: String) {
    runOnUiThread {
      webViewLoadingOverlay.alpha = 0f
      webViewLoadingOverlay.visibility = View.VISIBLE
      webViewLoadingOverlay.animate()
        .alpha(1.0f)
        .setDuration(180)
        .withEndAction {
          webView.loadUrl(newUrl)
        }
        .start()
    }
  }

  /**
   * Realtime Live Push Notification listener from Firebase Realtime Database.
   * Priority: espopup-bd-default-rtdb -> fallback
   */
  private fun startLiveNotificationListener() {
    lifecycleScope.launch(Dispatchers.IO) {
      val endpoints = listOf(
        FIREBASE_NOTIF_URL_USER_1,
        FIREBASE_NOTIF_URL_USER_2,
        FIREBASE_NOTIF_URL_FALLBACK_1,
        FIREBASE_NOTIF_URL_FALLBACK_2
      )

      while (isActive) {
          for (endpoint in endpoints) {
            try {
              val request = Request.Builder().url(endpoint).build()
              okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                  val body = response.body?.string()?.trim()
                  if (!body.isNullOrBlank() && body != "null") {
                    parseAndTriggerNotification(body)
                    return@use
                  }
                }
              }
            } catch (e: Exception) {
              Log.w(TAG, "Notification check notice: ${e.message}")
            }
          }
        // Poll every 5 seconds for instant admin push notification updates
        delay(5000)
      }
    }
  }

  private suspend fun parseAndTriggerNotification(rawJson: String) {
    try {
      val json = JSONObject(rawJson)
      // Extract fields (supports id, title, message/body, imageUrl/logo/icon, targetUrl/url, timestamp)
      val notifId = json.optString("id", "")
        .ifBlank { json.optString("timestamp", "") }
        .ifBlank { json.optString("title", "") + "_" + json.optString("message", "") }

      if (notifId.isBlank()) return

      val lastSeenId = prefs.getString(LAST_NOTIF_KEY, "")
      if (notifId == lastSeenId) {
        // Notification already displayed to user
        return
      }

      val title = json.optString("title", "ESP TopUp").ifBlank { "ESP TopUp" }
      val message = json.optString("message", "")
        .ifBlank { json.optString("body", "") }
        .ifBlank { json.optString("text", "") }

      if (message.isBlank()) return

      val imageUrl = json.optString("imageUrl", "")
        .ifBlank { json.optString("image", "") }
        .ifBlank { json.optString("logo", "") }
        .ifBlank { json.optString("icon", "") }

      val targetUrl = json.optString("url", "")
        .ifBlank { json.optString("link", "") }

      // Save as seen to prevent repeating
      prefs.edit().putString(LAST_NOTIF_KEY, notifId).apply()

      // Download bitmap if image URL provided
      var bannerBitmap: Bitmap? = null
      if (imageUrl.isNotBlank() && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
        bannerBitmap = downloadBitmap(imageUrl)
      }

      withContext(Dispatchers.Main) {
        showSystemNotification(
          title = title,
          message = message,
          targetUrl = targetUrl,
          bitmap = bannerBitmap
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse push notification payload", e)
    }
  }

  private fun showSystemNotification(
    title: String,
    message: String,
    targetUrl: String?,
    bitmap: Bitmap?
  ) {
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      if (!targetUrl.isNullOrBlank()) {
        putExtra("target_url", targetUrl)
      }
    }

    val pendingIntent = PendingIntent.getActivity(
      this,
      (System.currentTimeMillis() % 100000).toInt(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      // Custom status bar small icon with the letter 'T'
      .setSmallIcon(R.drawable.ic_notification_t)
      .setContentTitle(title)
      .setContentText(message)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .setSound(soundUri)
      .setVibrate(longArrayOf(0, 250, 150, 250))
      .setContentIntent(pendingIntent)
      .setColor(Color.parseColor("#FF5722"))

    if (bitmap != null) {
      builder.setLargeIcon(bitmap)
      builder.setStyle(
        NotificationCompat.BigPictureStyle()
          .bigPicture(bitmap)
          .setSummaryText(message)
      )
    } else {
      builder.setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(message)
      )
    }

    try {
      val notificationManager = NotificationManagerCompat.from(this)
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
      ) {
        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error displaying notification", e)
    }
  }

  private fun downloadBitmap(urlString: String): Bitmap? {
    return try {
      val url = URL(urlString)
      val connection = url.openConnection() as HttpURLConnection
      connection.doInput = true
      connection.connectTimeout = 7000
      connection.readTimeout = 7000
      connection.connect()
      val input: InputStream = connection.inputStream
      BitmapFactory.decodeStream(input)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to download notification image: $urlString", e)
      null
    }
  }

  /**
   * Triggers native Google Account selection bottom sheet / dialog.
   * Shows all Gmail accounts on the user's Android phone with 1-tap sign-in!
   */
  fun triggerNativeGoogleSignIn(authUrl: String? = null) {
    lifecycleScope.launch {
      var credentialRetrieved = false

      val clientIdsToTry = listOf(GOOGLE_SERVER_CLIENT_ID, GOOGLE_SERVER_CLIENT_ID_FALLBACK)
      for (clientId in clientIdsToTry) {
        try {
          val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()

          val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

          val result = credentialManager.getCredential(this@MainActivity, request)
          val credential = result.credential

          if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val email = googleIdTokenCredential.id
            val displayName = googleIdTokenCredential.displayName ?: email.substringBefore("@")
            val idToken = googleIdTokenCredential.idToken
            val profilePictureUri = googleIdTokenCredential.profilePictureUri?.toString()

            credentialRetrieved = true
            onNativeGoogleAccountSelected(
              email = email,
              displayName = displayName,
              idToken = idToken,
              profilePicture = profilePictureUri
            )
            break
          }
        } catch (e: GetCredentialException) {
          Log.w(TAG, "Credential Manager notice: ${e.message}")
        } catch (e: Exception) {
          Log.w(TAG, "Credential error notice: ${e.message}")
        }
      }

      // Fallback to Android System Google Account Chooser
      if (!credentialRetrieved) {
        try {
          val chooseAccountIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccountManager.newChooseAccountIntent(
              null,
              null,
              arrayOf("com.google"),
              null,
              null,
              null,
              null
            )
          } else {
            AccountManager.newChooseAccountIntent(
              null,
              null,
              arrayOf("com.google"),
              false,
              null,
              null,
              null,
              null
            )
          }
          accountPickerLauncher.launch(chooseAccountIntent)
        } catch (e: Exception) {
          Log.w(TAG, "System Account Chooser fallback to web auth", e)
          openGoogleAuthUrl(authUrl)
        }
      }
    }
  }

  /**
   * Called when user selects a Google account from the native bottom sheet / dialog.
   * Delivers selected Google user details directly into the website JavaScript context and Firebase.
   */
  private fun onNativeGoogleAccountSelected(
    email: String,
    displayName: String,
    idToken: String? = null,
    profilePicture: String? = null
  ) {
    runOnUiThread {
      val userJson = JSONObject().apply {
        put("email", email)
        put("displayName", displayName)
        put("name", displayName)
        put("idToken", idToken ?: "")
        put("token", idToken ?: "")
        put("photoUrl", profilePicture ?: "")
        put("isNativeAuth", true)
      }

      val jsPayload = userJson.toString()
      val safeEmail = JSONObject.quote(email)
      val safeName = JSONObject.quote(displayName)
      val safePhoto = JSONObject.quote(profilePicture ?: "logo.png")

      // Deliver into website DOM, Firebase Auth handler, and registration form inputs
      val injectScript = """
        (function() {
          const authData = $jsPayload;
          const userEmail = $safeEmail;
          const userName = $safeName;
          const userPhoto = $safePhoto;

          try {
            window.postMessage({ action: 'google_login_success', user: authData, token: authData.idToken, credential: authData.idToken }, '*');
          } catch(e) {}

          // Call your website's exact direct Google login functions
          if (typeof window.onGoogleAccountItemClick === 'function') {
            try {
              window.onGoogleAccountItemClick(userEmail, userName, 'bg-sky-600', userName.substring(0, 2).toUpperCase());
            } catch(e) { console.error("onGoogleAccountItemClick error", e); }
          } else if (typeof window.onGoogleSignInSelected === 'function') {
            try {
              window.onGoogleSignInSelected(userEmail, userName, userPhoto);
            } catch(e) { console.error("onGoogleSignInSelected error", e); }
          }

          if (typeof window.handleCredentialResponse === 'function') {
            try {
              window.handleCredentialResponse({ credential: authData.idToken, select_by: 'user_1tap' });
            } catch(e) {}
          }
          
          if (typeof window.onGoogleSignInSuccess === 'function') {
            try {
              window.onGoogleSignInSuccess(authData);
            } catch(e) {}
          }
          
          // Fill input fields if present on Login / Register form
          const emailInput = document.querySelector('input[type="email"], input[name="email"], input[id*="email"], input[placeholder*="email" i], input[placeholder*="gmail" i]');
          if (emailInput) {
            emailInput.value = authData.email;
            emailInput.dispatchEvent(new Event('input', { bubbles: true }));
            emailInput.dispatchEvent(new Event('change', { bubbles: true }));
          }

          const nameInput = document.querySelector('input[name="name"], input[name="username"], input[id*="name"], input[placeholder*="name" i]');
          if (nameInput && !nameInput.value) {
            nameInput.value = authData.displayName;
            nameInput.dispatchEvent(new Event('input', { bubbles: true }));
            nameInput.dispatchEvent(new Event('change', { bubbles: true }));
          }
        })();
      """.trimIndent()

      webView.evaluateJavascript(injectScript, null)

      val currentUrl = webView.url ?: ""
      if (currentUrl.contains("google-auth.html", ignoreCase = true)) {
        val returnUrl = "${GOOGLE_AUTH_URL}?email=${Uri.encode(email)}&name=${Uri.encode(displayName)}"
        webView.loadUrl(returnUrl)
      }
    }
  }

  /**
   * Continuous native Firebase Realtime Database website URL and Exit Dialog synchronization.
   * Checks every 4 seconds in the background so that any website change made in Admin Panel
   * will instantly update on user's screen with a smooth in-app loading animation!
   */
  private fun syncFirebaseWebsiteUrl() {
    lifecycleScope.launch(Dispatchers.IO) {
      val websiteEndpoints = listOf(
        FIREBASE_RTDB_URL_USER,
        FIREBASE_RTDB_URL_FALLBACK_1,
        FIREBASE_RTDB_URL_FALLBACK_2
      )

      val exitDialogEndpoints = listOf(
        FIREBASE_EXIT_DIALOG_URL_USER,
        FIREBASE_EXIT_DIALOG_FALLBACK_1,
        FIREBASE_EXIT_DIALOG_FALLBACK_2
      )

      while (isActive) {
        // 1. Synchronize Website URL
        for (endpoint in websiteEndpoints) {
          try {
            val request = Request.Builder().url(endpoint).build()
            var targetFound: String? = null
            okHttpClient.newCall(request).execute().use { response ->
              if (response.isSuccessful) {
                val responseBody = response.body?.string()?.trim()
                if (!responseBody.isNullOrBlank() && responseBody != "null") {
                  targetFound = if (responseBody.startsWith("\"") && responseBody.endsWith("\"") && responseBody.length > 2) {
                    responseBody.substring(1, responseBody.length - 1)
                  } else {
                    responseBody
                  }
                }
              }
            }

            if (targetFound != null) {
              val normalized = normalizeUrl(targetFound)
              if (normalized != null) {
                val currentCached = prefs.getString(CACHE_KEY, null)
                if (currentCached != normalized) {
                  prefs.edit().putString(CACHE_KEY, normalized).apply()
                  withContext(Dispatchers.Main) {
                    val activeUrl = webView.url ?: ""
                    if (activeUrl != normalized && !activeUrl.contains("admin.html")) {
                      loadWebsiteSmoothly(normalized)
                    }
                  }
                }
                break
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Firebase website sync check notice: ${e.message}")
          }
        }

        // 2. Synchronize Exit Dialog Custom Configuration
        for (dialogEndpoint in exitDialogEndpoints) {
          try {
            val request = Request.Builder().url(dialogEndpoint).build()
            okHttpClient.newCall(request).execute().use { response ->
              if (response.isSuccessful) {
                val body = response.body?.string()?.trim()
                if (!body.isNullOrBlank() && body != "null" && body.startsWith("{")) {
                  parseAndApplyExitDialogConfig(body)
                  return@use
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Exit dialog sync notice: ${e.message}")
          }
        }

        // Check every 4 seconds for instantaneous admin updates
        delay(4000)
      }
    }
  }

  private fun parseAndApplyExitDialogConfig(rawJson: String) {
    try {
      val json = JSONObject(rawJson)
      dialogTitleText = json.optString("title", dialogTitleText).ifBlank { dialogTitleText }
      dialogDescText = json.optString("message", dialogDescText).ifBlank { dialogDescText }
      dialogExitBtnText = json.optString("exitBtnText", dialogExitBtnText).ifBlank { dialogExitBtnText }
      dialogCloseBtnText = json.optString("closeBtnText", dialogCloseBtnText).ifBlank { dialogCloseBtnText }
      dialogExitBtnColor = json.optString("exitBtnColor", dialogExitBtnColor).ifBlank { dialogExitBtnColor }
      dialogCloseBtnColor = json.optString("closeBtnColor", dialogCloseBtnColor).ifBlank { dialogCloseBtnColor }
      dialogBgColor = json.optString("bgColor", dialogBgColor).ifBlank { dialogBgColor }
      dialogTextColor = json.optString("textColor", dialogTextColor).ifBlank { dialogTextColor }
      dialogLogoUrl = json.optString("logoUrl", dialogLogoUrl)
    } catch (e: Exception) {
      Log.w(TAG, "Exit dialog config parse error: ${e.message}")
    }
  }

  private fun normalizeUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var clean = url.trim()
    if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
      clean = "https://$clean"
    }
    return try {
      val parsed = Uri.parse(clean)
      if (parsed.scheme == "http" || parsed.scheme == "https") {
        parsed.toString()
      } else {
        null
      }
    } catch (e: Exception) {
      null
    }
  }

  fun openGoogleAuthUrl(url: String? = null) {
    val targetUrl = url ?: GOOGLE_AUTH_URL
    try {
      val customTabsIntent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
      customTabsIntent.launchUrl(this, Uri.parse(targetUrl))
    } catch (e: Exception) {
      try {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
        startActivity(browserIntent)
      } catch (ex: Exception) {
        Log.e(TAG, "Failed to open Google Auth URL", ex)
      }
    }
  }

  inner class AndroidWebAppInterface {

    @JavascriptInterface
    fun openGoogleAuth(authUrl: String?) {
      runOnUiThread {
        triggerNativeGoogleSignIn(authUrl)
      }
    }

    @JavascriptInterface
    fun triggerGoogleSignIn() {
      runOnUiThread {
        triggerNativeGoogleSignIn()
      }
    }

    @JavascriptInterface
    fun googleSignIn() {
      runOnUiThread {
        triggerNativeGoogleSignIn()
      }
    }

    @JavascriptInterface
    fun launchRealGoogleSignIn() {
      runOnUiThread {
        triggerNativeGoogleSignIn()
      }
    }

    @JavascriptInterface
    fun setUserEmail(email: String?) {
      Log.d(TAG, "User email set: $email")
    }

    @JavascriptInterface
    fun openSocialLink(url: String?) {
      openExternal(url)
    }

    @JavascriptInterface
    fun openExternalBrowser(url: String?) {
      openExternal(url)
    }

    @JavascriptInterface
    fun exitApp() {
      runOnUiThread {
        finishAffinity()
      }
    }

    @JavascriptInterface
    fun getAppVersionCode(): Int {
      return 1
    }

    @JavascriptInterface
    fun getClipboardText(): String {
      return try {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
          clip.getItemAt(0).text?.toString() ?: ""
        } else {
          ""
        }
      } catch (e: Exception) {
        ""
      }
    }

    @JavascriptInterface
    fun getDeviceDetails(): String {
      val json = JSONObject().apply {
        put("deviceName", Build.MODEL)
        put("brand", Build.BRAND)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("release", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("screenResolution", "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
      }
      return json.toString()
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
      runOnUiThread {
        requestNotificationPermissionIfNeeded()
      }
    }

    @JavascriptInterface
    fun onUrlLoaded(url: String?) {
      if (!url.isNullOrBlank()) {
        prefs.edit().putString(CACHE_KEY, url).apply()
      }
    }

    @JavascriptInterface
    fun getCachedUrl(): String? {
      return prefs.getString(CACHE_KEY, null)
    }

    @JavascriptInterface
    fun openExternal(url: String?) {
      if (!url.isNullOrBlank()) {
        runOnUiThread {
          try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
          } catch (e: Exception) {
            Log.e(TAG, "Cannot open external URL: $url", e)
          }
        }
      }
    }

    @JavascriptInterface
    fun shareText(title: String?, text: String?, url: String?) {
      runOnUiThread {
        try {
          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title ?: "")
            val content = buildString {
              if (!text.isNullOrBlank()) append(text).append(" ")
              if (!url.isNullOrBlank()) append(url)
            }
            putExtra(Intent.EXTRA_TEXT, content.trim())
          }
          startActivity(Intent.createChooser(shareIntent, title ?: "Share"))
        } catch (e: Exception) {
          Log.e(TAG, "Share failed", e)
        }
      }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String?) {
      if (!text.isNullOrBlank()) {
        runOnUiThread {
          val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          val clip = ClipData.newPlainText("Copied Text", text)
          clipboard.setPrimaryClip(clip)
        }
      }
    }

    @JavascriptInterface
    fun openAdminPanel() {
      runOnUiThread {
        webView.loadUrl("file:///android_asset/admin.html")
      }
    }

    @JavascriptInterface
    fun postMessage(message: String?) {
      if (message.isNullOrBlank()) return
      try {
        val json = JSONObject(message)
        val action = json.optString("action")
        if (action == "open_google_auth" || action == "google_login" || action == "google_sign_in") {
          val authUrl = json.optString("url", GOOGLE_AUTH_URL)
          runOnUiThread {
            triggerNativeGoogleSignIn(authUrl)
          }
        } else if (action == "open_admin" || action == "admin_panel") {
          runOnUiThread {
            webView.loadUrl("file:///android_asset/admin.html")
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Message parsing notice: $message", e)
      }
    }
  }

  inner class AppWebChromeClient : WebChromeClient() {

    override fun onPermissionRequest(request: PermissionRequest) {
      runOnUiThread {
        val requestedResources = request.resources
        val permissionsToRequest = mutableListOf<String>()

        for (resource in requestedResources) {
          when (resource) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
              if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
              ) {
                permissionsToRequest.add(Manifest.permission.CAMERA)
              }
            }
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
              if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
              ) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
              }
            }
          }
        }

        if (permissionsToRequest.isNotEmpty()) {
          pendingPermissionRequest = request
          permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
          request.grant(requestedResources)
        }
      }
    }

    override fun onGeolocationPermissionsShowPrompt(
      origin: String?,
      callback: GeolocationPermissions.Callback?
    ) {
      val fineGranted = ContextCompat.checkSelfPermission(
        this@MainActivity,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED

      val coarseGranted = ContextCompat.checkSelfPermission(
        this@MainActivity,
        Manifest.permission.ACCESS_COARSE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED

      if (fineGranted || coarseGranted) {
        callback?.invoke(origin, true, false)
      } else {
        pendingGeoCallback = callback
        pendingGeoOrigin = origin
        permissionLauncher.launch(
          arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
          )
        )
      }
    }

    override fun onShowFileChooser(
      webView: WebView?,
      filePathCallback: ValueCallback<Array<Uri>>?,
      fileChooserParams: FileChooserParams?
    ): Boolean {
      this@MainActivity.filePathCallback?.onReceiveValue(null)
      this@MainActivity.filePathCallback = filePathCallback

      val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
      }

      try {
        fileChooserLauncher.launch(intent)
        return true
      } catch (e: ActivityNotFoundException) {
        val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
        }
        return try {
          fileChooserLauncher.launch(fallbackIntent)
          true
        } catch (ex: Exception) {
          this@MainActivity.filePathCallback?.onReceiveValue(null)
          this@MainActivity.filePathCallback = null
          false
        }
      }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
      if (customView != null) {
        callback?.onCustomViewHidden()
        return
      }

      customView = view
      customViewCallback = callback
      customViewContainer.addView(view)
      customViewContainer.visibility = View.VISIBLE
      webView.visibility = View.GONE

      val controller = WindowCompat.getInsetsController(window, window.decorView)
      controller.hide(WindowInsetsCompat.Type.systemBars())
      controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onHideCustomView() {
      if (customView == null) return

      customViewContainer.visibility = View.GONE
      customViewContainer.removeView(customView)
      customView = null
      customViewCallback?.onCustomViewHidden()
      customViewCallback = null
      webView.visibility = View.VISIBLE

      val controller = WindowCompat.getInsetsController(window, window.decorView)
      controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun getDefaultVideoPoster(): Bitmap? {
      return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    override fun onCreateWindow(
      view: WebView?,
      isDialog: Boolean,
      isUserGesture: Boolean,
      resultMsg: Message?
    ): Boolean {
      // Seamlessly redirect any popup window requests into the current webview
      val hrefMsg = view?.handler?.obtainMessage()
      view?.requestFocusNodeHref(hrefMsg)
      val url = hrefMsg?.data?.getString("url")
      if (!url.isNullOrBlank()) {
        if (url.contains("google-auth.html", ignoreCase = true) ||
          url.contains("accounts.google.com", ignoreCase = true)
        ) {
          triggerNativeGoogleSignIn(url)
        } else {
          view.loadUrl(url)
        }
        return true
      }
      return false
    }
  }

  inner class AppWebViewClient : WebViewClient() {

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
      handler?.proceed()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
      val url = request?.url?.toString() ?: return false

      if (url.contains("google-auth.html", ignoreCase = true) ||
        url.contains("accounts.google.com/o/oauth2", ignoreCase = true)
      ) {
        triggerNativeGoogleSignIn(url)
        return true
      }

      val scheme = request.url.scheme?.lowercase() ?: ""

      if (scheme != "http" && scheme != "https" && scheme != "file" && scheme != "about") {
        try {
          val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
          if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            return true
          }
        } catch (e: Exception) {
          try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(fallbackIntent)
            return true
          } catch (ex: Exception) {
            Log.e(TAG, "Cannot handle custom scheme URL: $url", ex)
          }
        }
        return true
      }

      return false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
      super.onPageFinished(view, url)

      // Fade out loading transition overlay smoothly
      if (webViewLoadingOverlay.visibility == View.VISIBLE) {
        webViewLoadingOverlay.animate()
          .alpha(0f)
          .setDuration(220)
          .withEndAction {
            webViewLoadingOverlay.visibility = View.GONE
          }
          .start()
      }

      if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
        prefs.edit().putString(CACHE_KEY, url).apply()
      }

      // Inject Firebase Configuration and Native Auth Bridge
      val injection = """
        (function() {
          // Provide global Firebase configuration to window
          window.ESP_FIREBASE_CONFIG = {
            apiKey: "AIzaSyCXcnncDuQ9N-Sacvb7hNdMXKuZaQLn-vE",
            authDomain: "espopup-bd.firebaseapp.com",
            databaseURL: "https://espopup-bd-default-rtdb.firebaseio.com",
            projectId: "espopup-bd",
            storageBucket: "espopup-bd.firebasestorage.app",
            messagingSenderId: "1007807073745",
            appId: "1:1007807073745:web:b6cdf9d4fa53e5834aa4f0",
            measurementId: "G-R5YJZRF9PY"
          };

          if (!window._esp_msg_bridge_installed) {
            window._esp_msg_bridge_installed = true;
            
            window.addEventListener('message', function(event) {
              if (event.data && (event.data.action === 'open_google_auth' || event.data.action === 'google_login' || event.data.action === 'google_sign_in')) {
                if (window.AndroidApp && typeof window.AndroidApp.googleSignIn === 'function') {
                  window.AndroidApp.googleSignIn();
                } else if (window.AndroidBridge && typeof window.AndroidBridge.triggerGoogleSignIn === 'function') {
                  window.AndroidBridge.triggerGoogleSignIn();
                }
              }
            });

            // Target specifically Google Sign-in action buttons without interfering with other form buttons
            document.addEventListener('click', function(e) {
              const googleBtn = e.target.closest('[data-google-auth], [onclick*="handleGoogleAuth"], [onclick*="triggerRealFirebaseGoogleSignIn"], #google-login-btn, .google-signin-btn, a[href*="google-auth"], button[id*="google"]');
              if (googleBtn) {
                if (window.AndroidApp && typeof window.AndroidApp.googleSignIn === 'function') {
                  e.preventDefault();
                  e.stopPropagation();
                  window.AndroidApp.googleSignIn();
                } else if (window.AndroidBridge && typeof window.AndroidBridge.triggerGoogleSignIn === 'function') {
                  e.preventDefault();
                  e.stopPropagation();
                  window.AndroidBridge.triggerGoogleSignIn();
                }
              }
            }, true);
          }
        })();
      """.trimIndent()
      view?.evaluateJavascript(injection, null)
    }

    override fun onReceivedError(
      view: WebView?,
      request: WebResourceRequest?,
      error: WebResourceError?
    ) {
      super.onReceivedError(view, request, error)
      Log.w(TAG, "WebView error on ${request?.url}: ${error?.description}")
      if (request?.isForMainFrame == true) {
        val cached = prefs.getString(CACHE_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        if (request.url.toString() != cached) {
          webView.loadUrl(cached)
        }
      }
    }

    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
      Log.w(TAG, "Render process gone; recovering WebView gracefully.")
      view?.destroy()
      setupViews()
      val targetUrl = prefs.getString(CACHE_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
      webView.loadUrl(targetUrl)
      return true
    }

    override fun onSafeBrowsingHit(
      view: WebView?,
      request: WebResourceRequest?,
      threatType: Int,
      callback: android.webkit.SafeBrowsingResponse?
    ) {
      callback?.proceed(false)
    }
  }

  override fun onPause() {
    super.onPause()
    webView.onPause()
  }

  override fun onResume() {
    super.onResume()
    webView.onResume()
    // Guard against blank screen after returning to the app
    val currentUrl = webView.url
    if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
      val fallback = prefs.getString(CACHE_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
      webView.loadUrl(fallback)
    }
  }

  override fun onDestroy() {
    if (customView != null) {
      webView.webChromeClient?.onHideCustomView()
    }
    webView.destroy()
    super.onDestroy()
  }
}
