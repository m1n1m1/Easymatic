package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiModelProfile

/**
 * The account-plus-profile pair every protocol function takes, built for a test.
 *
 * One helper shared by all four protocol test files rather than a private copy in
 * each: the four vary only in which provider they exercise, and a per-file fixture is
 * how one of them ends up quietly testing a different profile shape from the rest.
 */
internal fun target(
    connection: AiConnection,
    effort: AiModel = AiModel.FAST,
    modelId: String = "",
    systemPrompt: String = "",
    tools: String = "",
) = AiTarget(
    connection = connection,
    profile = AiModelProfile(
        id = "${connection.id}#test",
        name = "Test",
        modelId = modelId,
        effort = effort,
        systemPrompt = systemPrompt,
        tools = tools,
    ),
)

/**
 * A request naming a profile that nothing here reads.
 *
 * Which profile a prompt is billed to is resolved by [RoutingAi] before a protocol is
 * reached, so the id is carried only because [AiRequest] refuses to be built without
 * one — which is itself the point: there is no implicit model to fall back on.
 */
internal const val TEST_MODEL_REF = "connection-id#test"
