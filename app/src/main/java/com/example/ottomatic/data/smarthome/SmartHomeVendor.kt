package com.example.ottomatic.data.smarthome

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightCommandResult
import com.example.ottomatic.core.service.LightRead
import com.example.ottomatic.core.service.LightReading
import com.example.ottomatic.core.service.SceneRecall
import com.example.ottomatic.core.service.SceneResult
import com.example.ottomatic.domain.model.SmartHomeHub

/**
 * One vendor's half of [com.example.ottomatic.core.service.SmartHome].
 *
 * **The seam is the whole facade rather than something underneath it**, and that is
 * the decision worth defending, because the obvious alternative — factoring out
 * `get`/`put`/`pathFor` primitives and sharing the logic above them — is wrong here
 * for reasons that are specific rather than stylistic. The two vendors do not merely
 * *format* the same requests differently, they need **different numbers of requests**:
 *
 * - a toggle is read-then-write on Hue, which has no such request, and **one call** on
 *   Home Assistant, which has `light.toggle`;
 * - "only lights already on" is one write **per lit light** on Hue, because a
 *   `grouped_light` write cannot say "except that one", and **one call with a list** on
 *   Home Assistant;
 * - a scene's group costs Hue up to two extra GETs and Home Assistant none.
 *
 * Shared logic above a shared transport would force Home Assistant into Hue's request
 * shape — twelve paced calls where one would do. This is the mirror image of
 * `data/ai/`, where the seam sits *below* the facade precisely because there the
 * transport genuinely does not vary and only the envelope does. Same question asked
 * twice, opposite answers, and the answer follows from what actually differs.
 *
 * **Nothing here throws**, which is the facade's promise pushed down one level: every
 * member answers a result carrying an `error`, so [RoutingSmartHome] never has to know
 * what a vendor's failures look like. Each vendor words its own, because "the bridge is
 * unplugged" and "the token was revoked" are not the same sentence and only the vendor
 * knows which it is looking at.
 */
internal interface SmartHomeVendor {

    /**
     * How long [RoutingSmartHome] waits after a command to one hub before sending the
     * next.
     *
     * On the vendor rather than in [com.example.ottomatic.core.service.SmartHomeLimits]
     * because it is a fact about *a bridge*, not about smart homes: Hue accepts roughly
     * ten light commands a second and then drops them silently, which is why the
     * constant exists at all. Home Assistant has no such limit, and paying Hue's tax
     * there would add a second and a half to a fifteen-light `action.for_each` for a
     * ceiling that is not there.
     */
    val commandSpacingMs: Long

    /**
     * Why this hub cannot be reached at all, or null when it can.
     *
     * Asked before anything is sent, so the answer is a sentence about *setup* rather
     * than a network failure — a credential this device can no longer open reads very
     * differently from a bridge that is switched off, and only one of them is fixed by
     * standing next to the hardware.
     */
    fun unreachable(hub: SmartHomeHub): String?

    suspend fun apply(hub: SmartHomeHub, command: LightCommand): LightCommandResult

    suspend fun recall(hub: SmartHomeHub, request: SceneRecall): SceneResult

    suspend fun read(hub: SmartHomeHub, request: LightRead): LightReading
}
