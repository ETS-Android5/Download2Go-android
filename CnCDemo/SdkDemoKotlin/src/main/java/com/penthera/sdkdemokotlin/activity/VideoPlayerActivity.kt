package com.penthera.sdkdemokotlin.activity


import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaDrm
import android.net.Uri
import android.os.*
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.BuildConfig
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.drm.DrmSessionManager
import com.google.android.exoplayer2.drm.DrmSessionManagerWrapper
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.metadata.emsg.EventMessage
import com.google.android.exoplayer2.metadata.id3.*
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.trackselection.*
import com.google.android.exoplayer2.ui.*
import com.google.android.exoplayer2.upstream.*
import com.google.android.exoplayer2.util.Util
import com.penthera.virtuososdk.Common
import com.penthera.virtuososdk.client.drm.UnsupportedDrmException
import com.penthera.virtuososdk.client.drm.VirtuosoDrmSessionManager
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.*

import com.penthera.sdkdemokotlin.R
import com.penthera.sdkdemokotlin.catalog.CatalogItemType
import com.penthera.sdkdemokotlin.catalog.ExampleCatalogItem
import com.penthera.virtuososdk.client.*
import com.penthera.virtuososdk.utility.CommonUtil.Identifier.*
import java.io.IOException
import java.text.NumberFormat

/**
 * Created by Penthera on 17/01/2019.
 */
class VideoPlayerActivity : Activity(), View.OnClickListener, PlaybackPreparer, PlayerControlView.VisibilityListener {

    // Best practice is to ensure we have a Virtuoso instance available while playing segmented assets
    // as this will guarantee the proxy service remains available throughout.
    private var mVirtuoso: Virtuoso? = null

    private var mainHandler: Handler? = null
    private var eventLogger: EventLogger? = null
    private var playerView: PlayerView? = null
    private var debugRootView: LinearLayout? = null
    private var debugTextView: TextView? = null
    private var retryButton: Button? = null

    private var mediaDataSourceFactory: DataSource.Factory? = null
    private var player: SimpleExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var trackSelectorParameters: DefaultTrackSelector.Parameters? = null
    private var debugViewHelper: DebugTextViewHelper? = null
    private var playerNeedsSource: Boolean = false
    private var inErrorState: Boolean = false
    private var lastSeenTrackGroupArray: TrackGroupArray? = null

    private var shouldAutoPlay: Boolean = false
    private var resumeWindow: Int = 0
    private var resumePosition: Long = 0

    // Activity lifecycle

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mVirtuoso = Virtuoso(applicationContext)

        shouldAutoPlay = true
        clearResumePosition()

