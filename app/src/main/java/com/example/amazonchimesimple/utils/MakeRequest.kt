package com.example.amazonchimesimple.utils

import com.amazonaws.services.chime.sdk.meetings.utils.logger.ConsoleLogger
import com.amazonaws.services.chime.sdk.meetings.utils.logger.LogLevel
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private val gson = Gson()
private val logger = ConsoleLogger(LogLevel.DEBUG)

private val TAG = "apiRequest"

suspend fun leaveMeeting(
    ioDispatcher: CoroutineDispatcher,
    meetingUrl: String,
    meetingId: String?
): String? {
    return withContext(ioDispatcher) {
        val url = if (meetingUrl.endsWith("/")) meetingUrl else "$meetingUrl/"
        val serverUrl = URL("${url}leave")
        try {
            val response = StringBuffer()
            with(serverUrl.openConnection() as HttpURLConnection) {
                requestMethod = "POST"
                doInput = true
                doOutput = true

                val body = gson.toJson(
                    mutableMapOf(
                        "title" to meetingId
                    )
                )

                setRequestProperty("Content-type", "application/json; charset=utf-8")
                outputStream.use {
                    val input = body.toByteArray()
                    it.write(input, 0, input.size)
                }

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
                    logger.error(TAG, "Unable to leave meeting. Response code: $responseCode")
                    null
                }
            }
        } catch (exception: Exception) {
            logger.error(TAG, "There was an exception while leaving the meeting: $exception")
            null
        }
    }
}

suspend fun joinMeeting(
    ioDispatcher: CoroutineDispatcher,
    meetingUrl: String,
    meetingId: String?,
    attendeeName: String?
): String? {
    return withContext(ioDispatcher) {
        val url = if (meetingUrl.endsWith("/")) meetingUrl else "$meetingUrl/"
        val serverUrl = URL("${url}join")
        try {
            val response = StringBuffer()
            with(serverUrl.openConnection() as HttpURLConnection) {
                requestMethod = "POST"
                doInput = true
                doOutput = true

                val body = gson.toJson(mutableMapOf(
                    "title" to meetingId,
                    "name" to attendeeName
                ))

                setRequestProperty("Content-type", "application/json; charset=utf-8")
                outputStream.use {
                    val input = body.toByteArray()
                    it.write(input, 0, input.size)
                }

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