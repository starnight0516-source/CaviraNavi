package com.cavira.navibox

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cavira.navibox.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var lastKnownLocation: Location? = null
    private var hasInitialCenter = false
    private var is1968Mode = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "請開啟定位權限以顯示車速與即時位置", Toast.LENGTH_LONG).show()
        }
    }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                launchTurnByTurnNavigation(spokenText)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupWebView()
        setupControls()
        checkAndRequestPermissions()

        // 預設載入台灣即時路況地圖，取得 GPS 後會自動對準車輛位置
        loadGoogleTrafficMap(24.8015, 120.9714)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webViewMap.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(true)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url != null && (url.startsWith("intent:") || url.startsWith("google.navigation:"))) {
                        return try {
                            startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME))
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
            }
        }
    }

    private fun setupControls() {
        binding.btnVoiceNav.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出導航目的地")
            }
            try {
                voiceLauncher.launch(intent)
            } catch (_: Exception) {
                // 若機上盒未啟用語音服務，直接開啟 Google Maps 導航頁面
                openGoogleMapsApp()
            }
        }

        binding.btnFindCcs2.setOnClickListener {
            val loc = lastKnownLocation
            val query = Uri.encode("CCS2 快充站")
            val uri = if (loc != null) {
                Uri.parse("geo:${loc.latitude},${loc.longitude}?q=$query")
            } else {
                Uri.parse("geo:0,0?q=$query")
            }
            val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            try {
                startActivity(mapIntent)
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }

        binding.btnSwitchSource.setOnClickListener {
            is1968Mode = !is1968Mode
            if (is1968Mode) {
                binding.tvModeStatus.text = "高公局 1968 國道即時路況"
                binding.btnSwitchSource.text = "🚦 切換 Google 路況"
                binding.webViewMap.loadUrl("https://1968.freeway.gov.tw/")
            } else {
                binding.tvModeStatus.text = "Google 即時路況"
                binding.btnSwitchSource.text = "🚦 切換 1968 路況"
                val loc = lastKnownLocation
                if (loc != null) {
                    loadGoogleTrafficMap(loc.latitude, loc.longitude)
                } else {
                    loadGoogleTrafficMap(24.8015, 120.9714)
                }
            }
        }

        binding.btnRecenter.setOnClickListener {
            val loc = lastKnownLocation
            if (loc != null) {
                if (is1968Mode) {
                    binding.webViewMap.reload()
                } else {
                    loadGoogleTrafficMap(loc.latitude, loc.longitude)
                }
            } else {
                Toast.makeText(this, "正在等待 GPS 定位訊號...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadGoogleTrafficMap(lat: Double, lng: Double) {
        val url = String.format(Locale.US, "https://www.google.com/maps/@%.6f,%.6f,15z/data=!5m1!1e1", lat, lng)
        binding.webViewMap.loadUrl(url)
    }

    private fun checkAndRequestPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastKnownLocation = location

                val speedKmh = (location.speed * 3.6f).roundToInt()
                binding.tvSpeed.text = String.format(Locale.TAIWAN, "%d km/h", speedKmh)

                if (!hasInitialCenter && !is1968Mode) {
                    hasInitialCenter = true
                    loadGoogleTrafficMap(location.latitude, location.longitude)
                }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun launchTurnByTurnNavigation(destination: String) {
        val navUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
        val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(navIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "請先確認路瑞寶 6350 已安裝 Google Maps", Toast.LENGTH_LONG).show()
        }
    }

    private fun openGoogleMapsApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "找不到 Google Maps，請先至 Play 商店安裝", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}