        mediaDataSourceFactory = buildDataSourceFactory(true)
        mainHandler = Handler()
        if (CookieHandler.getDefault() !== DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER)
        }

        setContentView(R.layout.player_activity)
        val rootView = findViewById<View>(R.id.root)
        rootView.setOnClickListener(this)
        debugRootView = findViewById<View>(R.id.controls_root) as LinearLayout
        debugTextView = findViewById<View>(R.id.debug_text_view) as TextView
        retryButton = findViewById<View>(R.id.retry_button) as Button
        retryButton!!.setOnClickListener(this)

        playerView = findViewById<View>(R.id.player_view) as PlayerView
        playerView!!.setControllerVisibilityListener(this)
        playerView!!.requestFocus()


        trackSelectorParameters = DefaultTrackSelector.ParametersBuilder().build()
    }

    public override fun onNewIntent(intent: Intent) {
        releasePlayer()
        shouldAutoPlay = true
        clearResumePosition()
        setIntent(intent)
    }

    public override fun onStart() {
        super.onStart()

        initializePlayer()

    }

    public override fun onResume() {
        super.onResume()
        if ( player == null) {
            initializePlayer()
        }
        mVirtuoso!!.onResume()
    }

    public override fun onPause() {
        super.onPause()

        mVirtuoso!!.onPause()
    }

    public override fun onStop() {
        super.onStop()
            releasePlayer()
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) =
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializePlayer()
        } else {
            showToast(R.string.storage_permission_denied)
            finish()
        }

    // Activity input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Show the controls on any key event.
        playerView!!.showController()
        // If the event was not handled then see if the player view can handle it as a media key event.
        return super.dispatchKeyEvent(event) || playerView!!.dispatchMediaKeyEvent(event)
    }

    // OnClickListener methods

    override fun onClick(view: View) {
        if (view === retryButton) {
            initializePlayer()
        } else if (view.parent === debugRootView) {
            val mappedTrackInfo = trackSelector?.currentMappedTrackInfo ?: return

            val rendererIndex = view.tag as Int
            val title = (view as Button).text.toString()
            val rendererType = mappedTrackInfo.getRendererType(rendererIndex)
            val allowAdaptiveSelections = rendererType == C.TRACK_TYPE_VIDEO || rendererType == C.TRACK_TYPE_AUDIO && mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO) == MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_NO_TRACKS
            val dialogPair = TrackSelectionView.getDialog(this, title, trackSelector, rendererIndex)
            dialogPair.second.setShowDisableOption(true)
            dialogPair.second.setAllowAdaptiveSelections(allowAdaptiveSelections)
            dialogPair.first.show()
        }
    }

    // PlaybackControlView.PlaybackPreparer implementation

    override fun preparePlayback() {
        initializePlayer()
    }

    // PlaybackControlView.VisibilityListener implementation

    override fun onVisibilityChange(visibility: Int) {
        debugRootView!!.visibility = visibility
    }

    // Internal methods

    private fun initializePlayer() {
        val intent = intent


        if (player == null) {

            val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory()
            trackSelector = DefaultTrackSelector(adaptiveTrackSelectionFactory)
            trackSelector?.parameters = trackSelectorParameters
            lastSeenTrackGroupArray = null
            eventLogger = EventLogger(trackSelector!!)


            var drmSessionManager: DrmSessionManager<FrameworkMediaCrypto>? = null
            if (intent.hasExtra(DRM_SCHEME_UUID_EXTRA) || intent.hasExtra(DRM_SCHEME_EXTRA)) {

                val drmSchemeExtra = if (intent.hasExtra(DRM_SCHEME_EXTRA))
                    DRM_SCHEME_EXTRA
                else
                    DRM_SCHEME_UUID_EXTRA
                val drmUuid = intent.getStringExtra(drmSchemeExtra)
                val keyRequestPropertiesArray = intent.getStringArrayExtra(DRM_KEY_REQUEST_PROPERTIES)

                if (drmUuid != null) {

                    var errorStringId = R.string.error_drm_unknown
                    if (Util.SDK_INT < 18) {
                        errorStringId = R.string.error_drm_not_supported
                    } else {
                        try {

                            var drmSchemeUuid: UUID? = null
                            if (!TextUtils.isEmpty(drmUuid))
                                drmSchemeUuid = Util.getDrmUuid(drmUuid)

                            if (drmSchemeUuid != null) {
                                val asset = intent.getParcelableExtra<IAsset>(VIRTUOSO_ASSET)
                                drmSessionManager = buildDrmSessionManager(drmSchemeUuid,
                                        keyRequestPropertiesArray,
                                        asset, intent.getStringExtra(VIRTUOSO_ASSET_ID))
                            } else {
                                errorStringId = R.string.error_drm_unsupported_scheme
                            }
                        } catch (e: UnsupportedDrmException) {
                            errorStringId = if (e.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME)
                                R.string.error_drm_unsupported_scheme
                            else
                                R.string.error_drm_unknown
                        }

                    }
                    if (drmSessionManager == null) {
                        showToast(errorStringId)
                        return
                    }
                }
            }


            val preferExtensionDecoders = intent.getBooleanExtra(PREFER_EXTENSION_DECODERS, false)
            @DefaultRenderersFactory.ExtensionRendererMode val extensionRendererMode = if (BuildConfig.FLAVOR == "withExtensions")
                if (preferExtensionDecoders)
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            else
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF


            val renderersFactory = DefaultRenderersFactory(this)
            renderersFactory.setExtensionRendererMode(extensionRendererMode)

            player = ExoPlayerFactory.newSimpleInstance(this,
                    renderersFactory,
                    trackSelector,
                    DefaultLoadControl(),
                    drmSessionManager,
                    BANDWIDTH_METER)
            player!!.addListener(PlayerEventListener())
            player!!.addListener(eventLogger)
            player!!.addMetadataOutput(eventLogger)
            player!!.addAnalyticsListener(eventLogger)
            player!!.playWhenReady = shouldAutoPlay

            playerView!!.player = player
            playerView!!.setPlaybackPreparer(this)
            debugViewHelper = DebugTextViewHelper(player, debugTextView)
            debugViewHelper!!.start()
            playerNeedsSource = true
        }
        if (playerNeedsSource) {
            val action = intent.action

            val uri = intent.data
            val type = intent.getIntExtra(VIRTUOSO_CONTENT_TYPE, Common.AssetIdentifierType.FILE_IDENTIFIER)
            if (ACTION_VIEW != action) {
                showToast(getString(R.string.unexpected_intent_action, action))
                return
            }
            // All our files are stored in the app private space so no need to check permissions after kitkat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                if (Util.maybeRequestReadExternalStoragePermission(this, uri)) {
                    // The player will be reinitialized if the permission is granted.
                    return
                }
            }
            val mediaSource = buildMediaSource(uri, type)
            playerNeedsSource = false
            val haveResumePosition = resumeWindow != C.INDEX_UNSET
            if (haveResumePosition) {
                player!!.seekTo(resumeWindow, resumePosition)
            }
            player!!.prepare(mediaSource, !haveResumePosition, false)
            inErrorState = false
            updateButtonVisibilities()
        }
    }

    private fun buildMediaSource(uri: Uri?, type: Int): MediaSource {

        val ret : MediaSource
        when (type) {
            ISegmentedAsset.SEG_FILE_TYPE_HSS -> ret =  SsMediaSource.Factory(
                    DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                    buildDataSourceFactory(false))
                    .createMediaSource(uri)
            ISegmentedAsset.SEG_FILE_TYPE_MPD -> ret =  DashMediaSource.Factory(
                    DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                    buildDataSourceFactory(false))
                    .createMediaSource(uri)
            ISegmentedAsset.SEG_FILE_TYPE_HLS -> ret = HlsMediaSource.Factory(mediaDataSourceFactory)
                    .createMediaSource(uri)
            Common.AssetIdentifierType.FILE_IDENTIFIER -> ret = ExtractorMediaSource.Factory(mediaDataSourceFactory).createMediaSource(uri)
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
        ret.addEventListener(mainHandler, eventLogger)
        return ret
    }

    @Throws(UnsupportedDrmException::class)
    private fun buildDrmSessionManager(uuid: UUID,
                                       keyRequestPropertiesArray: Array<String>?,
                                       asset: IAsset?,
                                       assetId: String): DrmSessionManager<FrameworkMediaCrypto>? {

        var keyRequestPropertiesMap: HashMap<String, String>? = null
        if (!keyRequestPropertiesArray.isNullOrEmpty()) {
            keyRequestPropertiesMap = HashMap()
            var key: String? = null
            for (i in keyRequestPropertiesArray.indices) {
                if (i % 2 == 0) {
                    key = keyRequestPropertiesArray[i]
                } else {
                    val prop = keyRequestPropertiesArray[i]
                    if (!key.isNullOrEmpty() && !prop.isNullOrEmpty()) {
                        keyRequestPropertiesMap[key] = prop
                    }
                }
            }
        }

        val drmListener = DrmListener(this, eventLogger!!)


        return asset?.let{
            DrmSessionManagerWrapper(applicationContext, uuid,
                    asset, keyRequestPropertiesMap!!, mainHandler!!.looper, mainHandler!!, drmListener, eventLogger!!)
        }?:run{
            DrmSessionManagerWrapper(applicationContext, uuid,
                    assetId, keyRequestPropertiesMap!!, mainHandler!!.looper, mainHandler!!, drmListener, eventLogger!!)
        }


    }

    private fun releasePlayer() {
        if (player != null) {
            debugViewHelper!!.stop()
            debugViewHelper = null
            shouldAutoPlay = player!!.playWhenReady
            updateResumePosition()
            player!!.release()
            player = null
            trackSelector = null
            trackSelectorParameters = null
            eventLogger = null
        }
    }

    private fun updateResumePosition() {
        resumeWindow = player!!.currentWindowIndex
        resumePosition = Math.max(0, player!!.contentPosition)
    }

    private fun clearResumePosition() {
        resumeWindow = C.INDEX_UNSET
        resumePosition = C.TIME_UNSET
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set [.BANDWIDTH_METER] as a listener to the new
     * DataSource factory.
     * @return A new DataSource factory.
     */
    private fun buildDataSourceFactory(useBandwidthMeter: Boolean): DataSource.Factory {
        return DefaultDataSourceFactory(applicationContext, if (useBandwidthMeter) BANDWIDTH_METER else null,
                buildHttpDataSourceFactory(useBandwidthMeter))
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @param useBandwidthMeter Whether to set [.BANDWIDTH_METER] as a listener to the new
     * DataSource factory.
     * @return A new HttpDataSource factory.
     */
    private fun buildHttpDataSourceFactory(useBandwidthMeter: Boolean): HttpDataSource.Factory {
        return DefaultHttpDataSourceFactory("virtuoso-sdk", if (useBandwidthMeter) BANDWIDTH_METER else null)
    }

    // User controls

    private fun updateButtonVisibilities() {
        debugRootView!!.removeAllViews()

        retryButton!!.visibility = if (playerNeedsSource) View.VISIBLE else View.GONE
        debugRootView!!.addView(retryButton)

        if (player == null) {
            return
        }

        val mappedTrackInfo = trackSelector!!.currentMappedTrackInfo ?: return

        for (i in 0 until mappedTrackInfo.rendererCount) {
            val trackGroups = mappedTrackInfo.getTrackGroups(i)
            if (trackGroups.length != 0) {
                val button = Button(this)
                val label: Int = when (player!!.getRendererType(i)) {
                    C.TRACK_TYPE_AUDIO -> R.string.audio
                    C.TRACK_TYPE_VIDEO -> R.string.video
                    C.TRACK_TYPE_TEXT -> R.string.text
                    else -> {
                        -1
                    }
                }

                if(label == -1)
                    continue
                button.setText(label)
                button.tag = i
                button.setOnClickListener(this)
                debugRootView!!.addView(button, debugRootView!!.childCount - 1)
            }
        }
    }

    private inner class PlayerEventListener : Player.EventListener {

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
                updateResumePosition()
            }
        }

        override fun onPlayerError(e: ExoPlaybackException?) {
            var errorString: String? = null
            if (e!!.type == ExoPlaybackException.TYPE_RENDERER) {
                val cause = e.rendererException
                // Special case for decoder initialization failures.
                if (cause is MediaCodecRenderer.DecoderInitializationException) errorString = if (cause.decoderName == null) {
                    when {
                        cause.cause is MediaCodecUtil.DecoderQueryException -> getString(R.string.error_querying_decoders)
                        cause.secureDecoderRequired -> getString(R.string.error_no_secure_decoder,
                                cause.mimeType)
                        else -> getString(R.string.error_no_decoder,
                                cause.mimeType)
                    }
                } else {
                    getString(R.string.error_instantiating_decoder,
                            cause.decoderName)
                }
            }
            if (errorString != null) {
                showToast(errorString)
            }
            inErrorState = true
            if (isBehindLiveWindow(e)) {
                clearResumePosition()
                initializePlayer()
            } else {
                updateResumePosition()
                updateButtonVisibilities()
                showControls()
            }
        }

        override fun onTracksChanged(trackGroups: TrackGroupArray?, trackSelections: TrackSelectionArray?) {
            updateButtonVisibilities()
            if (trackGroups !== lastSeenTrackGroupArray) {
                val mappedTrackInfo = trackSelector!!.currentMappedTrackInfo
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
        clearResumePosition()
        debugRootView!!.visibility = View.GONE

        runOnUiThread {
            val alertDialog = AlertDialog.Builder(this@VideoPlayerActivity).create()
            alertDialog.setTitle("License unavailable")
            alertDialog.setMessage("License for offline playback expired and renew is unavailable.")
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK") { dialog, _ ->
                dialog.dismiss()
                this@VideoPlayerActivity.finish()
            }
            alertDialog.show()
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

    private class DrmListener(private val mActivity: VideoPlayerActivity, private val mLogger: EventLogger) : VirtuosoDrmSessionManager.EventListener {

        override fun onDrmKeysLoaded() {
            mLogger.onDrmKeysLoaded()
        }

        override fun onDrmSessionManagerError(e: Exception) {
            // Can't complete playback
            mActivity.handleDrmLicenseNotAvailable()
            mLogger.onDrmSessionManagerError(e)
        }
    }

    companion object {

        // For backwards compatability
        const val DRM_SCHEME_UUID_EXTRA = "drm_scheme_uuid"

        // DRM
        private const val DRM_SCHEME_EXTRA = "drm_scheme"
        private const val DRM_LICENSE_URL = "drm_license_url" //  ??
        private const val DRM_KEY_REQUEST_PROPERTIES = "drm_key_request_properties"

        private const val VIRTUOSO_CONTENT_TYPE = "asset_type"
        private const val VIRTUOSO_ASSET = "asset"
        private const val VIRTUOSO_ASSET_ID = "asset_id"
        private const val PREFER_EXTENSION_DECODERS = "prefer_extension_decoders"

        private const val ACTION_VIEW = "com.penthera.harness.exoplayer.action.VIEW"

        private val BANDWIDTH_METER = DefaultBandwidthMeter()
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

        fun playVideoStream(item : ExampleCatalogItem, context : Context){

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
                    .putExtra(VIRTUOSO_ASSET_ID, item.exampleAssetId)

            context.startActivity(intent)
        }

        fun playVideoDownload(asset: IAsset , context: Context){

            var type = Common.AssetIdentifierType.FILE_IDENTIFIER
            val path: Uri
            var drmuuid: String? = null
            if (asset.type == Common.AssetIdentifierType.SEGMENTED_ASSET_IDENTIFIER) {
                val sa = asset as ISegmentedAsset
                type = sa.segmentedFileType()
                path = Uri.parse(sa.playlist.toString())
                drmuuid = sa.contentProtectionUuid()
            } else {
                val f = asset as IFile
                path = Uri.parse(f.filePath)
            }

            val intent = Intent(context, VideoPlayerActivity::class.java)
                    .setAction(ACTION_VIEW)
                    .setData(path)
                    .putExtra(DRM_SCHEME_UUID_EXTRA, drmuuid)
                    .putExtra(VIRTUOSO_CONTENT_TYPE, type)
                    .putExtra(VIRTUOSO_ASSET_ID, asset.assetId)

            context.startActivity(intent)


        }


    }

    @TargetApi(18)
    internal class EventLogger(private val trackSelector: MappingTrackSelector) : Player.EventListener,  MediaSourceEventListener, VirtuosoDrmSessionManager.EventListener, MetadataOutput, MediaDrm.OnEventListener, AnalyticsListener {


        private val window: Timeline.Window = Timeline.Window()
        private val period: Timeline.Period = Timeline.Period()
        private val startTimeMs: Long = SystemClock.elapsedRealtime()

        private val sessionTimeString: String
            get() = getTimeString(SystemClock.elapsedRealtime() - startTimeMs)

        // ExoPlayer.EventListener

        override fun onLoadingChanged(isLoading: Boolean) {
            Log.d(TAG, "loading [$isLoading]")
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, state: Int) {
            Log.d(TAG, "state [" + sessionTimeString + ", " + playWhenReady + ", "
                    + getStateString(state) + "]")
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            Log.d(TAG, "onRepeatModeChanged")
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {

        }

        override fun onPositionDiscontinuity(reason: Int) {
            Log.d(TAG, "positionDiscontinuity $reason")
        }

        override fun onTimelineChanged(timeline: Timeline?, manifest: Any?, reason: Int) {
            if (timeline == null) {
                return
            }
            val periodCount = timeline.periodCount
            val windowCount = timeline.windowCount
            Log.d(TAG, "sourceInfo [periodCount=$periodCount, windowCount=$windowCount")
            for (i in 0 until Math.min(periodCount, MAX_TIMELINE_ITEM_LINES)) {
                timeline.getPeriod(i, period)
                Log.d(TAG, "  " + "period [" + getTimeString(period.durationMs) + "]")
            }
            if (periodCount > MAX_TIMELINE_ITEM_LINES) {
                Log.d(TAG, "  ...")
            }
            for (i in 0 until Math.min(windowCount, MAX_TIMELINE_ITEM_LINES)) {
                timeline.getWindow(i, window)
                Log.d(TAG, "  " + "window [" + getTimeString(window.durationMs) + ", "
                        + window.isSeekable + ", " + window.isDynamic + "]")
            }
            if (windowCount > MAX_TIMELINE_ITEM_LINES) {
                Log.d(TAG, "  ...")
            }
            Log.d(TAG, "]")
        }

        override fun onPlayerError(e: ExoPlaybackException) {
            Log.e(TAG, "playerFailed [$sessionTimeString]", e)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            Log.d(TAG, "Playback params changed, pitch: " + playbackParameters.pitch + ", speed: " + playbackParameters.speed)
        }

        override fun onSeekProcessed() {
            // do nothing
        }

        override fun onTracksChanged(ignored: TrackGroupArray, trackSelections: TrackSelectionArray) {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo
            if (mappedTrackInfo == null) {
                Log.d(TAG, "Tracks []")
                return
            }
            Log.d(TAG, "Tracks [")
            // Log tracks associated to renderers.
            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                val rendererTrackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                val trackSelection = trackSelections.get(rendererIndex)
                if (rendererTrackGroups.length > 0) {
                    Log.d(TAG, "  Renderer:$rendererIndex [")
                    for (groupIndex in 0 until rendererTrackGroups.length) {
                        val trackGroup = rendererTrackGroups.get(groupIndex)
                        val adaptiveSupport = getAdaptiveSupportString(trackGroup.length,
                                mappedTrackInfo.getAdaptiveSupport(rendererIndex, groupIndex, false))
                        Log.d(TAG, "    Group:$groupIndex, adaptive_supported=$adaptiveSupport [")
                        for (trackIndex in 0 until trackGroup.length) {
                            val status = getTrackStatusString(trackSelection, trackGroup, trackIndex)
                            val formatSupport = getFormatSupportString(
                                    mappedTrackInfo.getTrackSupport(rendererIndex, groupIndex, trackIndex))
                            Log.d(TAG, "      " + status + " Track:" + trackIndex + ", "
                                    + getFormatString(trackGroup.getFormat(trackIndex))
                                    + ", supported=" + formatSupport)
                        }
                        Log.d(TAG, "    ]")
                    }
                    // Log metadata for at most one of the tracks selected for the renderer.
                    if (trackSelection != null) {
                        for (selectionIndex in 0 until trackSelection.length()) {
                            val metadata = trackSelection.getFormat(selectionIndex).metadata
                            if (metadata != null) {
                                Log.d(TAG, "    Metadata [")
                                printMetadata(metadata, "      ")
                                Log.d(TAG, "    ]")
                                break
                            }
                        }
                    }
                    Log.d(TAG, "  ]")
                }
            }
            // Log tracks not associated with a renderer.
            val unassociatedTrackGroups = mappedTrackInfo.unmappedTrackGroups
            if (unassociatedTrackGroups.length > 0) {
                Log.d(TAG, "  Renderer:None [")
                for (groupIndex in 0 until unassociatedTrackGroups.length) {
                    Log.d(TAG, "    Group:$groupIndex [")
                    val trackGroup = unassociatedTrackGroups.get(groupIndex)
                    for (trackIndex in 0 until trackGroup.length) {
                        val status = getTrackStatusString(false)
                        val formatSupport = getFormatSupportString(
                                RendererCapabilities.FORMAT_UNSUPPORTED_TYPE)
                        Log.d(TAG, "      " + status + " Track:" + trackIndex + ", "
                                + getFormatString(trackGroup.getFormat(trackIndex))
                                + ", supported=" + formatSupport)
                    }
                    Log.d(TAG, "    ]")
                }
                Log.d(TAG, "  ]")
            }
            Log.d(TAG, "]")
        }

        // MetadataRenderer.Output

        override fun onMetadata(metadata: Metadata) {
            Log.d(TAG, "onMetadata [")
            printMetadata(metadata, "  ")
            Log.d(TAG, "]")
        }

        // StreamingDrmSessionManager.EventListener

        override fun onDrmSessionManagerError(e: Exception) {
            printInternalError("drmSessionManagerError", e)
        }

        override fun onDrmKeysLoaded() {
            Log.d(TAG, "drmKeysLoaded [$sessionTimeString]")
        }



        /**
         * @see MediaDrm.OnEventListener.onEvent
         */
        override fun onEvent(md: MediaDrm, sessionId: ByteArray?, event: Int, extra: Int, data: ByteArray?) {
            Log.d(TAG, "MediaDrm event: $event")
        }

        // Internal methods

        private fun printInternalError(type: String, e: Exception?) {
            Log.e(TAG, "internalError [$sessionTimeString, $type]", e)
        }

        private fun printMetadata(metadata: Metadata, prefix: String) {
            for (i in 0 until metadata.length()) {
                when (val entry = metadata.get(i)) {
                    is PrivFrame -> Log.d(TAG, prefix + String.format("%s: owner=%s", entry.id, entry.owner))
                    is GeobFrame -> Log.d(TAG, prefix + String.format("%s: mimeType=%s, filename=%s, description=%s",
                            entry.id, entry.mimeType, entry.filename, entry.description))
                    is ApicFrame -> Log.d(TAG, prefix + String.format("%s: mimeType=%s, description=%s",
                            entry.id, entry.mimeType, entry.description))
                    is TextInformationFrame -> Log.d(TAG, prefix + String.format("%s: description=%s", entry.id,
                            entry.description))
                    is CommentFrame -> Log.d(TAG, prefix + String.format("%s: language=%s description=%s", entry.id,
                            entry.language, entry.description))
                    is Id3Frame -> Log.d(TAG, prefix + String.format("%s", entry.id))
                    is EventMessage -> Log.d(TAG, prefix + String.format("EMSG: scheme=%s, id=%d, value=%s",
                            entry.schemeIdUri, entry.id, entry.value))
                }
            }
        }

        override fun onDownstreamFormatChanged(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, mediaLoadData: MediaSourceEventListener.MediaLoadData?) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onUpstreamDiscarded(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, mediaLoadData: MediaSourceEventListener.MediaLoadData?) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onMediaPeriodCreated(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?) {
           // TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onLoadCanceled(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, loadEventInfo: MediaSourceEventListener.LoadEventInfo?, mediaLoadData: MediaSourceEventListener.MediaLoadData?) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onMediaPeriodReleased(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onReadingStarted(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onLoadCompleted(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, loadEventInfo: MediaSourceEventListener.LoadEventInfo?, mediaLoadData: MediaSourceEventListener.MediaLoadData?) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onLoadError(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, loadEventInfo: MediaSourceEventListener.LoadEventInfo?, mediaLoadData: MediaSourceEventListener.MediaLoadData?, error: IOException?, wasCanceled: Boolean) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onLoadStarted(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, loadEventInfo: MediaSourceEventListener.LoadEventInfo?, mediaLoadData: MediaSourceEventListener.MediaLoadData?) {
            //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        companion object {

            private const val TAG = "EventLogger"
            private const val MAX_TIMELINE_ITEM_LINES = 3
            private val TIME_FORMAT: NumberFormat = NumberFormat.getInstance(Locale.US)

            init {
                TIME_FORMAT.minimumFractionDigits = 2
                TIME_FORMAT.maximumFractionDigits = 2
                TIME_FORMAT.isGroupingUsed = false
            }

            private fun getTimeString(timeMs: Long): String {
                return if (timeMs == C.TIME_UNSET) "?" else TIME_FORMAT.format((timeMs / 1000f).toDouble())
            }

            private fun getStateString(state: Int): String {
                return when (state) {
                    Player.STATE_BUFFERING -> "B"
                    Player.STATE_ENDED -> "E"
                    Player.STATE_IDLE -> "I"
                    Player.STATE_READY -> "R"
                    else -> "?"
                }
            }

            private fun getFormatSupportString(formatSupport: Int): String {

                return when (formatSupport) {
                    RendererCapabilities.FORMAT_HANDLED -> "YES"
                    RendererCapabilities.FORMAT_EXCEEDS_CAPABILITIES -> "NO_EXCEEDS_CAPABILITIES"
                    RendererCapabilities.FORMAT_UNSUPPORTED_SUBTYPE -> "NO_UNSUPPORTED_TYPE"
                    RendererCapabilities.FORMAT_UNSUPPORTED_TYPE -> "NO"
                    else -> "?"
                }
            }

            private fun getAdaptiveSupportString(trackCount: Int, adaptiveSupport: Int): String {
                if (trackCount < 2) {
                    return "N/A"
                }
                return when (adaptiveSupport) {
                    RendererCapabilities.ADAPTIVE_SEAMLESS -> "YES"
                    RendererCapabilities.ADAPTIVE_NOT_SEAMLESS -> "YES_NOT_SEAMLESS"
                    RendererCapabilities.ADAPTIVE_NOT_SUPPORTED -> "NO"
                    else -> "?"
                }
            }

            private fun getFormatString(format: Format?): String {
                if (format == null) {
                    return "null"
                }
                val builder = StringBuilder()
                builder.append("id=").append(format.id).append(", mimeType=").append(format.sampleMimeType)
                if (format.bitrate != Format.NO_VALUE) {
                    builder.append(", bitrate=").append(format.bitrate)
                }
                if (format.width != Format.NO_VALUE && format.height != Format.NO_VALUE) {
                    builder.append(", res=").append(format.width).append("x").append(format.height)
                }
                if (format.frameRate != Format.NO_VALUE.toFloat()) {
                    builder.append(", fps=").append(format.frameRate)
                }
                if (format.channelCount != Format.NO_VALUE) {
                    builder.append(", channels=").append(format.channelCount)
                }
                if (format.sampleRate != Format.NO_VALUE) {
                    builder.append(", sample_rate=").append(format.sampleRate)
                }
                if (format.language != null) {
                    builder.append(", language=").append(format.language)
                }
                return builder.toString()
            }

            private fun getTrackStatusString(selection: TrackSelection?, group: TrackGroup,
                                             trackIndex: Int): String {
                return getTrackStatusString(selection != null && selection.trackGroup === group
                        && selection.indexOf(trackIndex) != C.INDEX_UNSET)
            }

            private fun getTrackStatusString(enabled: Boolean): String {
                return if (enabled) "[X]" else "[ ]"
            }
        }

    }
}