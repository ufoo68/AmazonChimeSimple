package com.example.amazonchimesimple.fragment

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoFacade
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.DefaultVideoRenderView
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoTileObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoTileState
import com.example.amazonchimesimple.R
import com.example.amazonchimesimple.activity.MainActivity
import com.example.amazonchimesimple.utils.leaveMeeting
import kotlinx.coroutines.*

class MeetingFragment : Fragment(), VideoTileObserver {
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private var deviceDialog: AlertDialog? = null

    private lateinit var audioVideo: AudioVideoFacade
    private lateinit var listener: EventListener

    private val WEBRTC_PERMISSION_REQUEST_CODE = 1

    companion object {
        fun newInstance(meetingId: String): MeetingFragment {
            val fragment = MeetingFragment()

            fragment.arguments =
                Bundle().apply { putString(MainActivity.MEETING_ID_KEY, meetingId) }
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View = inflater.inflate(R.layout.fragment_meeting, container, false)
        val activity = activity as Context
        audioVideo = (activity as MainActivity).getAudioVideo()
        subscribeToAttendeeChangeHandlers()
        audioVideo.start()
        audioVideo.startRemoteVideo()
        view.findViewById<Button>(R.id.meeting_leave)
            ?.setOnClickListener { endMeeting() }
        return view
    }

    private fun startLocalVideo() {
        audioVideo.startLocalVideo()
    }

    private fun stopLocalVideo() {
        audioVideo.stopLocalVideo()
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

    private fun showVideoTile(tileState: VideoTileState) {
        val activity = activity as Context
        val defaultVideoRenderView = DefaultVideoRenderView(activity)
        val layout = view?.findViewById<LinearLayout>(R.id.meeting_layout)
        layout?.addView(defaultVideoRenderView, ViewGroup.LayoutParams(500, 500))
        audioVideo.bindVideoView(defaultVideoRenderView,tileState.tileId)
    }

    override fun onVideoTileAdded(tileState: VideoTileState) {
        showVideoTile(tileState)
    }

    override fun onVideoTileRemoved(tileState: VideoTileState) {
        audioVideo.unbindVideoView(tileState.tileId)
    }

    override fun onVideoTilePaused(tileState: VideoTileState) {
        TODO()
    }

    override fun onVideoTileResumed(tileState: VideoTileState) {
        TODO()
    }

    override fun onVideoTileSizeChanged(tileState: VideoTileState) {
        TODO()
    }


    private fun subscribeToAttendeeChangeHandlers() {
        audioVideo.addVideoTileObserver(this)
    }

    private fun unsubscribeFromAttendeeChangeHandlers() {
        audioVideo.removeVideoTileObserver(this)
    }

    interface EventListener {
        fun onLeaveMeeting()
    }

    private fun endMeeting() {
        uiScope.launch {
            leaveMeeting(
                ioDispatcher,
                getString(R.string.test_url),
                getString(R.string.test_meeting_id)
            )
        }
        audioVideo.stopLocalVideo()
        audioVideo.stopRemoteVideo()
        audioVideo.stopRemoteVideo()
        audioVideo.stop()
        listener.onLeaveMeeting()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceDialog?.dismiss()
        unsubscribeFromAttendeeChangeHandlers()
    }

    override fun onStart() {
        super.onStart()
        startLocalVideo()
        audioVideo.startRemoteVideo()
    }

    override fun onStop() {
        super.onStop()
        stopLocalVideo()
        audioVideo.stopRemoteVideo()
    }
}
