package com.penthera.sdkdemokotlin.activity


import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.*
import android.media.MediaDrm
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.Pair
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.C.WIDEVINE_UUID
import com.google.android.exoplayer2.drm.DrmSessionManager
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException
import com.google.android.exoplayer2.source.BehindLiveWindowException
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.*
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector.ParametersBuilder
import com.google.android.exoplayer2.util.DebugTextViewHelper
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.ui.StyledPlayerControlView
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.*
import com.google.android.exoplayer2.util.ErrorMessageProvider
import com.google.android.exoplayer2.util.EventLogger
import com.google.android.exoplayer2.util.Util
import com.penthera.sdkdemokotlin.R
import com.penthera.sdkdemokotlin.catalog.CatalogItemType
import com.penthera.sdkdemokotlin.catalog.ExampleCatalogItem
import com.penthera.sdkdemokotlin.dialog.TrackSelectionDialog
import com.penthera.virtuososdk.Common
import com.penthera.virtuososdk.client.*
import com.penthera.virtuososdk.client.drm.UnsupportedDrmException
import com.penthera.virtuososdk.support.exoplayer217.ExoplayerUtils
import com.penthera.virtuososdk.support.exoplayer217.drm.ExoplayerDrmSessionManager

import com.penthera.virtuososdk.utility.CommonUtil.Identifier.*
import java.net.*
import java.util.*
import kotlin.collections.HashMap

/**
 * Created by Penthera on 17/01/2019.
 */
class VideoPlayerActivity : AppCompatActivity(), View.OnClickListener, StyledPlayerControlView.VisibilityListener {

    // Best practice is to ensure we have a Virtuoso instance available while playing segmented assets
    // as this will guarantee the proxy service remains available throughout.
    private lateinit var mVirtuoso: Virtuoso

    private lateinit var playerView: StyledPlayerView
    private var debugRootView: LinearLayout? = null
    private var selectTracksButton: Button? = null
    private var debugTextView: TextView? = null
    private var isShowingTrackSelectionDialog = false

    private var player: Player? = null
    private var mediaSource: MediaSource? = null
    private var drmSessionManager: DrmSessionManager? = null
    private var mTrackSelector: DefaultTrackSelector? = null
    private var trackSelectorParameters: DefaultTrackSelector.Parameters? = null
    private var lastSeenTrackGroupArray: TrackGroupArray? = null

    private var debugViewHelper: DebugTextViewHelper? = null
    private var inErrorState = false

    private var shouldAutoPlay: Boolean = false
    private var startWindow: Int = 0
    private var startPosition: Long = 0

    // Activity lifecycle

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mVirtuoso = Virtuoso(applicationContext)

        setContentView(R.layout.player_activity)
        val rootView = findViewById<View>(R.id.root)
        rootView.setOnClickListener(this)
        debugRootView = findViewById<View>(R.id.controls_root) as LinearLayout
        debugTextView = findViewById<View>(R.id.debug_text_view) as TextView
        selectTracksButton = findViewById<View>(R.id.select_tracks_button) as Button
        selectTracksButton!!.setOnClickListener(this)

        playerView = findViewById<StyledPlayerView>(R.id.player_view).apply{
            setControllerVisibilityListener(this@VideoPlayerActivity)
            setErrorMessageProvider(PlayerErrorMessageProvider())
            requestFocus()

        }

