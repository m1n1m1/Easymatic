package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A scripted phone, on `FakeAi`'s shape and for its reason.
 *
 * The four statuses and a generation that fails are the whole input space of the
 * on-device branch, and not one of them can be produced from a real device on demand —
 * so `RoutingAi`'s fallback rules are testable exactly to the extent that this exists.
 *
 * [plans] records what was actually asked, which is how "the fallback answers as itself"
 * is checked from the other side: a request that fell back must never have reached here.
 */
internal class FakeOnDeviceAi(
    var status: OnDeviceStatus = OnDeviceStatus.AVAILABLE,
    var reply: AiReply = AiReply(text = "on-device"),
) : OnDeviceAi {

    val plans = mutableListOf<MlKitPlan>()

    var forgotten = 0
        private set

    override suspend fun status(): OnDeviceStatus = status

    override suspend fun complete(plan: MlKitPlan): AiReply {
        plans += plan
        return reply
    }

    override fun download(): Flow<OnDeviceDownload> = flowOf(OnDeviceDownload.Done)

    override suspend fun baseModelName(): String = "nano-test"

    override fun forget() {
        forgotten++
    }
}
