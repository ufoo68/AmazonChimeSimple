/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.amazonchimesimple.fragment

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amazonaws.services.chime.sdk.meetings.analytics.EventAnalyticsObserver
import com.amazonaws.services.chime.sdk.meetings.analytics.EventAttributes
import com.amazonaws.services.chime.sdk.meetings.analytics.EventName
import com.amazonaws.services.chime.sdk.meetings.analytics.toJsonString
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AttendeeInfo
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoFacade
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.audio.activespeakerdetector.ActiveSpeakerObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.audio.activespeakerpolicy.DefaultActiveSpeakerPolicy
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoPauseState
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoTileObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoTileState
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.*
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCoreFactory
import com.amazonaws.services.chime.sdk.meetings.device.DeviceChangeObserver
import com.amazonaws.services.chime.sdk.meetings.device.MediaDevice
import com.amazonaws.services.chime.sdk.meetings.device.MediaDeviceType
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionCredentials
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionStatus
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionStatusCode
import com.amazonaws.services.chime.sdk.meetings.utils.logger.ConsoleLogger
import com.amazonaws.services.chime.sdk.meetings.utils.logger.LogLevel
import com.amazonaws.services.chime.sdkdemo.device.AudioDeviceManager
import com.example.amazonchimesimple.R
import com.example.amazonchimesimple.activity.MainActivity
import com.example.amazonchimesimple.adapter.VideoAdapter
import com.example.amazonchimesimple.adapter.VideoDiffCallback
import com.example.amazonchimesimple.data.VideoCollectionTile
import com.example.amazonchimesimple.model.MeetingModel
import com.example.amazonchimesimple.utils.CpuVideoProcessor
import com.example.amazonchimesimple.utils.GpuVideoProcessor
import com.example.amazonchimesimple.utils.isLandscapeMode
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MeetingFragment : Fragment(), AudioVideoObserver, VideoTileObserver,
    ActiveSpeakerObserver, DeviceChangeObserver, EventAnalyticsObserver {
    private val logger = ConsoleLogger(LogLevel.DEBUG)
    private val mutex = Mutex()
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val meetingModel: MeetingModel by lazy { ViewModelProvider(this)[MeetingModel::class.java] }

    private var deviceDialog: AlertDialog? = null
    private val gson = Gson()

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var powerManager: PowerManager
    private lateinit var credentials: MeetingSessionCredentials
    private lateinit var audioVideo: AudioVideoFacade
    private lateinit var cameraCaptureSource: CameraCaptureSource
    private lateinit var gpuVideoProcessor: GpuVideoProcessor
    private lateinit var cpuVideoProcessor: CpuVideoProcessor
    private lateinit var eglCoreFactory: EglCoreFactory
    private lateinit var listener: RosterViewEventListener

    override val scoreCallbackIntervalMs: Int? get() = 1000

    private val WEBRTC_PERMISSION_REQUEST_CODE = 1
    private val SCREEN_CAPTURE_REQUEST_CODE = 2
    private val TAG = "MeetingFragment"

    private val DATA_MESSAGE_TOPIC = "chat"

    private lateinit var buttonMute: ImageButton
    private lateinit var buttonCamera: ImageButton
    private lateinit var additionalOptionsAlertDialogBuilder: AlertDialog.Builder
    private lateinit var viewVideo: LinearLayout
    private lateinit var recyclerViewVideoCollection: RecyclerView
    private lateinit var prevVideoPageButton: Button
    private lateinit var nextVideoPageButton: Button
    private lateinit var videoTileAdapter: VideoAdapter
    private lateinit var screenTileAdapter: VideoAdapter
    private lateinit var audioDeviceManager: AudioDeviceManager

    companion object {
        fun newInstance(meetingId: String): MeetingFragment {
            val fragment = MeetingFragment()

            fragment.arguments =
                Bundle().apply { putString(MainActivity.MEETING_ID_KEY, meetingId) }
            return fragment
        }
    }

    interface RosterViewEventListener {
        fun onLeaveMeeting()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if (context is RosterViewEventListener) {
            listener = context
        } else {
            logger.error(TAG, "$context must implement RosterViewEventListener.")
            throw ClassCastException("$context must implement RosterViewEventListener.")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View = inflater.inflate(R.layout.fragment_meeting, container, false)
        val activity = activity as Context

        credentials = (activity as MainActivity).getMeetingSessionCredentials()
        audioVideo = activity.getAudioVideo()
        eglCoreFactory = activity.getEglCoreFactory()
        cameraCaptureSource = activity.getCameraCaptureSource()
        gpuVideoProcessor = activity.getGpuVideoProcessor()
        cpuVideoProcessor = activity.getCpuVideoProcessor()
        audioDeviceManager = AudioDeviceManager(audioVideo)

        mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager

        view.findViewById<TextView>(R.id.textViewMeetingId)?.text = arguments?.getString(
            MainActivity.MEETING_ID_KEY
        ) as String
        setupButtonsBar(view)
        setupSubViews(view)
        setupAdditionalOptionsDialog()

        subscribeToAttendeeChangeHandlers()
        audioVideo.start()
        audioVideo.startRemoteVideo()
        return view
    }

    private fun setupButtonsBar(view: View) {
        buttonMute = view.findViewById(R.id.buttonMute)
        buttonMute.setImageResource(if (meetingModel.isMuted) R.drawable.button_mute_on else R.drawable.button_mute)
        buttonMute.setOnClickListener { toggleMute() }

        buttonCamera = view.findViewById(R.id.buttonCamera)
        buttonCamera.setImageResource(if (meetingModel.isCameraOn) R.drawable.button_camera_on else R.drawable.button_camera)
        buttonCamera.setOnClickListener { toggleVideo() }

        view.findViewById<ImageButton>(R.id.buttonMore)
            ?.setOnClickListener { toggleAdditionalOptionsMenu() }

        view.findViewById<ImageButton>(R.id.buttonSpeaker)
            ?.setOnClickListener { toggleSpeaker() }

        view.findViewById<ImageButton>(R.id.buttonLeave)
            ?.setOnClickListener { endMeeting() }
    }

    private fun setupSubViews(view: View) {
        viewVideo = view.findViewById(R.id.subViewVideo)
        viewVideo.visibility = View.VISIBLE

        prevVideoPageButton = view.findViewById(R.id.prevVideoPageButton)
        prevVideoPageButton.setOnClickListener {
            if (meetingModel.canGoToPrevVideoPage()) {
                meetingModel.currentVideoPageIndex -= 1
                onVideoPageUpdated()
            }
        }

        nextVideoPageButton = view.findViewById(R.id.nextVideoPageButton)
        nextVideoPageButton.setOnClickListener {
            if (meetingModel.canGoToNextVideoPage()) {
                meetingModel.currentVideoPageIndex += 1
                onVideoPageUpdated()
            }
        }

        recyclerViewVideoCollection =
            view.findViewById(R.id.recyclerViewVideoCollection)
        recyclerViewVideoCollection.layoutManager = createLinearLayoutManagerForOrientation()
        videoTileAdapter = VideoAdapter(
            meetingModel.videoStatesInCurrentPage,
            meetingModel.userPausedVideoTileIds,
            audioVideo,
            cameraCaptureSource,
            context,
            logger
        )
        recyclerViewVideoCollection.adapter = videoTileAdapter
    }

    private fun createLinearLayoutManagerForOrientation(): LinearLayoutManager {
        return if (isLandscapeMode(activity)) {
            LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        } else {
            LinearLayoutManager(activity)
        }
    }

    private fun setupAdditionalOptionsDialog() {
        additionalOptionsAlertDialogBuilder = AlertDialog.Builder(activity)
        additionalOptionsAlertDialogBuilder.setTitle(R.string.alert_title_additional_options)
        additionalOptionsAlertDialogBuilder.setNegativeButton(R.string.cancel) { dialog, _ ->
            dialog.dismiss()
            meetingModel.isAdditionalOptionsDialogOn = false
        }
        additionalOptionsAlertDialogBuilder.setOnDismissListener {
            meetingModel.isAdditionalOptionsDialogOn = false
        }

        if (meetingModel.isAdditionalOptionsDialogOn) {
            additionalOptionsAlertDialogBuilder.create().show()
        }
    }

    override fun onAudioDeviceChanged(freshAudioDeviceList: List<MediaDevice>) {
        meetingModel.currentMediaDevices = freshAudioDeviceList
            .filter { device -> device.type != MediaDeviceType.OTHER }
        audioDeviceManager.reconfigureActiveAudioDevice()
    }

    override fun onActiveSpeakerDetected(attendeeInfo: Array<AttendeeInfo>) {
        uiScope.launch {
            mutex.withLock {
                meetingModel.updateRemoteVideoStatesBasedOnActiveSpeakers(attendeeInfo)
                onVideoPageUpdated()
            }
        }
    }

    override fun onActiveSpeakerScoreChanged(scores: Map<AttendeeInfo, Double>) {
        val scoresStr =
            scores.map { entry -> "${entry.key.externalUserId}: ${entry.value}" }.joinToString(",")
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name,
            scoresStr,
            LogLevel.DEBUG
        )
    }

    private fun toggleMute() {
        if (meetingModel.isMuted) {
            audioVideo.realtimeLocalUnmute()
            buttonMute.setImageResource(R.drawable.button_mute)
        } else {
            audioVideo.realtimeLocalMute()
            buttonMute.setImageResource(R.drawable.button_mute_on)
        }
        meetingModel.isMuted = !meetingModel.isMuted
    }

    private fun toggleSpeaker() {
        meetingModel.currentMediaDevices = audioVideo.listAudioDevices().filter { it.type != MediaDeviceType.OTHER }
        deviceDialog?.show()
        meetingModel.isDeviceListDialogOn = true
    }

    private fun setVoiceFocusEnabled(enabled: Boolean) {
        val action = if (enabled) "enable" else "disable"

        val success = audioVideo.realtimeSetVoiceFocusEnabled(enabled)

        if (success) {
            notifyHandler("Voice Focus ${action}d")
        } else {
            notifyHandler("Failed to $action Voice Focus")
        }
    }

    private fun toggleVideo() {
        if (meetingModel.isCameraOn) {
            stopLocalVideo()
        } else {
            startLocalVideo()
        }
        meetingModel.isCameraOn = !meetingModel.isCameraOn
    }

    private fun toggleAdditionalOptionsMenu() {
        additionalOptionsAlertDialogBuilder.create()
        additionalOptionsAlertDialogBuilder.show()
        meetingModel.isAdditionalOptionsDialogOn = true
    }

    private fun toggleFlashlight() {
        logger.info(
            TAG,
            "Toggling flashlight from ${cameraCaptureSource.torchEnabled} to ${!cameraCaptureSource.torchEnabled}"
        )
        if (!meetingModel.isUsingCameraCaptureSource) {
            logger.warn(TAG, "Cannot toggle flashlight without using custom camera capture source")
            Toast.makeText(
                context,
                getString(R.string.user_notification_flashlight_custom_source_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val desiredFlashlightEnabled = !cameraCaptureSource.torchEnabled
        cameraCaptureSource.torchEnabled = desiredFlashlightEnabled
        if (cameraCaptureSource.torchEnabled != desiredFlashlightEnabled) {
            logger.warn(TAG, "Flashlight failed to toggle")
            Toast.makeText(
                context,
                getString(R.string.user_notification_flashlight_unavailable_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
    }

    private fun toggleCpuDemoFilter() {
        if (!meetingModel.isUsingCameraCaptureSource) {
            logger.warn(TAG, "Cannot toggle filter without using custom camera capture source")
            Toast.makeText(
                context,
                getString(R.string.user_notification_filter_custom_source_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (meetingModel.isUsingGpuVideoProcessor) {
            logger.warn(TAG, "Cannot toggle filter when other filter is enabled")
            Toast.makeText(
                context,
                getString(R.string.user_notification_filter_both_enabled_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        logger.info(
            TAG,
            "Toggling CPU demo filter from $meetingModel.isUsingCpuVideoProcessor to ${!meetingModel.isUsingCpuVideoProcessor}"
        )
        meetingModel.isUsingCpuVideoProcessor = !meetingModel.isUsingCpuVideoProcessor
        if (meetingModel.isLocalVideoStarted) {
            startLocalVideo()
        }
    }

    private fun toggleGpuDemoFilter() {
        if (!meetingModel.isUsingCameraCaptureSource) {
            logger.warn(TAG, "Cannot toggle filter without using custom camera capture source")
            Toast.makeText(
                context,
                getString(R.string.user_notification_filter_custom_source_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (meetingModel.isUsingCpuVideoProcessor) {
            logger.warn(TAG, "Cannot toggle filter when other filter is enabled")
            Toast.makeText(
                context,
                getString(R.string.user_notification_filter_both_enabled_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        logger.info(
            TAG,
            "Toggling GPU demo filter from $meetingModel.isUsingGpuVideoProcessor to ${!meetingModel.isUsingGpuVideoProcessor}"
        )
        meetingModel.isUsingGpuVideoProcessor = !meetingModel.isUsingGpuVideoProcessor
        if (meetingModel.isLocalVideoStarted) {
            startLocalVideo()
        }
    }

    private fun startLocalVideo() {
        meetingModel.isLocalVideoStarted = true
        if (meetingModel.isUsingCameraCaptureSource) {
            if (meetingModel.isUsingGpuVideoProcessor) {
                cameraCaptureSource.addVideoSink(gpuVideoProcessor)
                audioVideo.startLocalVideo(gpuVideoProcessor)
            } else if (meetingModel.isUsingCpuVideoProcessor) {
                cameraCaptureSource.addVideoSink(cpuVideoProcessor)
                audioVideo.startLocalVideo(cpuVideoProcessor)
            } else {
                audioVideo.startLocalVideo(cameraCaptureSource)
            }
            cameraCaptureSource.start()
        } else {
            audioVideo.startLocalVideo()
        }
        buttonCamera.setImageResource(R.drawable.button_camera_on)
    }

    private fun stopLocalVideo() {
        meetingModel.isLocalVideoStarted = false
        if (meetingModel.isUsingCameraCaptureSource) {
            cameraCaptureSource.stop()
        }
        audioVideo.stopLocalVideo()
        buttonCamera.setImageResource(R.drawable.button_camera)
    }

    private fun onVideoPageUpdated() {
        val oldList = mutableListOf<VideoCollectionTile>()
        oldList.addAll(meetingModel.videoStatesInCurrentPage)

        // Recalculate videos in the current page and notify videoTileAdapter
        meetingModel.updateVideoStatesInCurrentPage()
        revalidateVideoPageIndex()

        val newList = mutableListOf<VideoCollectionTile>()
        newList.addAll(meetingModel.videoStatesInCurrentPage)

        val videoDiffCallback = VideoDiffCallback(oldList, newList)
        val videoDiffResult: DiffUtil.DiffResult = DiffUtil.calculateDiff(videoDiffCallback)

        videoDiffResult.dispatchUpdatesTo(videoTileAdapter)

        // Pause/Resume remote videos accordingly based on videoTileState and the tab that user is on
        meetingModel.remoteVideoTileStates.forEach {
            // Resume paused videos in the current page if user is on Video tab and it was not manually paused by user
            if (meetingModel.videoStatesInCurrentPage.contains(it) && !meetingModel.userPausedVideoTileIds.contains(it.videoTileState.tileId)) {
                if (it.videoTileState.pauseState == VideoPauseState.PausedByUserRequest) {
                    audioVideo.resumeRemoteVideoTile(it.videoTileState.tileId)
                }
            } else {
                if (it.videoTileState.pauseState != VideoPauseState.PausedByUserRequest) {
                    audioVideo.pauseRemoteVideoTile(it.videoTileState.tileId)
                }
            }
        }

        // update video pagination control buttons states
        prevVideoPageButton.isEnabled = meetingModel.canGoToPrevVideoPage()
        nextVideoPageButton.isEnabled = meetingModel.canGoToNextVideoPage()
    }

    private fun revalidateVideoPageIndex() {
        while (meetingModel.canGoToPrevVideoPage() && meetingModel.remoteVideoCountInCurrentPage() == 0) {
            meetingModel.currentVideoPageIndex -= 1
            onVideoPageUpdated()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            WEBRTC_PERMISSION_REQUEST_CODE -> {
                val isMissingPermission: Boolean =
                    grantResults.isEmpty() || grantResults.any { PackageManager.PERMISSION_GRANTED != it }

                if (isMissingPermission) {
                    Toast.makeText(
                        context,
                        getString(R.string.user_notification_permission_error),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    startLocalVideo()
                }
                return
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (SCREEN_CAPTURE_REQUEST_CODE == requestCode) {
            if (resultCode != Activity.RESULT_OK) {
                Toast.makeText(
                    context,
                    getString(R.string.user_notification_screen_share_permission_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showVideoTile(tileState: VideoTileState) {
        val videoCollectionTile = createVideoCollectionTile(tileState)
        if (tileState.isContent) {
            meetingModel.currentScreenTiles.add(videoCollectionTile)
            screenTileAdapter.notifyDataSetChanged()

            // Currently not in the Screen tab, no need to render the video tile
            audioVideo.pauseRemoteVideoTile(tileState.tileId)

        } else {
            if (tileState.isLocalTile) {
                meetingModel.localVideoTileState = videoCollectionTile
                onVideoPageUpdated()
            } else {
                meetingModel.remoteVideoTileStates.add(videoCollectionTile)
                onVideoPageUpdated()
            }
        }
    }

    private fun createVideoCollectionTile(tileState: VideoTileState): VideoCollectionTile {
        val attendeeId = tileState.attendeeId
        val attendeeName = meetingModel.currentRoster[attendeeId]?.attendeeName ?: ""
        return VideoCollectionTile(attendeeName, tileState)
    }

    override fun onAudioSessionStartedConnecting(reconnecting: Boolean) {
        notifyHandler(
            "Audio started connecting. reconnecting: $reconnecting"
        )
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name,
            "reconnecting: $reconnecting"
        )
    }

    override fun onAudioSessionStarted(reconnecting: Boolean) {
        notifyHandler(
            "Audio successfully started. reconnecting: $reconnecting"
        )
        // Start Voice Focus as soon as audio session started
        setVoiceFocusEnabled(true)
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name,
            "reconnecting: $reconnecting"
        )

        val cachedDevice = (activity as MainActivity).getCachedDevice()
        cachedDevice?.let {
            audioVideo.chooseAudioDevice(it)
            audioDeviceManager.setActiveAudioDevice(it)
            (activity as MainActivity).resetCachedDevice()
        }
    }

    override fun onAudioSessionDropped() {
        notifyHandler("Audio session dropped")
        logWithFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    override fun onAudioSessionStopped(sessionStatus: MeetingSessionStatus) {
        notifyHandler(
            "Audio stopped for reason: ${sessionStatus.statusCode}"
        )
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name,
            "${sessionStatus.statusCode}"
        )

        if (sessionStatus.statusCode != MeetingSessionStatusCode.OK) {
            endMeeting()
        }
        listener.onLeaveMeeting()
    }

    override fun onAudioSessionCancelledReconnect() {
        notifyHandler("Audio cancelled reconnecting")
        logWithFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    override fun onConnectionRecovered() {
        notifyHandler(
            "Connection quality has recovered"
        )
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name
        )
    }

    override fun onConnectionBecamePoor() {
        notifyHandler(
            "Connection quality has become poor"
        )
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name
        )
    }

    override fun onVideoSessionStartedConnecting() {
        notifyHandler("Video started connecting.")
        logWithFunctionName(object {}.javaClass.enclosingMethod?.name)
    }

    override fun onVideoSessionStarted(sessionStatus: MeetingSessionStatus) {
        val message =
            if (sessionStatus.statusCode == MeetingSessionStatusCode.VideoAtCapacityViewOnly) "Video encountered an error: ${sessionStatus.statusCode}" else "Video successfully started: ${sessionStatus.statusCode}"

        notifyHandler(message)
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name,
            "${sessionStatus.statusCode}"
        )
    }

    override fun onVideoSessionStopped(sessionStatus: MeetingSessionStatus) {
        notifyHandler(
            "Video stopped for reason: ${sessionStatus.statusCode}"
        )
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name,
            "${sessionStatus.statusCode}"
        )
    }

    override fun onVideoTileAdded(tileState: VideoTileState) {
        uiScope.launch {
            logger.info(
                TAG,
                "Video tile added, tileId: ${tileState.tileId}, attendeeId: ${tileState.attendeeId}" +
                        ", isContent ${tileState.isContent} with size ${tileState.videoStreamContentWidth}*${tileState.videoStreamContentHeight}"
            )
            if (tileState.isContent && meetingModel.currentScreenTiles.none { it.videoTileState.tileId == tileState.tileId }) {
                showVideoTile(tileState)
            } else {
                if (tileState.isLocalTile) {
                    showVideoTile(tileState)
                } else if (meetingModel.remoteVideoTileStates.none { it.videoTileState.tileId == tileState.tileId }) {
                    showVideoTile(tileState)
                }
            }
        }
    }

    override fun onVideoTileRemoved(tileState: VideoTileState) {
        uiScope.launch {
            val tileId: Int = tileState.tileId

            logger.info(
                TAG,
                "Video track removed, tileId: $tileId, attendeeId: ${tileState.attendeeId}"
            )
            audioVideo.unbindVideoView(tileId)
            if (tileState.isContent) {
                meetingModel.currentScreenTiles.removeAll { it.videoTileState.tileId == tileId }
                screenTileAdapter.notifyDataSetChanged()
            } else {
                if (meetingModel.localVideoTileState?.videoTileState?.tileId == tileId) {
                    meetingModel.localVideoTileState = null
                } else {
                    meetingModel.remoteVideoTileStates.removeAll { it.videoTileState.tileId == tileId }
                }
                onVideoPageUpdated()
            }
        }
    }

    override fun onVideoTilePaused(tileState: VideoTileState) {
        if (tileState.pauseState == VideoPauseState.PausedForPoorConnection) {
            val collection = if (tileState.isContent) meetingModel.currentScreenTiles else meetingModel.remoteVideoTileStates
            collection.find { it.videoTileState.tileId == tileState.tileId }.apply {
                this?.setPauseMessageVisibility(View.VISIBLE)
            }
            val attendeeName =
                meetingModel.currentRoster[tileState.attendeeId]?.attendeeName ?: ""
            logWithFunctionName(
                object {}.javaClass.enclosingMethod?.name,
                "$attendeeName video paused"
            )
        }
    }

    override fun onVideoTileResumed(tileState: VideoTileState) {
        val collection = if (tileState.isContent) meetingModel.currentScreenTiles else meetingModel.remoteVideoTileStates
        collection.find { it.videoTileState.tileId == tileState.tileId }.apply {
            this?.setPauseMessageVisibility(View.INVISIBLE)
        }
        val attendeeName = meetingModel.currentRoster[tileState.attendeeId]?.attendeeName ?: ""
        logWithFunctionName(
            object {}.javaClass.enclosingMethod?.name,
            "$attendeeName video resumed"
        )
    }

    override fun onVideoTileSizeChanged(tileState: VideoTileState) {
        logger.info(
            TAG,
            "Video stream content size changed to ${tileState.videoStreamContentWidth}*${tileState.videoStreamContentHeight} for tileId: ${tileState.tileId}"
        )
    }

    private fun notifyHandler(
        toastMessage: String
    ) {
        uiScope.launch {
            activity?.let {
                Toast.makeText(activity, toastMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logWithFunctionName(
        fnName: String?,
        msg: String = "",
        logLevel: LogLevel = LogLevel.INFO
    ) {
        val newMsg = if (fnName == null) msg else "[Function] [$fnName]: $msg"
        when (logLevel) {
            LogLevel.DEBUG -> logger.debug(TAG, newMsg)
            else -> logger.info(TAG, newMsg)
        }
    }

    private fun subscribeToAttendeeChangeHandlers() {
        audioVideo.addAudioVideoObserver(this)
        audioVideo.addDeviceChangeObserver(this)
        audioVideo.addVideoTileObserver(this)
        audioVideo.addActiveSpeakerObserver(DefaultActiveSpeakerPolicy(), this)
        audioVideo.addEventAnalyticsObserver(this)
    }

    private fun unsubscribeFromAttendeeChangeHandlers() {
        audioVideo.removeAudioVideoObserver(this)
        audioVideo.removeDeviceChangeObserver(this)
        audioVideo.removeRealtimeDataMessageObserverFromTopic(DATA_MESSAGE_TOPIC)
        audioVideo.removeVideoTileObserver(this)
        audioVideo.removeActiveSpeakerObserver(this)
    }

    private fun endMeeting() {
        if (meetingModel.localVideoTileState != null) {
            audioVideo.unbindVideoView(meetingModel.localTileId)
        }
        meetingModel.remoteVideoTileStates.forEach {
            audioVideo.unbindVideoView(it.videoTileState.tileId)
        }
        meetingModel.currentScreenTiles.forEach {
            audioVideo.unbindVideoView(it.videoTileState.tileId)
        }
        audioVideo.stopLocalVideo()
        audioVideo.stopRemoteVideo()
        audioVideo.stopRemoteVideo()
        audioVideo.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceDialog?.dismiss()
        unsubscribeFromAttendeeChangeHandlers()
    }

    // Handle backgrounded app
    override fun onStart() {
        super.onStart()
        if (meetingModel.wasLocalVideoStarted) {
            startLocalVideo()
        }
        audioVideo.startRemoteVideo()
    }

    override fun onStop() {
        super.onStop()
        meetingModel.wasLocalVideoStarted = meetingModel.isLocalVideoStarted
        if (meetingModel.wasLocalVideoStarted) {
            stopLocalVideo()
        }
        audioVideo.stopRemoteVideo()
    }

    override fun onEventReceived(name: EventName, attributes: EventAttributes) {
        // Store the logs
        attributes.putAll(audioVideo.getCommonEventAttributes())

        logger.info(TAG, "$name ${attributes.toJsonString()}")
        when (name) {
            EventName.meetingStartSucceeded ->
                logger.info(TAG, "Meeting started on : ${audioVideo.getCommonEventAttributes().toJsonString()}")
            EventName.meetingEnded, EventName.meetingFailed -> {
                logger.info(TAG, "Meeting history: ${gson.toJson(audioVideo.getMeetingHistory())}")
            }
            else -> Unit
        }
    }
}
