/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.amazonchimesimple.model

import androidx.lifecycle.ViewModel
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoFacade
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.CameraCaptureSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.DefaultEglCoreFactory
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCoreFactory
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSession

class MeetingSessionModel : ViewModel() {
    private lateinit var meetingSession: MeetingSession

    fun setMeetingSession(meetingSession: MeetingSession) {
        this.meetingSession = meetingSession
    }

    val audioVideo: AudioVideoFacade
        get() = meetingSession.audioVideo

    // Graphics/capture related objects
    val eglCoreFactory: EglCoreFactory = DefaultEglCoreFactory()
    lateinit var cameraCaptureSource: CameraCaptureSource
}
