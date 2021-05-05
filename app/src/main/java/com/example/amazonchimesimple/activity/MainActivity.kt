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
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.CameraCaptureSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.DefaultCameraCaptureSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.DefaultSurfaceTextureCaptureSourceFactory
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCoreFactory
import com.amazonaws.services.chime.sdk.meetings.device.MediaDevice
import com.amazonaws.services.chime.sdk.meetings.session.*
import com.amazonaws.services.chime.sdk.meetings.utils.logger.ConsoleLogger
import com.amazonaws.services.chime.sdk.meetings.utils.logger.LogLevel
import com.example.amazonchimesimple.device.ScreenShareManager
import com.example.amazonchimesimple.R
import com.example.amazonchimesimple.data.JoinMeetingResponse
import com.example.amazonchimesimple.fragment.DeviceManagementFragment
import com.example.amazonchimesimple.fragment.MeetingFragment
import com.example.amazonchimesimple.model.MeetingSessionModel
import com.example.amazonchimesimple.utils.CpuVideoProcessor
import com.example.amazonchimesimple.utils.GpuVideoProcessor
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity(),
    DeviceManagementFragment.DeviceManagementEventListener,
    MeetingFragment.RosterViewEventListener {
    private val logger = ConsoleLogger(LogLevel.INFO)
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val meetingSessionModel: MeetingSessionModel by lazy { ViewModelProvider(this)[MeetingSessionModel::class.java] }
    private var cachedDevice: MediaDevice? = null
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
        private val ALLOWED_CHARACTERS = "0123456789qwertyuiopasdfghjklzxcvbnm"
        const val MEETING_ID_KEY = "MEETING_ID"
        const val NAME_KEY = "NAME"
    }

    private fun getRandomString(sizeOfRandomString: Int = 8): String {
        val random = Random()
        val sb = StringBuilder(sizeOfRandomString)
        for (i in 0 until sizeOfRandomString)
            sb.append(ALLOWED_CHARACTERS[random.nextInt(ALLOWED_CHARACTERS.length)])
        return sb.toString()
    }

    private suspend fun joinMeeting(
            meetingUrl: String,
            meetingId: String?,
            attendeeName: String?
    ): String? {
        return withContext(ioDispatcher) {
            val url = if (meetingUrl.endsWith("/")) meetingUrl else "$meetingUrl/"
            val serverUrl = URL("${url}join")
            val sendDataJson = "{\"title\":\"${meetingId}\",\"name\":\"${attendeeName}\"}"
            val bodyData = sendDataJson.toByteArray()
            try {
                val response = StringBuffer()
                with(serverUrl.openConnection() as HttpURLConnection) {
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true

                    setFixedLengthStreamingMode(bodyData.size)
                    setRequestProperty("Content-type", "application/json; charset=utf-8")
                    outputStream.write(bodyData)
                    outputStream.flush()
                    outputStream.close()

                    BufferedReader(InputStreamReader(inputStream)).use {
                        var inputLine = it.readLine()
                        while (inputLine != null) {
                            response.append(inputLine)
                            inputLine = it.readLine()
                        }
                        it.close()
                    }

                    if (responseCode == 200) {
                        response.toString()
                    } else {
                        logger.error(TAG, "Unable to join meeting. Response code: $responseCode")
                        null
                    }
                }
            } catch (exception: Exception) {
                logger.error(TAG, "There was an exception while joining the meeting: $exception")
                null
            }
        }
    }

    private fun hasPermissionsAlready(): Boolean {
        return WEBRTC_PERM.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        meetingId = "testId"
        name = getRandomString()
        meetingUrl = getString(R.string.test_url)
        if (hasPermissionsAlready()) {
            uiScope.launch {
                val meetingResponseJson: String? = joinMeeting(meetingUrl, meetingId, name)
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
                meetingSessionModel.cpuVideoProcessor = CpuVideoProcessor(logger, meetingSessionModel.eglCoreFactory)
                meetingSessionModel.gpuVideoProcessor = GpuVideoProcessor(logger, meetingSessionModel.eglCoreFactory)

                val deviceManagementFragment = DeviceManagementFragment.newInstance(meetingId, name)
                supportFragmentManager
                    .beginTransaction()
                    .add(R.id.root_layout, deviceManagementFragment, "deviceManagement")
                    .commit()
            }
        } else {
            ActivityCompat.requestPermissions(this, WEBRTC_PERM, WEBRTC_PERMISSION_REQUEST_CODE)
        }
    }
    override fun onJoinMeetingClicked() {
        val rosterViewFragment = MeetingFragment.newInstance(meetingId)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.root_layout, rosterViewFragment, "rosterViewFragment")
            .commit()
    }

    override fun onCachedDeviceSelected(mediaDevice: MediaDevice) {
        cachedDevice = mediaDevice
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
        meetingSessionModel.gpuVideoProcessor.release()
        meetingSessionModel.cpuVideoProcessor.release()
        meetingSessionModel.screenShareManager?.stop()
        meetingSessionModel.screenShareManager?.release()
    }

    fun getAudioVideo(): AudioVideoFacade = meetingSessionModel.audioVideo

    fun getMeetingSessionConfiguration(): MeetingSessionConfiguration = meetingSessionModel.configuration

    fun getMeetingSessionCredentials(): MeetingSessionCredentials = meetingSessionModel.credentials

    fun getCachedDevice(): MediaDevice? = cachedDevice
    fun resetCachedDevice() {
        cachedDevice = null
    }
    fun getEglCoreFactory(): EglCoreFactory = meetingSessionModel.eglCoreFactory

    fun getCameraCaptureSource(): CameraCaptureSource = meetingSessionModel.cameraCaptureSource

    fun getGpuVideoProcessor(): GpuVideoProcessor = meetingSessionModel.gpuVideoProcessor

    fun getCpuVideoProcessor(): CpuVideoProcessor = meetingSessionModel.cpuVideoProcessor

    fun getScreenShareManager(): ScreenShareManager? = meetingSessionModel.screenShareManager

    fun setScreenShareManager(screenShareManager: ScreenShareManager?) {
        meetingSessionModel.screenShareManager = screenShareManager
    }

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
