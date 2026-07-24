package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.HttpRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class HttpInput(val method: String, val url: String, val headers: String, val body: String)

/**
 * Action for `action.http`. Performs an HTTP request via [SystemServices]
 * and exposes the typed [HttpResponseItem] on its `response` data port.
 *
 * Its typed [HttpInput] is built by the action contract: `url`, `headers` and
 * `body` may be wired from upstream data or use their static form literals.
 */
class HttpAction : Action<HttpInput, HttpResponseItem> {

    override val definition = actionNode<HttpInput, HttpResponseItem>(
        typeId = "action.http",
        displayName = "HTTP Request",
        description = "Calls a web API and exposes the typed response",
        category = NodeCategory.NETWORK,
        iconKey = "http",
        dataInputs = listOf(
            dataInPort<String>("url"),
            dataInPort<String>("headers"),
            dataInPort<String>("body"),
        ),
        dataOutputs = listOf(dataOut<HttpResponseItem>("response")),
        configFields = listOf(
            ConfigField(
                key = "method",
                label = "Method",
                type = ConfigFieldType.ENUM(options = listOf("GET", "POST", "PUT", "DELETE")),
                defaultValue = "GET",
            ),
            ConfigField(
                key = "url",
                label = "URL",
                type = ConfigFieldType.STR,
                defaultValue = "https://example.com",
            ),
            ConfigField(
                key = "headers",
                label = "Headers (JSON, optional)",
                type = ConfigFieldType.MULTILINE,
            ),
            ConfigField(
                key = "body",
                label = "Body",
                type = ConfigFieldType.MULTILINE,
            ),
        ),
        decode = { input ->
            HttpInput(
                method = input.configString("method", "GET"),
                url = input.text("url", "https://example.com"),
                headers = input.text("headers"),
                body = input.text("body"),
            )
        },
        encodeData = { response -> mapOf("response" to Item.of(response)) },
    )

    override suspend fun execute(input: HttpInput, context: ExecutionContext): NodeOutput<HttpResponseItem> {
        val headers = parseHeaders(input.headers)
        val response = withContext(Dispatchers.IO) {
            context.systemServices.httpRequest(HttpRequest(input.method, input.url, headers, input.body))
        }
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
        return runCatching {
            Json.decodeFromString<Map<String, String>>(json)
        }.getOrDefault(emptyMap())
    }
}
