package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.HttpMethod
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Config for `action.http`. [url], [headers] and [body] are `@Wired`: each may
 * be fed from an upstream data edge, falling back to the value typed in the
 * form.
 */
@Serializable
data class HttpConfig(
    @Label("Method") val method: HttpMethod = HttpMethod.GET,
    @Label("URL") @Wired val url: String = "https://example.com",
    @Label("Headers (JSON, optional)") @Multiline @Wired val headers: String = "",
    @Label("Body") @Multiline @Wired val body: String = "",
)

/**
 * Action for `action.http`. Performs an HTTP request via
 * [com.example.ottomatic.core.service.SystemServices] and exposes the typed
 * [HttpResponseItem] on its `response` data port.
 */
class HttpAction : Action<HttpConfig, HttpResponseItem> {

    override val definition = actionNode<HttpConfig, HttpResponseItem>(
        typeId = "action.http",
        displayName = "HTTP Request",
        description = "Calls a web API and exposes the typed response",
        category = NodeCategory.NETWORK,
        icon = NodeIcon.HTTP,
        output = dataOut<HttpResponseItem>("response"),
    )

    override suspend fun execute(input: HttpConfig, context: ExecutionContext): NodeOutput<HttpResponseItem> {
        val request = HttpRequest(input.method, input.url, parseHeaders(input.headers), input.body)
        val response = withContext(Dispatchers.IO) { context.systemServices.httpRequest(request) }
        return NodeOutput(
            HttpResponseItem(
                statusCode = response.statusCode,
                body = response.body,
                headers = response.headers,
            ),
        )
    }

    private fun parseHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, String>>(json) }.getOrDefault(emptyMap())
    }
}