        if (savedInstanceState != null) {
            trackSelectorParameters = DefaultTrackSelector.Parameters.CREATOR.fromBundle(
                savedInstanceState.getBundle(KEY_TRACK_SELECTOR_PARAMETERS)!!
            )
            shouldAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY)
            startWindow = savedInstanceState.getInt(KEY_WINDOW)
            startPosition = savedInstanceState.getLong(KEY_POSITION)
        } else {
            trackSelectorParameters = ParametersBuilder(this).build()
            clearStartPosition()
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        releasePlayer()
        shouldAutoPlay = true
        clearStartPosition()
        setIntent(intent)
    }

    public override fun onStart() {
        super.onStart()
        initializePlayer()
        playerView?.onResume()
    }

    public override fun onResume() {
        super.onResume()
        mVirtuoso?.onResume()
        mVirtuoso?.addObserver(ProxyPortUpdated(this))
    }

    public override fun onPause() {
        super.onPause()
        mVirtuoso?.onPause()
    }

    public override fun onStop() {
        super.onStop()
        playerView.onPause()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        updateTrackSelectorParameters()
        updateStartPosition()
        outState.putBundle(KEY_TRACK_SELECTOR_PARAMETERS, trackSelectorParameters?.toBundle())
        outState.putBoolean(KEY_AUTO_PLAY, shouldAutoPlay)
        outState.putInt(KEY_WINDOW, startWindow)
        outState.putLong(KEY_POSITION, startPosition)
    }

    // Activity input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Show the controls on any key event.
        playerView?.showController()
        // If the event was not handled then see if the player view can handle it as a media key event.
        return super.dispatchKeyEvent(event) || playerView?.dispatchMediaKeyEvent(event) ?: false
    }

    // OnClickListener methods

    override fun onClick(view: View) {
        mTrackSelector?.let {
            if (view === selectTracksButton
                    && !isShowingTrackSelectionDialog
                    && TrackSelectionDialog.willHaveContent(it)) {
                isShowingTrackSelectionDialog = true
                val trackSelectionDialog: TrackSelectionDialog = TrackSelectionDialog.createForTrackSelector(
                        it,  /* onDismissListener= */
                        DialogInterface.OnDismissListener { isShowingTrackSelectionDialog = false })
                trackSelectionDialog.show(supportFragmentManager,  /* tag= */null)
            }
        }
    }

    // PlaybackControlView.VisibilityListener implementation
    override fun onVisibilityChange(visibility: Int) {
        debugRootView?.visibility = visibility
    }

    // Internal methods

    private fun initializePlayer() {
        val intent = intent

        var segmentedAsset: ISegmentedAsset? = null
        val asset: IAsset? = intent.getParcelableExtra(VIRTUOSO_ASSET)
        if (asset != null && asset is ISegmentedAsset) {
            segmentedAsset = asset
        }

        val adaptiveTrackSelectionFactory: ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory()
        mTrackSelector = DefaultTrackSelector(this, adaptiveTrackSelectionFactory)
        mTrackSelector?.parameters = trackSelectorParameters!!
        lastSeenTrackGroupArray = null

        if (player == null) {

            val extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

            val renderersFactory = DefaultRenderersFactory(this)
            renderersFactory.setExtensionRendererMode(extensionRendererMode)
            val eventLogger = EventLogger(mTrackSelector)


            if (asset == null) {
                initPlayerForStreaming(renderersFactory)
            } else {
                // downloaded asset
                val builder = ExoplayerUtils.PlayerConfigOptions.Builder(this)
                    .playWhenReady(true)
                    .withBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(this))
                    .withTrackSelector(mTrackSelector)
                    .withAnalyticsListener(eventLogger)
                    .userRenderersFactory(renderersFactory)
                    .withPlayerListener(PlayerEventListener())

                builder.mediaSourceOptions()
                    .withTransferListener(DefaultBandwidthMeter.getSingletonInstance(this))
                    .withUserAgent("virtuoso-sdk")

                builder.drmOptions()
                    .withDrmSessionManagerEventListener(
                        DrmListener(this)
                    )

                val haveResumePosition = startWindow != C.INDEX_UNSET
                if (haveResumePosition) {
                    builder.withSeekToPosition(startWindow, startPosition)
                }

                try {
                    player = ExoplayerUtils.setupPlayer(
                        playerView!!,
                        mVirtuoso.assetManager, asset, false, builder.build()
                    )
                    if (player == null) {
                        runOnUiThread {
                            val alertDialog =
                                AlertDialog.Builder(this@VideoPlayerActivity).create()
                            alertDialog.setTitle("Asset unavailable")
                            alertDialog.setMessage("Could not initialize player for asset. Playback unavailable.")
                            alertDialog.setButton(
                                AlertDialog.BUTTON_NEUTRAL,
                                "OK"
                            ) { dialog: DialogInterface, which: Int ->
                                dialog.dismiss()
                                this@VideoPlayerActivity.finish()
                            }
                            alertDialog.show()
                        }
                    }
                } catch (e: MalformedURLException) {
                    e.printStackTrace()
                    return
                }

                if (player != null && player is ExoPlayer) {
                    debugViewHelper = DebugTextViewHelper(
                        (player as ExoPlayer?)!!,
                        debugTextView!!
                    )
                    debugViewHelper!!.start()
                }

            }
        }

        inErrorState = false
        updateButtonVisibilities()
    }

    // Internal methods
    private fun initPlayerForStreaming(renderersFactory: RenderersFactory) {
        if (player == null) {
            val drmUrl = intent.getStringExtra(STREAM_DRM_URL)
            val mib = MediaItem.Builder()
                .setUri(intent.data)
            drmUrl?.let {
                // Assuming support is only for widevine
                val drmConfiguration = MediaItem.DrmConfiguration.Builder(WIDEVINE_UUID)
                    .setLicenseUri(it)
                    .build()
                mib.setDrmConfiguration(drmConfiguration)
            }
            val builder = ExoPlayer.Builder(this, renderersFactory)
            val exoplayer = builder.build()
            player = exoplayer
            exoplayer.setPlayWhenReady(true)
            playerView!!.player = player
            exoplayer.addMediaItem(mib.build())
            exoplayer.prepare()
            if (player != null) {
                debugViewHelper = DebugTextViewHelper(
                    exoplayer,
                    debugTextView!!
                )
                debugViewHelper!!.start()
            }
        }
    }

    private fun releasePlayer() {
        if (player != null) {
            debugViewHelper?.stop()
            debugViewHelper = null
            shouldAutoPlay = player?.playWhenReady ?: false
            updateStartPosition()
            player?.release()
            player = null
            mTrackSelector = null
            trackSelectorParameters = null
        }
    }

    private fun updateTrackSelectorParameters() {
        mTrackSelector?.let {
            trackSelectorParameters = it.parameters
        }
    }

    private fun updateStartPosition() {
        player?.let {
            shouldAutoPlay = it.playWhenReady
            startWindow = it.currentWindowIndex
            startPosition = 0L.coerceAtLeast(it.contentPosition)
        }
    }

    private fun clearStartPosition() {
        shouldAutoPlay = true
        startWindow = C.INDEX_UNSET
        startPosition = C.TIME_UNSET
    }


    // User controls

    private fun updateButtonVisibilities() {
        mTrackSelector?.let {
            selectTracksButton!!.isEnabled = player != null && TrackSelectionDialog.willHaveContent(it)
        }
    }

    private inner class PlayerEventListener : Player.Listener {

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                showControls()
            }
            updateButtonVisibilities()
        }

        // Error handling

        override fun onPositionDiscontinuity(@Player.DiscontinuityReason reason: Int) {
            if (inErrorState) {
                // This will only occur if the user has performed a seek whilst in the error state. Update
                // the resume position so that if the user then retries, playback will resume from the
                // position to which they seeked.
                updateStartPosition()
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            inErrorState = true
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                clearStartPosition()
                initializePlayer()
            } else {
                updateButtonVisibilities()
                showControls()
            }
        }

        override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
            updateButtonVisibilities()
            if (trackGroups !== lastSeenTrackGroupArray) {
                val mappedTrackInfo = mTrackSelector!!.currentMappedTrackInfo
                if (mappedTrackInfo != null) {
                    if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO) == MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
                        showToast(R.string.error_unsupported_video)
                    }
                    if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO) == MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
                        showToast(R.string.error_unsupported_audio)
                    }
                }
                lastSeenTrackGroupArray = trackGroups
            }
        }
    }

    fun handleDrmLicenseNotAvailable() {
        inErrorState = true
        clearStartPosition()
        debugRootView!!.visibility = View.GONE

        runOnUiThread {
            AlertDialog.Builder(this@VideoPlayerActivity).apply {
                title = "License unavailable"
                setMessage("License for offline playback expired and renew is unavailable.")
                setNeutralButton("OK", {dialog, _ ->
                    dialog.dismiss()
                    this@VideoPlayerActivity.finish()
                })
            }.create().show()
        }
    }

    private fun showControls() {
        debugRootView!!.visibility = View.VISIBLE
    }

    private fun showToast(messageId: Int) {
        showToast(getString(messageId))
    }

    private fun showToast(message: String?) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }


    /**
     * Demonstrates how to use an observer class from the Download2Go session manager. This
     * enables the client to be informed of events for when keys are loaded or an error occurs
     * with fetching a license.
     */
    private class DrmListener(private val activity: VideoPlayerActivity) : ExoplayerDrmSessionManager.EventListener {

        override fun onDrmKeysLoaded() {
           Toast.makeText(activity, "Drm Keys Loaded", Toast.LENGTH_SHORT)
        }

        override fun onDrmSessionManagerError(e: java.lang.Exception) { // Can't complete playback
            activity.handleDrmLicenseNotAvailable()
        }

    }

    /**
     * Demonstrates how to view media drm events directly, which we use for logging
     */
    @TargetApi(18)
    private class MediaDrmOnEventListener : MediaDrm.OnEventListener {
        override fun onEvent(md: MediaDrm, sessionId: ByteArray?, event: Int, extra: Int, data: ByteArray?) {
            Log.d("MediaDrm", "MediaDrm event: $event")
        }
    }

    /**
     * The proxy update observes if the proxy needs to change port after a restart,
     * which can occur if the app is placed in the background and then brought back to the foreground.
     * In this case the player needs to be set back up to get the new base url.
     */
    private class ProxyPortUpdated(private val player: VideoPlayerActivity) : EngineObserver() {
        override fun proxyPortUpdated() {
            super.proxyPortUpdated()
            Log.w(VideoPlayerActivity::class.java.getSimpleName(), "Received warning about change in port, restarting player")
            player.releasePlayer()
            player.shouldAutoPlay = true
            player.initializePlayer()
        }
    }


    private inner class PlayerErrorMessageProvider :
            ErrorMessageProvider<PlaybackException> {
        override fun getErrorMessage(e: PlaybackException): Pair<Int, String> {
            var errorString: String = getString(R.string.error_generic)
            val cause = e.cause
            if (cause is DecoderInitializationException) {
                // Special case for decoder initialization failures.
                val decoderInitializationException =
                        cause
                if (decoderInitializationException.codecInfo == null) {
                    if (decoderInitializationException.cause is DecoderQueryException) {
                        errorString = getString(R.string.error_querying_decoders)
                    } else if (decoderInitializationException.secureDecoderRequired) {
                        errorString = getString(
                                R.string.error_no_secure_decoder,
                                decoderInitializationException.mimeType
                        )
                    } else {
                        errorString = getString(
                                R.string.error_no_decoder,
                                decoderInitializationException.mimeType
                        )
                    }
                } else {
                    errorString = getString(
                            R.string.error_instantiating_decoder,
                            decoderInitializationException.codecInfo!!.name
                    )
                }
            }
            return Pair.create(0, errorString)
        }
    }

    companion object {

        private const val VIRTUOSO_CONTENT_TYPE = "asset_type"
        private const val VIRTUOSO_ASSET = "asset"
        const val STREAM_DRM_URL = "stream_drm_url"

        private const val ACTION_VIEW = "com.penthera.harness.exoplayer.action.VIEW"

        // Saved instance state keys.
        private const val KEY_TRACK_SELECTOR_PARAMETERS = "track_selector_parameters"
        private const val KEY_WINDOW = "window"
        private const val KEY_POSITION = "position"
        private const val KEY_AUTO_PLAY = "auto_play"

        private val DEFAULT_COOKIE_MANAGER: CookieManager = CookieManager()

        init {
            DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        }

        private fun isBehindLiveWindow(e: ExoPlaybackException): Boolean {
            if (e.type != ExoPlaybackException.TYPE_SOURCE) {
                return false
            }
            var cause: Throwable? = e.sourceException
            while (cause != null) {
                if (cause is BehindLiveWindowException) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        fun playVideoStream(item : ExampleCatalogItem, context : Context, drmUrl: String?){

            val type : Int = when(item.contentType){
                CatalogItemType.HLS_MANIFEST -> {
                    SEGMENTED_ASSET_IDENTIFIER_HLS
                }
                CatalogItemType.DASH_MANIFEST -> {
                    SEGMENTED_ASSET_IDENTIFIER_MPD
                }
                else -> {
                    FILE_IDENTIFIER
                }

            }

            val intent = Intent(context, VideoPlayerActivity::class.java)
                .setAction(ACTION_VIEW)
                .setData(Uri.parse(item.contentUri))
                .putExtra(VIRTUOSO_CONTENT_TYPE, type)

            drmUrl?.apply {
                intent.putExtra(STREAM_DRM_URL, drmUrl)
            }

            context.startActivity(intent)
        }

        fun playVideoDownload(asset: IAsset , context: Context){

            var type = Common.AssetIdentifierType.FILE_IDENTIFIER
            val path: Uri
            if (asset.type == Common.AssetIdentifierType.SEGMENTED_ASSET_IDENTIFIER) {
                val sa = asset as ISegmentedAsset
                type = sa.segmentedFileType()
                val url = sa.playbackURL ?: return
                path = Uri.parse(url.toString())
            } else {
                val f = asset as IFile
                path = Uri.parse(f.filePath)
            }

            val intent = Intent(context, VideoPlayerActivity::class.java)
                .setAction(ACTION_VIEW)
                .setData(path)
                .putExtra(VIRTUOSO_CONTENT_TYPE, type)
                .putExtra(VIRTUOSO_ASSET, asset)

            context.startActivity(intent)


        }
    }
}
