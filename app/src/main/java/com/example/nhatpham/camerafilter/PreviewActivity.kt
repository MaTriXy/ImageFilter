package com.example.nhatpham.camerafilter

import android.Manifest
import android.app.Activity
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.WindowManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.wysaid.common.Common
import org.wysaid.nativePort.CGENativeLibrary
import java.io.*
import kotlin.math.absoluteValue

class PreviewActivity : AppCompatActivity() {

    private lateinit var mainViewModel: MainViewModel
    private val cameraFragment by lazy { CameraFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_preview)

        CGENativeLibrary.setLoadImageCallback(object : CGENativeLibrary.LoadImageCallback {
            override fun loadImage(name: String, arg: Any?): Bitmap? {
                Log.i(Common.LOG_TAG, "Loading file: $name")
                val am = assets
                val inputStream: InputStream
                try {
                    inputStream = am.open(name)
                } catch (e: IOException) {
                    Log.e(Common.LOG_TAG, "Can not open file $name")
                    return null
                }
                return BitmapFactory.decodeStream(inputStream)
            }

            override fun loadImageOK(bmp: Bitmap, arg: Any?) {
                Log.i(Common.LOG_TAG, "Loading bitmap over, you can choose to recycle or cache")
                bmp.recycle()
            }
        }, null)

        mainViewModel = ViewModelProviders.of(this).get(MainViewModel::class.java)
        mainViewModel.openPhotoPreviewEvent.observe(this, Observer { photoUri ->
            if (photoUri != null) {
                supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, PhotoPreviewFragment.newInstance(photoUri))
                        .addToBackStack(null)
                        .commit()
            }
        })
        mainViewModel.openVideoPreviewEvent.observe(this, Observer { videoUri ->
            if (videoUri != null) {
                supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, VideoPreviewFragment.newInstance(videoUri))
                        .addToBackStack(null)
                        .commit()
            }
        })
        mainViewModel.openGalleryEvent.observe(this, Observer {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, GalleryFragment())
                    .addToBackStack(null)
                    .commit()
        })
        mainViewModel.doneEditEvent.observe(this, Observer {
            setResult(Activity.RESULT_OK, Intent().apply { data = it })
            finish()
        })

        val am = assets
        val inputStream: InputStream
        try {
            inputStream = am.open("filters.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val builder = StringBuilder()
            reader.use { bufferedReader ->
                while (true) {
                    val line = bufferedReader.readLine()
                    if(line != null) {
                        builder.append(line)
                    } else {
                        break
                    }
                }
            }
            EFFECT_CONFIGS.addAll(Gson().fromJson<ArrayList<Config>>(builder.toString(), object : TypeToken<ArrayList<Config>>() {}.type))
        } catch (e: IOException) {
            Log.e(Common.LOG_TAG, "Can not open file filters.json")
        }
        if (!checkToRequestPermissions()) {
            if (savedInstanceState == null) {
                showCameraFragment()
            }
        }
    }

    private fun checkToRequestPermissions(): Boolean {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return requestPermissions(this, REQUEST_USE_CAMERA_PERMISSIONS, *permissions)
    }

    private fun showCameraFragment() {
        supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, cameraFragment)
                .commitAllowingStateLoss()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_USE_CAMERA_PERMISSIONS -> {
                val somePermissionsNotGranted = grantResults.any { it.absoluteValue != PackageManager.PERMISSION_GRANTED }
                if (!somePermissionsNotGranted) {
                    showCameraFragment()
                } else {
                    finish()
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    companion object {
        const val REQUEST_USE_CAMERA_PERMISSIONS = 1

        fun newIntent(context: Context, photoUri: String, isVideo: Boolean) = Intent(context, PreviewActivity::class.java)
                .apply {
                    putExtra("nhat", photoUri)
                }
    }
}
