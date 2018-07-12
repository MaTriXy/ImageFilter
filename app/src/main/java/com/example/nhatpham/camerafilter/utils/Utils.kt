package com.example.nhatpham.camerafilter.utils

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.DisplayMetrics
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.*
import android.os.Environment
import android.provider.MediaStore
import com.example.nhatpham.camerafilter.models.Config
import java.io.File
import java.nio.file.Files.exists
import kotlin.collections.ArrayList

const val APP_NAME = "Mingle"

internal val NONE_CONFIG = Config("None", "")

internal val EFFECT_CONFIGS = ArrayList<Config>()

internal fun requestPermissions(activity: Activity, requestCode: Int, vararg permissions: String): Boolean {
    val notGrantedPermissions = ArrayList<String>()
    for (permission in permissions) {
        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            notGrantedPermissions.add(permission)
        }
    }
    if (!notGrantedPermissions.isEmpty()) {
        val notGrantedPermissionsArray = arrayOf<String>()
        ActivityCompat.requestPermissions(activity, notGrantedPermissions.toArray(notGrantedPermissionsArray), requestCode)
        return true
    }
    return false
}

internal fun convertDpToPixel(context: Context, dp: Float): Int {
    val displayMetrics = context.resources.displayMetrics
    return Math.round(dp * (displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()))
}

internal fun convertPixelsToDp(context: Context, px: Float): Int {
    val displayMetrics = context.resources.displayMetrics
    return Math.round(px / (displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()))
}

internal fun getThumbnail(context: Context, videoUri: Uri): Bitmap? {
    var bitmap: Bitmap? = null
    var mediaMetadataRetriever = MediaMetadataRetriever()
    try {
        mediaMetadataRetriever.setDataSource(context, videoUri)
        bitmap = mediaMetadataRetriever.frameAtTime
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        if (mediaMetadataRetriever != null)
            mediaMetadataRetriever.release()
    }
    return bitmap
}

/* Checks if external storage is available for read and write */
internal fun isExternalStorageWritable() = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

/* Checks if external storage is available to at least read */
internal fun isExternalStorageReadable() = Environment.getExternalStorageState() in
        setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

internal fun getPath(): String {
    val path = "${Environment.getExternalStorageDirectory().absolutePath}/$APP_NAME"
    File(path).run {
        if (!exists())
            mkdirs()
    }
    return path
}

internal fun getInternalPath(context: Context): String {
    val file = context.getDir("$APP_NAME-camera", Context.MODE_PRIVATE)
    return file.apply {
        if(!exists()) {
            mkdirs()
        }
    }.absolutePath
}

internal fun generateImageFileName() = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Calendar.getInstance().time)}.jpg"

internal fun generateVideoFileName() = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Calendar.getInstance().time)}.mp4"

internal fun isMediaStoreUri(uri: Uri?): Boolean {
    return (uri != null && ContentResolver.SCHEME_CONTENT == uri.scheme
            && MediaStore.AUTHORITY == uri.authority)
}

internal fun isVideoUri(uri: Uri?): Boolean = uri != null && uri.pathSegments.contains("video")

internal fun isMediaStoreVideoUri(uri: Uri?): Boolean = isMediaStoreUri(uri) && isVideoUri(uri)

internal fun isMediaStoreImageUri(uri: Uri?): Boolean = isMediaStoreUri(uri) && !isVideoUri(uri)

internal fun isFileUri(uri: Uri?): Boolean = uri != null && ContentResolver.SCHEME_FILE == uri.scheme

internal fun reScanFile(context: Context, fileUri: Uri) {
    context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
}