package com.example

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseInitHelper.initialize(this)
        NotificationHelper.createNotificationChannel(this)

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val jsInterface = WebAppInterface(this@MainActivity)
            addJavascriptInterface(jsInterface, "AndroidApp")
            addJavascriptInterface(jsInterface, "Android")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    return if (url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://")) {
                        false
                    } else {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        } catch (_: Exception) { }
                        true
                    }
                }
            }

            webChromeClient = WebChromeClient()

            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("handleNativeBack();") { result ->
                    if (result == null || result == "null" || result == "false") {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                }
            }
        })
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun showExitDialog() {
            runOnUiThread {
                AlertDialog.Builder(context)
                    .setTitle("Esp TopUp")
                    .setMessage("আপনি কি অ্যাপ থেকে বের হয়ে যেতে চান?")
                    .setPositiveButton("Exit") { dialog, _ ->
                        dialog.dismiss()
                        finish()
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }

        @JavascriptInterface
        fun openSocialLink(url: String?) {
            if (url.isNullOrEmpty()) return
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun getDeviceAccounts(): String {
            return "[]"
        }

        @JavascriptInterface
        fun setUserEmail(email: String) {
            // Handle email setter
        }

        @JavascriptInterface
        fun getDeviceDetails(): String {
            val json = JSONObject().apply {
                put("deviceName", Build.MODEL)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("release", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("screenResolution", "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
            }
            return json.toString()
        }

        @JavascriptInterface
        fun launchRealGoogleSignIn() {
            // Google sign-in trigger
        }
    }
}
