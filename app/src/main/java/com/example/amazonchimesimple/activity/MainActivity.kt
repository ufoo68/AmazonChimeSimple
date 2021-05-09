package com.example.amazonchimesimple.activity

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoFacade
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.DefaultCameraCaptureSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.DefaultSurfaceTextureCaptureSourceFactory
import com.amazonaws.services.chime.sdk.meetings.session.*
import com.amazonaws.services.chime.sdk.meetings.utils.logger.ConsoleLogger
import com.amazonaws.services.chime.sdk.meetings.utils.logger.LogLevel
import com.example.amazonchimesimple.R
import com.example.amazonchimesimple.data.JoinMeetingResponse
import com.example.amazonchimesimple.fragment.MeetingFragment
import com.example.amazonchimesimple.model.MeetingSessionModel
import com.example.amazonchimesimple.utils.*
import com.google.gson.Gson
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(),
    MeetingFragment.EventListener {
    private val logger = ConsoleLogger(LogLevel.INFO)
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val meetingSessionModel: MeetingSessionModel by lazy { ViewModelProvider(this)[MeetingSessionModel::class.java] }
    private val gson = Gson()

    private lateinit var meetingId: String
    private lateinit var name: String
    private lateinit var meetingUrl: String
    private val TAG = "mainActivity"

    private val WEBRTC_PERMISSION_REQUEST_CODE = 1

    private val WEBRTC_PERM = arrayOf(
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
    )

    companion object {
        const val MEETING_ID_KEY = "MEETING_ID"
    }

    private fun hasPermissionsAlready(): Boolean {
        return WEBRTC_PERM.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        meetingId = getString(R.string.test_meeting_id)
        name = getRandomString()
        meetingUrl = getString(R.string.test_url)
        if (hasPermissionsAlready()) {
            uiScope.launch {
                val meetingResponseJson: String? = joinMeeting(ioDispatcher, meetingUrl, meetingId, name)
                logger.debug(TAG, "Get meeting response: ${meetingResponseJson}")
                val sessionConfig = createSessionConfiguration(meetingResponseJson)
                val meetingSession = sessionConfig?.let {
                    logger.info(TAG, "Creating meeting session for meeting Id: $meetingId")

                    DefaultMeetingSession(
                        it,
                        logger,
                        applicationContext,
                        meetingSessionModel.eglCoreFactory
                    )
                }

                if (meetingSession == null) {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.user_notification_meeting_start_error),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                } else {
                    meetingSessionModel.setMeetingSession(meetingSession)
                }

                val surfaceTextureCaptureSourceFactory = DefaultSurfaceTextureCaptureSourceFactory(logger, meetingSessionModel.eglCoreFactory)
                meetingSessionModel.cameraCaptureSource = DefaultCameraCaptureSource(applicationContext, logger, surfaceTextureCaptureSourceFactory).apply {
                    eventAnalyticsController = meetingSession?.eventAnalyticsController
                }

                val meetingFragment = MeetingFragment.newInstance(meetingId)
                supportFragmentManager
                    .beginTransaction()
                    .add(R.id.root_layout, meetingFragment, "meeting")
                    .commit()
            }
        } else {
            ActivityCompat.requestPermissions(this, WEBRTC_PERM, WEBRTC_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onLeaveMeeting() {
        onBackPressed()
    }

    override fun onDestroy() {
        if (isFinishing) {
            cleanup()
        }
        super.onDestroy()
    }

    private fun cleanup() {
        meetingSessionModel.audioVideo.stopLocalVideo()
        meetingSessionModel.audioVideo.stopRemoteVideo()
        meetingSessionModel.audioVideo.stopContentShare()
        meetingSessionModel.audioVideo.stop()
        meetingSessionModel.cameraCaptureSource.stop()
    }

    fun getAudioVideo(): AudioVideoFacade = meetingSessionModel.audioVideo

    private fun urlRewriter(url: String): String {
        // You can change urls by url.replace("example.com", "my.example.com")
        return url
    }

    private fun createSessionConfiguration(response: String?): MeetingSessionConfiguration? {
        if (response.isNullOrBlank()) return null

        return try {
            val joinMeetingResponse = gson.fromJson(response, JoinMeetingResponse::class.java)
            MeetingSessionConfiguration(
                CreateMeetingResponse(joinMeetingResponse.joinInfo.meetingResponse.meeting),
                CreateAttendeeResponse(joinMeetingResponse.joinInfo.attendeeResponse.attendee),
                ::urlRewriter
            )
        } catch (exception: Exception) {
            logger.error(
                TAG,
                "Error creating session configuration: ${exception.localizedMessage}"
            )
            null
        }
    }
}
