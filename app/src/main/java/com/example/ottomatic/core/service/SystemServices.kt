package com.example.ottomatic.core.service

/**
 * Android-backed system operations exposed to actions without pulling
 * Android types into `engine/`. Implemented by [AndroidSystemServices] in `data/`.
 */
interface SystemServices {

    /** Posts a notification with [title] and [text]. Returns true on success. */
    fun notify(title: String, text: String): Boolean

    /** Toggles Wi-Fi. Returns the new state, or null if it could not be changed. */
    fun setWifi(enabled: Boolean): Boolean?

    /** Performs an HTTP request. */
    fun httpRequest(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

data class HttpResponse(
    val statusCode: Int,
    val body: String,
)
