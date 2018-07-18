package com.example.nhatpham.camerafilter.preview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer
import android.databinding.DataBindingUtil
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.isVisible
import com.example.nhatpham.camerafilter.databinding.FragmentVideoPreviewBinding
import org.wysaid.common.Common
import org.wysaid.view.VideoPlayerGLSurfaceView
import android.os.Handler
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.core.graphics.applyCanvas
import androidx.work.OneTimeWorkRequest
import androidx.work.State
import androidx.work.WorkManager
import androidx.work.WorkStatus
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.example.nhatpham.camerafilter.*
import com.example.nhatpham.camerafilter.jobs.GenerateFilteredVideoWorker
import com.example.nhatpham.camerafilter.models.Config
import com.example.nhatpham.camerafilter.models.Video
import com.example.nhatpham.camerafilter.models.isFromCamera
import com.example.nhatpham.camerafilter.models.isFromGallery
import com.example.nhatpham.camerafilter.utils.*
import org.wysaid.view.ImageGLSurfaceView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit


internal class VideoPreviewFragment : Fragment() {

    private lateinit var mBinding: FragmentVideoPreviewBinding
    private val video: Video? by lazy {
        arguments?.getParcelable(EXTRA_VIDEO) as? Video
    }
    private val videoPathToSave by lazy {
        "${getPath()}/${generateVideoFileName()}"
    }

    private lateinit var mainViewModel: MainViewModel
    private lateinit var videoPreviewViewModel: VideoPreviewViewModel
    private val mainHandler = Handler()
    private var mediaPlayer: MediaPlayer? = null
    private var scheduler = Executors.newSingleThreadScheduledExecutor()
    private var timeRecordingFuture: ScheduledFuture<*>? = null

    private val progressDialogFragment = ProgressDialogFragment()
    private lateinit var previewFiltersAdapter: PreviewFiltersAdapter
    private var currentBitmap: Bitmap? = null
    private val currentConfig
        get() = videoPreviewViewModel.currentConfigLiveData.value ?: NONE_CONFIG
    private var lastIntervalUpdate = 0L
    private var isCompleted = true
    private val playListener = PlayListener()

    private val updateTimeIntervalTask = Runnable {
        val currentPlayer = mediaPlayer
        if (currentPlayer != null && currentPlayer.isPlaying) {
            mBinding.tvRecordingTime.text = DateUtils.formatElapsedTime(TimeUnit.MILLISECONDS.toSeconds(
                    SystemClock.elapsedRealtime() - lastIntervalUpdate))
            mBinding.tvRecordingTime.isVisible = true
        }
    }

