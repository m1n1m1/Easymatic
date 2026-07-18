package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.interpolate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Action for `action.http`. Performs an HTTP request via [SystemServices]
 * and exposes the response status/body in the downstream payload.
 */
class HttpAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val method = input.node.config["method"] ?: "GET"
        val url = interpolate(input.node.config["url"].orEmpty(), input.payload)
        val headersJson = interpolate(input.node.config["headers"].orEmpty(), input.payload)
        val body = interpolate(input.node.config["body"].orEmpty(), input.payload)
        val headers = parseHeaders(headersJson)
        val response = withContext(Dispatchers.IO) {
            context.systemServices.httpRequest(HttpRequest(method, url, headers, body))
        }
        val extra = mapOf(
            "statusCode" to response.statusCode.toString(),
            "responseBody" to response.body,
        )
        return ActionResult(mapOf(0 to input.payload + extra))
    }

    private fun parseHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(json)
        }.getOrDefault(emptyMap())
    }

    companion object {
        const val TYPE_ID = "action.http"
    }
}
