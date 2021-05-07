package com.example.amazonchimesimple.utils

import java.util.*

private val ALLOWED_CHARACTERS = "0123456789qwertyuiopasdfghjklzxcvbnm"

fun getRandomString(sizeOfRandomString: Int = 8): String {
    val random = Random()
    val sb = StringBuilder(sizeOfRandomString)
    for (i in 0 until sizeOfRandomString)
        sb.append(ALLOWED_CHARACTERS[random.nextInt(ALLOWED_CHARACTERS.length)])
    return sb.toString()
}