    private val playerCallback = object : VideoPlayerGLSurfaceView.PlayerCallback {

        override fun initPlayer(player: MediaPlayer?) {
            player?.setOnBufferingUpdateListener { _, percent ->
                if (percent == 100) {
                    player.setOnBufferingUpdateListener(null)
                }
            }
        }

        override fun playPrepared(player: MediaPlayer?) {
            mediaPlayer = player
            startPlayingVideo()
        }

        override fun playComplete(player: MediaPlayer?) {
            isCompleted = true
            mediaPlayer = player
            cancelScheduledRecordTime()
            mBinding.tvRecordingTime.isVisible = false
            mBinding.videoView.isVisible = false
            videoPreviewViewModel.showThumbnailEvent.value = true
        }

        override fun playFailed(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
            Log.d(Common.LOG_TAG, String.format("Error occured! Stop playing, Err code: %d, %d", what, extra))
            return true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        mBinding = DataBindingUtil.inflate(inflater, R.layout.fragment_video_preview, container, false)
        initialize()
        return mBinding.root
    }

    private fun initialize() {
        mainViewModel = getViewModel(activity!!)
        videoPreviewViewModel = getViewModel(this)

        videoPreviewViewModel.showFiltersEvent.observe(viewLifecycleOwner, Observer { active ->
            showFilters(active ?: false)
        })

        videoPreviewViewModel.currentConfigLiveData.value = video?.config ?: NONE_CONFIG
        videoPreviewViewModel.currentConfigLiveData.observe(viewLifecycleOwner, Observer { newConfig ->
            if (newConfig != null) {
                val currentPlayer = mediaPlayer
                if (currentPlayer == null || currentPlayer.isStopped()) {
                    showThumbnail(true)
                }
                mBinding.videoView.setFilterWithConfig(newConfig.value)
                mBinding.tvFilterName.text = newConfig.name
                previewFiltersAdapter.setNewConfig(newConfig)
            }
        })

        videoPreviewViewModel.showThumbnailEvent.observe(viewLifecycleOwner, Observer {
            showThumbnail(it ?: true)
        })

        mBinding.rcImgPreview.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        previewFiltersAdapter = PreviewFiltersAdapter(context!!, EFFECT_CONFIGS, object : PreviewFiltersAdapter.OnItemInteractListener {
            override fun onConfigSelected(selectedConfig: Config) {
                videoPreviewViewModel.currentConfigLiveData.value = selectedConfig
            }
        })
        mBinding.rcImgPreview.adapter = previewFiltersAdapter
        val pos = previewFiltersAdapter.findConfigPos(video?.config ?: NONE_CONFIG)
        mBinding.rcImgPreview.scrollToPosition(pos ?: 0)

        mBinding.videoView.apply {
            setZOrderOnTop(false)
            setPlayerCallback(playerCallback)
            setVideoUri(video?.uri)
            setOnClickListener(playListener)
            isVisible = false
        }

        mBinding.imgVideoThumb.apply {
            displayMode = ImageGLSurfaceView.DisplayMode.DISPLAY_ASPECT_FILL
            setSurfaceCreatedCallback {
                setImageBitmap(currentBitmap)
                setFilterWithConfig(currentConfig.value)
            }
            setOnClickListener(playListener)
        }

        mBinding.btnPickStickers.setOnClickListener {
            mBinding.btnPickStickers.isSelected = !mBinding.btnPickStickers.isSelected
        }

        mBinding.btnPickFilters.setOnClickListener {
            videoPreviewViewModel.showFiltersEvent.value = videoPreviewViewModel.showFiltersEvent.value?.not() ?: true
        }

        mBinding.btnDone.setOnClickListener {
            val currentVideo = video
            if (currentVideo != null && (isMediaStoreVideoUri(currentVideo.uri) ||
                            (isFileUri(currentVideo.uri) && File(currentVideo.uri.path).exists()))) {
                mediaPlayer?.run {
                    if (isPlaying)
                        stop()
                }
                if (currentVideo.isFromGallery() && currentConfig == NONE_CONFIG) {
                    mainViewModel.doneEditEvent.value = currentVideo.uri
                } else {
                    val context = context ?: kotlin.run {
                        mainViewModel.doneEditEvent.value = null
                        return@setOnClickListener
                    }

                    val inputPath = if (currentVideo.isFromGallery())
                        getPathFromMediaUri(context, currentVideo.uri)
                    else
                        currentVideo.uri.toString()

                    if (inputPath == null) {
                        mainViewModel.doneEditEvent.value = null
                        return@setOnClickListener
                    }

                    scheduleGenerateFilteredVideoNow(inputPath, currentConfig.value, videoPathToSave)?.observe(viewLifecycleOwner,
                            Observer { workStatus ->
                                if (workStatus != null) {
                                    if (workStatus.state.isFinished) {
                                        progressDialogFragment.dismiss()

                                        if (workStatus.state == State.SUCCEEDED) {
                                            val outputUri = workStatus.outputData.getString(GenerateFilteredVideoWorker.KEY_RESULT, "")
                                            if (outputUri != null && !outputUri.isEmpty()) {
                                                mainViewModel.doneEditEvent.value = Uri.parse(outputUri)
                                            } else {
                                                mainViewModel.doneEditEvent.value = null
                                            }
                                        } else {
                                            mainViewModel.doneEditEvent.value = null
                                        }
                                    } else {
                                        when (workStatus.state) {
                                            State.ENQUEUED -> {
                                                progressDialogFragment.show(fragmentManager, ProgressDialogFragment::class.java.simpleName)
                                            }
                                            else -> {
                                            }
                                        }
                                    }
                                }
                            }
                    )
                }
            } else mainViewModel.doneEditEvent.value = null
        }

        mBinding.btnBack.setOnClickListener {
            activity?.supportFragmentManager?.popBackStack()
        }
    }

    private fun MediaPlayer.isPaused() = !isPlaying && !isCompleted

    private fun MediaPlayer.isStopped() = !isPlaying && isCompleted

    private fun startPlayingVideo() {
        isCompleted = false

        mediaPlayer?.run {
            videoPreviewViewModel.showThumbnailEvent.value = false
            mBinding.videoView.setFilterWithConfig(currentConfig.value)
            start()

            lastIntervalUpdate = SystemClock.elapsedRealtime() + PROGRESS_UPDATE_INITIAL_INTERVAL
            scheduleRecordTime()
        }
    }

    private fun showThumbnail(visible: Boolean, config: Config = currentConfig) {
        mBinding.btnPlay.isVisible = visible
        if (visible) {
            mBinding.imgVideoThumb.isVisible = true

            if (currentBitmap == null) {
                mBinding.imgVideoThumb.afterMeasured {
                    Glide.with(this)
                            .asBitmap()
                            .load(video?.uri)
                            .apply(RequestOptions.skipMemoryCacheOf(true))
                            .apply(RequestOptions.overrideOf(width, height))
                            .apply(RequestOptions.bitmapTransform(object : BitmapTransformation() {
                                override fun updateDiskCacheKey(messageDigest: MessageDigest) {
                                    val videoUri = video?.uri
                                    if (videoUri != null) {
                                        messageDigest.update("$videoUri-${config.name}".toByteArray())
                                    }
                                }

                                override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
                                    return Bitmap.createBitmap(toTransform.width, toTransform.height, Bitmap.Config.ARGB_8888).applyCanvas {
                                        drawBitmap(toTransform, 0F, 0F, null)
                                    }
                                }

                            })).listener(object : RequestListener<Bitmap> {
                                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>?, isFirstResource: Boolean): Boolean {
                                    return false
                                }

                                override fun onResourceReady(resource: Bitmap?, model: Any?, target: Target<Bitmap>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                                    currentBitmap = resource
                                    setImageBitmap(currentBitmap)
                                    setFilterWithConfig(config.value)
                                    return false
                                }
                            })
                            .submit()
                }
            } else {
                mBinding.imgVideoThumb.setFilterWithConfig(config.value)
            }
        } else {
            mBinding.imgVideoThumb.isVisible = false
        }
    }

    private fun scheduleGenerateFilteredVideoNow(inputPath: String, config: String, outputPath: String): LiveData<WorkStatus>? {
        val workManager = WorkManager.getInstance()
        if (workManager != null) {
            val generateFilteredVideoWorkRequest = OneTimeWorkRequest.Builder(GenerateFilteredVideoWorker::class.java)
                    .setInputData(GenerateFilteredVideoWorker.data(inputPath, config, outputPath))
                    .build()
            workManager.enqueue(generateFilteredVideoWorkRequest)
            return workManager.getStatusById(generateFilteredVideoWorkRequest.id)
        }
        return null
    }

    private fun scheduleRecordTime() {
        timeRecordingFuture = scheduler.scheduleAtFixedRate({
            mainHandler.post(updateTimeIntervalTask)
        }, PROGRESS_UPDATE_INITIAL_INTERVAL, PROGRESS_UPDATE_INTERNAL, TimeUnit.MILLISECONDS)
    }

    private fun cancelScheduledRecordTime() {
        timeRecordingFuture?.cancel(false)
    }

    private fun showFilters(visible: Boolean) {
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()

        if (visible) {
            mBinding.rcImgPreview.post {
                mBinding.rcImgPreview.alpha = 0.6F
                mBinding.rcImgPreview.isVisible = true
            }
            mBinding.rcImgPreview.post {
                mBinding.rcImgPreview.animate()
                        .alpha(1F)
                        .setDuration(duration)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
            }
            mBinding.tvFilterName.post {
                mBinding.tvFilterName.alpha = 0.6F
                mBinding.tvFilterName.text = videoPreviewViewModel.currentConfigLiveData.value?.name
                mBinding.tvFilterName.isVisible = true
            }
            mBinding.tvFilterName.post {
                mBinding.tvFilterName.animate()
                        .alpha(1F)
                        .setDuration(duration)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
            }
            mBinding.btnPickFilters.isSelected = true
        } else {
            mBinding.rcImgPreview.animate()
                    .alpha(0F)
                    .setDuration(duration)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            mBinding.rcImgPreview.animate().setListener(null)
                            mBinding.rcImgPreview.isVisible = false
                        }
                    }).start()
            mBinding.tvFilterName.animate()
                    .alpha(0F)
                    .setDuration(duration)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            mBinding.tvFilterName.animate().setListener(null)
                            mBinding.tvFilterName.isVisible = false
                        }
                    })
                    .start()
            mBinding.btnPickFilters.isSelected = false
        }
    }

    override fun onResume() {
        super.onResume()
        mBinding.imgVideoThumb.onResume()
        mBinding.videoView.setPlayerCallback(playerCallback)
        mBinding.videoView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer = null
        mBinding.imgVideoThumb.apply {
            release()
            onPause()
        }
        mBinding.videoView.apply {
            isVisible = false
            release()
            onPause()
        }
        cancelScheduledRecordTime()
    }

    override fun onStop() {
        super.onStop()
        mBinding.tvRecordingTime.isVisible = false
        videoPreviewViewModel.showThumbnailEvent.postValue(true)
    }

    override fun onDestroy() {
        scheduler.shutdown()
        video?.let {
            if (it.isFromCamera())
                checkToDeleteTempFile(it.uri)
        }
        super.onDestroy()
    }

    private fun checkToDeleteTempFile(uri: Uri) {
        if (isFileUri(uri)) {
            File(uri.path).apply {
                if (exists()) {
                    delete()
                    activity?.let {
                        reScanFile(it, uri)
                    }
                }
            }
        }
    }

    inner class PlayListener : View.OnClickListener {

        override fun onClick(view: View?) {
            mBinding.videoView.isVisible = true

            val currentPlayer = mediaPlayer
            if (currentPlayer != null) {
                when {
                    currentPlayer.isStopped() -> startPlayingVideo()
                    currentPlayer.isPaused() -> {
                        lastIntervalUpdate = SystemClock.elapsedRealtime() - currentPlayer.currentPosition + PROGRESS_UPDATE_INITIAL_INTERVAL
                        mBinding.btnPlay.isVisible = false
                        scheduleRecordTime()
                        currentPlayer.start()
                    }
                    else -> {
                        mBinding.btnPlay.isVisible = true
                        cancelScheduledRecordTime()
                        currentPlayer.pause()
                    }
                }
            } else {
                mBinding.videoView.setVideoUri(video?.uri)
            }
        }
    }

    companion object {
        private const val EXTRA_VIDEO = "EXTRA_VIDEO"
        private const val PROGRESS_UPDATE_INTERNAL: Long = 1000
        private const val PROGRESS_UPDATE_INITIAL_INTERVAL: Long = 100

        fun newInstance(video: Video): VideoPreviewFragment {
            return VideoPreviewFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(EXTRA_VIDEO, video)
                }
            }
        }
    }
}