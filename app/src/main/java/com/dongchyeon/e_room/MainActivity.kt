package com.dongchyeon.e_room

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dongchyeon.e_room.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

const val LOCATION_PERMISSION_REQUEST_CODE = 1001
const val STORAGE_PERMISSION_REQUEST_CODE = 1002

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var cameraImagePath = ""
    private var webViewImageUploadCallback: ValueCallback<Array<Uri>>? = null

    val chooseImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = result.data

            if (intent == null) {
                val results = arrayOf(Uri.fromFile(File(cameraImagePath)))
                webViewImageUploadCallback!!.onReceiveValue(results)
            } else {
                val results = intent.data!!
                webViewImageUploadCallback!!.onReceiveValue(arrayOf(results))
            }
        } else {
            webViewImageUploadCallback!!.onReceiveValue(null)
            webViewImageUploadCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        webView = binding.webview
        if (isNetworkConnected()) {

            with(webView) {
                loadUrl(BuildConfig.BASE_URL)

                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        Log.e("e-room", "Http Error : $errorResponse")
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        Log.e("e-room", "SSL Error : $error")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        Log.e("e-room", "Error : $error")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        super.onShowFileChooser(webView, filePathCallback, fileChooserParams)

                        if (!isStoragePermissionGranted()) {
                            requestStoragePermission()
                        }
                        try {
                            webViewImageUploadCallback = filePathCallback!!
                            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
                                takePictureIntent.resolveActivity(packageManager)?.also {
                                    val photoFile: File? = try {
                                        createImageFile()
                                    } catch (ex: IOException) {
                                        null
                                    }

                                    photoFile?.also {
                                        val photoURI: Uri = FileProvider.getUriForFile(
                                            applicationContext,
                                            BuildConfig.APPLICATION_ID + ".fileProvider",
                                            it
                                        )
                                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                                    }
                                }
                            }
                            val contentSelectionIntent = Intent(
                                Intent.ACTION_PICK,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            )
                            contentSelectionIntent.type = "image/*"

                            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                                putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                                putExtra(Intent.EXTRA_TITLE, "사용할 앱을 선택해주세요.")
                                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
                            }
                            chooseImageLauncher.launch(chooserIntent)
                        } catch (e: Exception) {
                            Log.d("e-room", e.printStackTrace().toString())
                        }

                        return true
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?
                    ) {
                        super.onGeolocationPermissionsShowPrompt(origin, callback)
                        callback?.invoke(origin, true, false)
                        requestLocationPermission()
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.apply {
                            Log.d(
                                "e-room",
                                "${message()} -- From line ${lineNumber()} of ${sourceId()}"
                            )
                        }
                        return true
                    }
                }

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = System.getProperty("http.agent")?.plus(applicationContext.packageName)

                settings.setGeolocationEnabled(true)
            }

            onBackPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
            onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        } else {
            showSnackBar(binding.root, "인터넷이 연결되어 있지 않아 앱이 종료됩니다.")
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 1000)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            cameraImagePath = absolutePath
        }
    }

    private fun isStoragePermissionGranted(): Boolean {
        val readStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        return readStoragePermission == PackageManager.PERMISSION_GRANTED && cameraPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationPermissionGranted(): Boolean {
        val coarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        return coarseLocationPermission == PackageManager.PERMISSION_GRANTED && fineLocationPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        val permissions = arrayOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }, Manifest.permission.CAMERA
        )
        ActivityCompat.requestPermissions(this, permissions, STORAGE_PERMISSION_REQUEST_CODE)
    }

    private fun requestLocationPermission() {
        val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        ActivityCompat.requestPermissions(this, permissions, LOCATION_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> handlePermissionResult(
                isLocationPermissionGranted(),
                "위치 권한을 허용해야 현재 위치를 알 수 있습니다.",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            )
            STORAGE_PERMISSION_REQUEST_CODE -> handlePermissionResult(
                isStoragePermissionGranted(),
                "저장소 권한을 허용해야 사진을 선택할 수 있습니다.",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            )
        }
    }

    private fun handlePermissionResult(isGranted: Boolean, message: String, settingsAction: String) {
        if (!isGranted) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

            try {
                val intent = Intent(settingsAction).setData(Uri.parse("package:$packageName"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                e.printStackTrace()
                val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                startActivity(intent)
            }
        }
    }

    private fun showSnackBar(rootView: View, message: String) {
        val snackBar = Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
        snackBar.show()
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkCapabilities = connectivityManager.activeNetwork ?: return false
        val actNw =
            connectivityManager.getNetworkCapabilities(networkCapabilities) ?: return false

        return actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}