package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Action for `action.http`. Performs an HTTP request via [SystemServices]
 * and exposes the typed [HttpResponseItem] on its `response` data port.
 *
 * The url/headers/body config fields are EXPR-typed: `{{field}}` placeholders
 * interpolate against the runtime data context (all data items produced
 * upstream in the current execution chain).
 */
class HttpAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val method = input.config.str("method", default = "GET")
        val url = input.config.expr("url", default = "https://example.com")
        val headersJson = input.config.expr("headers")
        val body = input.config.expr("body")
        val headers = parseHeaders(headersJson)
        val response = withContext(Dispatchers.IO) {
            context.systemServices.httpRequest(HttpRequest(method, url, headers, body))
        }
        val item = Item.of(
            HttpResponseItem(
                statusCode = response.statusCode,
                body = response.body,
            ),
        )
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("response" to item))
    }

    private fun parseHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(json)
        }.getOrDefault(emptyMap())
    }

    companion object {
        const val TYPE_ID = "action.http"
    }
}
