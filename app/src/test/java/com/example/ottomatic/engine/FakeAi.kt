package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest

/**
 * An [Ai] that answers whatever it is told to and records what it was asked.
 *
 * Shaped after [FakeMail] and [FakeSmartHome]: the node's whole contract is what
 * it does with an answer and with a failure, and neither is testable against a
 * real model — one costs money, needs a key and a network, and cannot be made to
 * fail on demand in each of the ways that matter.
 *
 * [requests] is what pins the *asking* half — that a standing instruction, a
 * model tier and a reply limit reach the facade as configured rather than being
 * quietly dropped, which is a failure nothing downstream would ever reveal.
 */
class FakeAi(var reply: AiReply = AiReply(text = "answered")) : Ai {

    val requests = mutableListOf<AiRequest>()

    override suspend fun complete(request: AiRequest): AiReply {
        requests += request
        return reply
    }
}
