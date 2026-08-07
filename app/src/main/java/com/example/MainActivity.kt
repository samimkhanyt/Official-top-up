package com.example

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun exitApp() {
            activity.runOnUiThread {
                activity.finish()
            }
        }

        @JavascriptInterface
        fun showExitDialog() {
            activity.runOnUiThread {
                activity.displayExitConfirmationDialog()
            }
        }

        @JavascriptInterface
        fun googleSignIn() {
            activity.runOnUiThread {
                activity.promptGoogleAccountSelection()
            }
        }

        @JavascriptInterface
        fun openSocialLink(url: String) {
            activity.runOnUiThread {
                activity.handleUrlScheme(url)
            }
        }

        @JavascriptInterface
        fun setUserEmail(email: String) {
            activity.saveUserEmail(email)
        }

        @JavascriptInterface
        fun getDeviceAccounts(): String {
            return try {
                val accountManager = AccountManager.get(activity)
                val accounts = accountManager.getAccountsByType("com.google")
                val jsonArray = org.json.JSONArray()
                val seen = mutableSetOf<String>()
                for (acc in accounts) {
                    val email = acc.name?.lowercase()?.trim() ?: continue
                    if (email.contains("@") && seen.add(email)) {
                        val obj = org.json.JSONObject()
                        obj.put("email", email)
                        val name = email.split("@").firstOrNull()?.replace(".", " ")
                            ?.split(" ")?.joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } } ?: "User"
                        obj.put("name", name)
                        jsonArray.put(obj)
                    }
                }
                jsonArray.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                "[]"
            }
        }

        @JavascriptInterface
        fun launchRealGoogleSignIn() {
            activity.runOnUiThread {
                activity.launchNativeAccountPicker()
            }
        }

        @JavascriptInterface
        fun getDeviceDetails(): String {
            return try {
                val obj = org.json.JSONObject()
                obj.put("model", android.os.Build.MODEL ?: "Android Device")
                obj.put("brand", android.os.Build.BRAND ?: "Unknown")
                obj.put("manufacturer", android.os.Build.MANUFACTURER ?: "Unknown")
                obj.put("device", android.os.Build.DEVICE ?: "Unknown")
                obj.put("sdk", android.os.Build.VERSION.SDK_INT)
                obj.put("release", android.os.Build.VERSION.RELEASE ?: "")
                val displayMetrics = activity.resources.displayMetrics
                obj.put("screenResolution", "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
                obj.put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
                obj.toString()
            } catch (e: Exception) {
                "{}"
            }
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            val intentData = result.data
            val results = if (result.resultCode == RESULT_OK && intentData != null) {
                val dataString = intentData.dataString
                if (dataString != null) {
                    arrayOf(Uri.parse(dataString))
                } else null
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrEmpty()) {
                val displayName = accountName.split("@").firstOrNull()?.replace(".", " ") ?: "User"
                notifyJsGoogleSignIn(accountName, displayName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize Firebase safely
        initFirebaseSafely()

        // 2. Initialize Notification Channel safely
        try {
            NotificationHelper.createNotificationChannel(this)
            NotificationBackgroundService.startService(this) // Stops any existing foreground service
        } catch (e: Throwable) {
            Log.e("MainActivity", "Error creating notification channel: ${e.message}")
        }

        // 3. Prompt for Push Notification Permission
        checkAndRequestNotificationPermission()
        checkAndRequestAccountPermissions()

        // 4. Setup WebView
        setupWebView()

        // 5. Handle back press seamlessly with WebView JS section stack
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript("if (typeof handleNativeBack === 'function') { handleNativeBack(); } else { 'false'; }") { result ->
                        val cleanResult = result?.replace("\"", "")
                        if (cleanResult == "false" || cleanResult == null || cleanResult == "null") {
                            if (webView.canGoBack()) {
                                webView.goBack()
                            } else {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkAndRequestAccountPermissions() {
        try {
            val permissionsNeeded = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.GET_ACCOUNTS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
            }
            if (permissionsNeeded.isNotEmpty()) {
                requestMultiplePermissionsLauncher.launch(permissionsNeeded.toTypedArray())
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking account permissions: ${e.message}")
        }
    }

    private fun initFirebaseSafely() {
        try {
            FirebaseInitHelper.ensureInitialized(this)

            try {
                FirebaseMessaging.getInstance().subscribeToTopic("broadcast")
                FirebaseMessaging.getInstance().subscribeToTopic("notifications_broadcast")
                FirebaseMessaging.getInstance().subscribeToTopic("live_notifications")
                FirebaseMessaging.getInstance().subscribeToTopic("all")
                FirebaseMessaging.getInstance().subscribeToTopic("esp_topup")
            } catch (e: Throwable) {
                Log.e("MainActivity", "FirebaseMessaging subscribe error: ${e.message}")
            }

            FirebaseBroadcastListener(applicationContext).startListening()
            syncFcmTokenWithFirebase()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Firebase initialization safely caught exception: ${t.message}", t)
        }
    }

    fun syncFcmTokenWithFirebase(email: String? = null) {
        try {
            FirebaseInitHelper.ensureInitialized(this)
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w("MainActivity", "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                if (!token.isNullOrEmpty()) {
                    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    prefs.edit().putString("fcm_token", token).apply()

                    val savedEmail = (email ?: prefs.getString("user_email", ""))?.lowercase()?.trim() ?: ""
                    val savedUid = prefs.getString("user_uid", "")?.lowercase()?.trim() ?: ""

                    if (savedUid.isNotEmpty()) {
                        val sanitizedUid = savedUid.replace(Regex("[.#$\\[\\]]"), "_")
                        FirebaseMessaging.getInstance().subscribeToTopic("user_$sanitizedUid")
                    }

                    if (savedEmail.isNotEmpty()) {
                        val sanitizedEmail = savedEmail.replace(Regex("[.#$\\[\\]]"), "_")
                        FirebaseMessaging.getInstance().subscribeToTopic("user_$sanitizedEmail")
                    }

                    try {
                        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://samim-firebase-default-rtdb.firebaseio.com")
                        val tokenData = HashMap<String, Any>()
                        tokenData["token"] = token
                        tokenData["email"] = savedEmail
                        tokenData["uid"] = savedUid
                        tokenData["updatedAt"] = System.currentTimeMillis()

                        val sanitizedToken = token.replace(Regex("[.#$\\[\\]]"), "_")
                        db.getReference("fcm_tokens").child(sanitizedToken).setValue(tokenData)

                        if (savedEmail.isNotEmpty()) {
                            val emailKey = savedEmail.replace(Regex("[.#$\\[\\]]"), "_")
                            db.getReference("user_tokens").child(emailKey).setValue(tokenData)
                        }
                        if (savedUid.isNotEmpty()) {
                            db.getReference("users").child(savedUid).child("fcmToken").setValue(token)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error uploading FCM token: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting FCM token: ${e.message}")
        }
    }

    fun displayExitConfirmationDialog() {
        val builder = AlertDialog.Builder(this)
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 50, 60, 50)
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 32f
            }
        }

        // App Logo Container (Square with Rounded Corners and Border)
        val logoContainer = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 32f
                setStroke(2, Color.parseColor("#e2e8f0"))
            }
            layoutParams = LinearLayout.LayoutParams(160, 160).apply {
                bottomMargin = 24
            }
            setPadding(12, 12, 12, 12)
        }
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.app_launcher_logo_1785854861781)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        logoContainer.addView(logoView)
        container.addView(logoContainer)

        // Title
        val titleView = TextView(this).apply {
            text = "Esp TopUp"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1e293b"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
        }
        container.addView(titleView)

        // Subtitle / Message
        val messageView = TextView(this).apply {
            text = "আপনি কি অ্যাপ থেকে বের হয়ে যেতে চান?"
            textSize = 14f
            setTextColor(Color.parseColor("#64748b"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 40
            }
        }
        container.addView(messageView)

        // Button Container
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        var dialog: AlertDialog? = null

        // Close Button
        val closeBtn = TextView(this).apply {
            text = "Close"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#f1f5f9"))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = 12
            }
            setOnClickListener {
                dialog?.dismiss()
            }
        }
        buttonLayout.addView(closeBtn)

        // Exit Button
        val exitBtn = TextView(this).apply {
            text = "Exit"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#ef4444"))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = 12
            }
            setOnClickListener {
                dialog?.dismiss()
                finish()
            }
        }
        buttonLayout.addView(exitBtn)

        container.addView(buttonLayout)

        builder.setView(container)
        dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    fun promptGoogleAccountSelection() {
        try {
            val accountManager = AccountManager.get(this)
            val accounts = accountManager.getAccountsByType("com.google")

            if (accounts.isNotEmpty()) {
                val accountEmails = accounts.map { it.name }.toTypedArray()
                showAccountChooserDialog(accountEmails)
            } else {
                launchNativeAccountPicker()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching Google accounts: ${e.message}")
            launchNativeAccountPicker()
        }
    }

    fun launchNativeAccountPicker() {
        try {
            val intent = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"),
                true, null, null, null, null
            )
            accountPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "AccountPicker intent failed: ${e.message}")
            showCustomEmailInputDialog()
        }
    }

    private fun showAccountChooserDialog(emails: Array<String>) {
        val builder = AlertDialog.Builder(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121318"))
                cornerRadius = 32f
                setStroke(2, Color.parseColor("#27272a"))
            }
        }

        // Header: Google Sign-In Branding
        val googleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }

        val googleIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(56, 56).apply { rightMargin = 20 }
        }
        googleHeader.addView(googleIcon)

        val googleTitle = TextView(this).apply {
            text = "Sign in with Google"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#f49e0b"))
        }
        googleHeader.addView(googleTitle)
        container.addView(googleHeader)

        // Choose an account title
        val chooseTitle = TextView(this).apply {
            text = "Choose an account"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6)
        }
        container.addView(chooseTitle)

        // Subtitle: to continue to Esp TopUp
        val continueSubtitle = TextView(this).apply {
            text = "to continue to Esp TopUp"
            textSize = 14f
            setTextColor(Color.parseColor("#a1a1aa"))
            setPadding(0, 0, 0, 28)
        }
        container.addView(continueSubtitle)

        var dialog: AlertDialog? = null

        val avatarColors = arrayOf("#3b82f6", "#ef4444", "#10b981", "#f59e0b", "#8b5cf6", "#ec4899")

        // ScrollView for accounts list so all accounts are fully scrollable
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = 16 }
            isScrollbarFadingEnabled = false
        }

        val scrollContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Account list
        for (email in emails) {
            val displayName = email.split("@").firstOrNull()?.replace(".", " ")?.split(" ")
                ?.joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } } ?: "User"
            val initial = displayName.firstOrNull()?.uppercase() ?: "U"
            val colorIndex = Math.abs(email.hashCode()) % avatarColors.size
            val bgHex = avatarColors[colorIndex]

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 20, 24, 20)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1f2128"))
                    cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    notifyJsGoogleSignIn(email, displayName)
                    dialog?.dismiss()
                }
            }

            // Circle avatar
            val avatarView = TextView(this).apply {
                text = initial
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgHex))
                    shape = GradientDrawable.OVAL
                }
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { rightMargin = 20 }
            }
            row.addView(avatarView)

            // Name & Email
            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(this).apply {
                text = displayName
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            val emailView = TextView(this).apply {
                text = email
                textSize = 12f
                setTextColor(Color.parseColor("#a1a1aa"))
            }
            textLayout.addView(nameView)
            textLayout.addView(emailView)
            row.addView(textLayout)

            scrollContainer.addView(row)
        }

        scrollView.addView(scrollContainer)
        container.addView(scrollView)

        // "Use another account" button
        val useAnotherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181920"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#3f3f46"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }

            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog?.dismiss()
                launchNativeAccountPicker()
            }
        }

        val plusAvatar = TextView(this).apply {
            text = "+"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#f49e0b"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#27272a"))
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { rightMargin = 20 }
        }
        val useAnotherText = TextView(this).apply {
            text = "Use another account"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        useAnotherRow.addView(plusAvatar)
        useAnotherRow.addView(useAnotherText)
        container.addView(useAnotherRow)

        // Footer note
        val footerText = TextView(this).apply {
            text = "Before using this app, you can review Esp TopUp's Privacy Policy and Terms of Service."
            textSize = 11f
            setTextColor(Color.parseColor("#71717a"))
            setPadding(4, 4, 4, 0)
        }
        container.addView(footerText)

        builder.setView(container)
        dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showCustomEmailInputDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Google Sign-In")
        builder.setMessage("Enter your Gmail address:")

        val input = android.widget.EditText(this).apply {
            hint = "user@gmail.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        builder.setView(input)

        builder.setPositiveButton("Continue") { dialog, _ ->
            val email = input.text.toString().trim()
            if (email.isNotEmpty()) {
                val displayName = email.split("@").firstOrNull()?.replace(".", " ") ?: "User"
                notifyJsGoogleSignIn(email, displayName)
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    fun saveUserEmail(email: String) {
        val cleanEmail = email.lowercase().trim()
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putString("user_email", cleanEmail).apply()
        syncFcmTokenWithFirebase(cleanEmail)
    }

    private fun notifyJsGoogleSignIn(email: String, name: String) {
        saveUserEmail(email)
        if (::webView.isInitialized) {
            val safeEmail = email.replace("'", "\\'")
            val safeName = name.replace("'", "\\'")
            webView.post {
                webView.evaluateJavascript("if (typeof onGoogleSignInSelected === 'function') { onGoogleSignInSelected('$safeEmail', '$safeName'); }", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            webView = WebView(this)
            setContentView(webView)

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.overScrollMode = View.OVER_SCROLL_NEVER
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false

            WebView.setWebContentsDebuggingEnabled(false)
            webView.setOnLongClickListener { true }
            webView.isLongClickable = false
            webView.isHapticFeedbackEnabled = false

            val settings: WebSettings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            settings.userAgentString = settings.userAgentString + " EspTopUpApp/1.0"

            webView.addJavascriptInterface(WebAppInterface(this), "AndroidApp")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    webView.evaluateJavascript("(function() { return localStorage.getItem('esp_logged_email') || ''; })()") { result ->
                        if (!result.isNullOrEmpty() && result != "null") {
                            val cleanEmail = result.replace("\"", "").trim()
                            if (cleanEmail.isNotEmpty()) {
                                saveUserEmail(cleanEmail)
                            }
                        }
                    }
                    webView.evaluateJavascript("(function() { return localStorage.getItem('esp_logged_uid') || ''; })()") { result ->
                        if (!result.isNullOrEmpty() && result != "null") {
                            val cleanUid = result.replace("\"", "").trim()
                            if (cleanUid.isNotEmpty()) {
                                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                                prefs.edit().putString("user_uid", cleanUid.lowercase().trim()).apply()
                            }
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleUrlScheme(url)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    return handleUrlScheme(url)
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                    }

                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        this@MainActivity.filePathCallback = null
                        Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
                        return false
                    }
                    return true
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    val newWebView = WebView(this@MainActivity)
                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            handleUrlScheme(url)
                            return true
                        }
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = newWebView
                    resultMsg?.sendToTarget()
                    return true
                }
            }

            webView.loadUrl("file:///android_asset/index.html")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up WebView: ${e.message}", e)
        }
    }

    private fun extractWhatsAppNumber(url: String): String? {
        try {
            var raw: String? = null
            if (url.contains("wa.me/")) {
                val path = url.substringAfter("wa.me/").substringBefore("?").substringBefore("/")
                raw = path
            } else if (url.contains("phone=")) {
                raw = url.substringAfter("phone=").substringBefore("&")
            } else if (url.contains("send/")) {
                raw = url.substringAfter("send/").substringBefore("?").substringBefore("/")
            }
            if (raw.isNullOrEmpty()) {
                raw = url
            }
            var num = raw.filter { it.isDigit() }
            if (num.startsWith("01")) {
                num = "880" + num.substring(1)
            } else if (num.startsWith("00")) {
                num = num.substring(2)
            }
            return if (num.length >= 7) num else null
        } catch (e: Exception) {
            return null
        }
    }

    fun handleUrlScheme(url: String): Boolean {
        if (url.startsWith("file:///android_asset/") || url == "about:blank") {
            return false
        }

        try {
            var targetUrl = url.trim()
            if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://") &&
                !targetUrl.startsWith("whatsapp:") && !targetUrl.startsWith("tg:") &&
                !targetUrl.startsWith("intent:") && !targetUrl.startsWith("fb:")) {
                if (targetUrl.contains("youtube.com") || targetUrl.contains("youtu.be")) {
                    targetUrl = "https://$targetUrl"
                } else if (targetUrl.length == 11 && !targetUrl.contains(".")) {
                    targetUrl = "https://www.youtube.com/watch?v=$targetUrl"
                } else {
                    targetUrl = "https://$targetUrl"
                }
            }

            val uri = Uri.parse(targetUrl)
            val host = uri.host?.lowercase() ?: ""

            // 1. WhatsApp handling
            if (targetUrl.contains("wa.me") || targetUrl.startsWith("whatsapp:") || host.contains("whatsapp")) {
                val phone = extractWhatsAppNumber(targetUrl)
                if (phone != null) {
                    try {
                        val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$phone"))
                        startActivity(waIntent)
                        return true
                    } catch (e: Exception) {
                        try {
                            val waWebIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone"))
                            startActivity(waWebIntent)
                            return true
                        } catch (e2: Exception) {
                            val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp"))
                            startActivity(playStoreIntent)
                            return true
                        }
                    }
                }
            }

            // 2. Telegram handling
            if (targetUrl.contains("t.me") || targetUrl.startsWith("tg:") || host.contains("telegram")) {
                val cleanTgUrl = if (!targetUrl.startsWith("http") && !targetUrl.startsWith("tg:")) "https://$targetUrl" else targetUrl
                var username = ""
                if (cleanTgUrl.contains("t.me/")) {
                    username = cleanTgUrl.substringAfter("t.me/").substringBefore("?").substringBefore("/").trim()
                }
                if (username.startsWith("@")) username = username.substring(1)

                if (username.isNotEmpty() && !username.contains(".")) {
                    try {
                        val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$username"))
                        startActivity(tgIntent)
                        return true
                    } catch (e: Exception) {
                        // Fallback to web or Play Store
                    }
                }
                return openAppOrPlayStore(cleanTgUrl, "org.telegram.messenger", cleanTgUrl)
            }

            // 3. YouTube handling
            if (targetUrl.contains("youtube.com") || targetUrl.contains("youtu.be")) {
                try {
                    val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                    startActivity(ytIntent)
                    return true
                } catch (e: Exception) {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                        startActivity(browserIntent)
                        return true
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }
            }

            // 4. Facebook handling
            if (targetUrl.contains("facebook.com") || targetUrl.contains("fb.watch") || targetUrl.startsWith("fb:")) {
                try {
                    val fbIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                    startActivity(fbIntent)
                    return true
                } catch (e: Exception) {
                    openAppOrPlayStore(targetUrl, "com.facebook.katana", targetUrl)
                    return true
                }
            }

            // 5. General Intent or URL
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                startActivity(intent)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in handleUrlScheme for $url: ${e.message}", e)
        }
        return false
    }

    private fun openAppOrPlayStore(intentUrl: String, packageName: String, fallbackUrl: String): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(intentUrl))
            intent.setPackage(packageName)
            startActivity(intent)
            return true
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                startActivity(browserIntent)
                return true
            } catch (e2: Exception) {
                try {
                    val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                    startActivity(playStoreIntent)
                    return true
                } catch (e3: Exception) {
                    e3.printStackTrace()
                }
            }
        }
        return false
    }
}
