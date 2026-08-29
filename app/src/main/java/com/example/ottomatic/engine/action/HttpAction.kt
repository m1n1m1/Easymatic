package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.HttpMethod
import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WebUrl
import com.example.ottomatic.domain.model.config.Hint
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
    @Label("Headers")
    @Hint("JSON, optional")
    @Multiline @Wired val headers: String = "",
    @Label("Body") @Multiline @Wired val body: String = "",
)

/**
 * Action for `action.http`. Performs an HTTP request via
 * [com.example.ottomatic.core.service.SystemServices] and exposes the typed
 * [HttpResponseItem] on its `response` data port.
 *
 * The **scheme is optional** — `api.example.com/v1` is fetched over https,
 * through the same [WebUrl] reading `action.open_url` uses. Text that is not a
 * web URL never reaches the network: it comes back as [INVALID_URL] on the
 * `response` port, which is the status the platform already reports for a
 * request that could not be made, so a downstream `action.if` sees no new shape.
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
        val url = WebUrl.webOnly(input.url)
        if (url == null) {
            val typed = input.url.trim()
            val problem = if (typed.isEmpty()) "No URL set" else "Not a web URL: \"$typed\""
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(HttpResponseItem(statusCode = INVALID_URL, body = problem))
        }
        val request = HttpRequest(input.method, url, parseHeaders(input.headers), input.body)
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

    private companion object {
        /** What the platform already reports for a request that never happened. */
        const val INVALID_URL = -1
    }
}
