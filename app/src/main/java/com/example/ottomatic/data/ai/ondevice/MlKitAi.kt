package com.example.ottomatic.data.ai.ondevice

import android.util.Base64
import android.util.Log
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.data.ai.MlKitPlan
import com.example.ottomatic.data.ai.OnDeviceAi
import com.example.ottomatic.data.ai.OnDeviceDownload
import com.example.ottomatic.data.ai.OnDeviceStatus
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Gemini Nano through ML Kit's GenAI Prompt API.
 *
 * **One of the two files in the app that import `com.google.mlkit`** — `MlKitTranslation` is
 * the other, and follows this file's rule rather than weakening it — which is the whole point
 * of [OnDeviceAi] existing as an interface: everything worth testing about the on-device
 * path — what a tier means, which requests cannot run here, what the request carries — is
 * decided by `mlKitPlan` and `onDeviceProblem` with no device present, and what is left
 * here is the call itself. That is `AiTransport`'s division exactly, one layer over.
 *
 * **It needs no `Context`.** `Generation.getClient` takes only a [GenerationConfig], and
 * ML Kit initialises itself from its own manifest provider — so this is constructed with
 * nothing, and `ServiceLocator` hands it to `RoutingAi` rather than threading an
 * application context through the AI library.
 *
 * **Nothing here throws**, including out of the SDK. A beta artifact talking to a system
 * service that may not exist is exactly where an unexpected exception comes from, and the
 * caller's answer to every one of them is the same: report it, and let `RoutingAi` ask the
 * profile's fallback. [CancellationException] is rethrown, on this project's standing rule
 * — stopping a run must stop it rather than log a bogus failure and walk on.
 */
internal class MlKitAi : OnDeviceAi {

    /**
     * One client per model preference, held for the life of the process.
     *
     * Held rather than built per call because each is a binding to AICore, and rebuilding
     * one for every prompt would pay the bind on a path whose whole selling point is that
     * it is quick and local. Never closed for the same reason: the two consumers are a
     * foreground service that outlives any single run and a settings screen, and there is
     * no moment at which "no more prompts are coming" is known. Neither holds model
     * weights — those live in AICore's own process.
     */
    private val clients = mutableMapOf<Boolean, GenerativeModel>()

    /**
     * The last settled answer from [status], or null when it must be asked again.
     *
     * **Only the two permanent answers are cached.** Whether this phone has AICore cannot
     * change while the process lives, and neither can a downloaded model become
     * undownloaded — but "downloadable" and "downloading" are exactly the states somebody
     * is in the middle of changing, and a cached one of those would leave the AI screen
     * showing a progress bar that has already finished.
     */
    @Volatile
    private var settled: OnDeviceStatus? = null

    override suspend fun status(): OnDeviceStatus {
        settled?.let { return it }
        val answer = ask()
        if (answer == OnDeviceStatus.AVAILABLE || answer == OnDeviceStatus.UNSUPPORTED) {
            settled = answer
        }
        return answer
    }

    override fun forget() {
        settled = null
    }

    @Suppress("TooGenericExceptionCaught") // A beta SDK over a system service that may be
    // absent: an unavailable phone is precisely where an unforeseen throwable comes from,
    // and reporting it as "unsupported" is the same answer the SDK would have given.
    private suspend fun ask(): OnDeviceStatus = try {
        when (client(preferFull = false).checkStatus()) {
            FeatureStatus.AVAILABLE -> OnDeviceStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> OnDeviceStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> OnDeviceStatus.DOWNLOADING
            else -> OnDeviceStatus.UNSUPPORTED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Throwable) {
        Log.w(TAG, "On-device AI status could not be read", failed)
        OnDeviceStatus.UNSUPPORTED
    }

    @Suppress(
        "TooGenericExceptionCaught", // See `ask`; a generation failure must reach the
        // fallback rather than the default handler.
        "ReturnCount", // A picture that will not decode and a generation that failed are
        // different sentences, and both are answers rather than throws.
    )
    override suspend fun complete(plan: MlKitPlan): AiReply {
        val request = try {
            buildRequest(plan)
        } catch (rejected: IllegalArgumentException) {
            Log.w(TAG, "On-device AI refused the picture", rejected)
            return AiReply(error = UNREADABLE_PICTURE)
        }
        return try {
            readReply(client(plan.preferFullModel).generateContent(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            Log.w(TAG, "On-device AI could not answer", failed)
            AiReply(error = failed.message?.takeIf { it.isNotBlank() } ?: GENERIC_FAILURE)
        }
    }

    /**
     * Turns a [MlKitPlan] into the SDK's request.
     *
     * The Base64 is decoded **here** rather than in `mlKitPlan`, and for the reason
     * `RoutingAi.transcribe` decodes its own: `android.util.Base64` in one of the pure
     * files is what those files exist not to have. `ImagePart` takes the encoded bytes
     * directly and does its own decoding, so no `Bitmap` is ever held — which matters in a
     * foreground service carrying every armed macro on the phone.
     */
    private fun buildRequest(plan: MlKitPlan): GenerateContentRequest {
        val text = TextPart(plan.prompt)
        val picture = plan.image?.let { ImagePart(Base64.decode(it.base64, Base64.NO_WRAP)) }
        val builder =
            if (picture == null) GenerateContentRequest.Builder(text)
            else GenerateContentRequest.Builder(picture, text)
        builder.maxOutputTokens = plan.maxOutputTokens
        builder.temperature = plan.temperature
        builder.topK = plan.topK
        // Absent rather than blank when there is no standing instruction, matching every
        // protocol here: an empty system prompt is a thing said, not a thing omitted.
        if (plan.systemInstruction.isNotBlank()) {
            builder.promptPrefix = PromptPrefix(plan.systemInstruction)
        }
        return builder.build()
    }

    /**
     * What the model answered.
     *
     * Blank text is a failure, as it is on every cloud protocol here and for the same
     * reason — a macro sending an empty answer on to somebody is worse than one that
     * reports. A reply stopped at the token bound is *not* that: it is real output, so it
     * reaches the node with [AiReply.truncated] set and the console says it was cut off.
     */
    private fun readReply(response: GenerateContentResponse): AiReply {
        val candidate = response.candidates.firstOrNull()
        val text = candidate?.text.orEmpty()
        // No candidate at all and a candidate with nothing in it are the same answer here,
        // which is why they fold into one branch: both mean the model said nothing, and
        // there is nothing different a node could do about either.
        return if (text.isBlank()) {
            AiReply(error = NO_ANSWER)
        } else {
            AiReply(text = text, truncated = candidate?.finishReason == Candidate.FinishReason.MAX_TOKENS)
        }
    }

    override fun download(): Flow<OnDeviceDownload> = flow {
        var total = 0L
        try {
            client(preferFull = false).download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        total = status.bytesToDownload
                        emit(OnDeviceDownload.Started(total))
                    }

                    is DownloadStatus.DownloadProgress ->
                        emit(OnDeviceDownload.Progress(status.totalBytesDownloaded, total))

                    DownloadStatus.DownloadCompleted -> {
                        // The one answer this class caches that a download invalidates.
                        forget()
                        emit(OnDeviceDownload.Done)
                    }

                    is DownloadStatus.DownloadFailed ->
                        emit(OnDeviceDownload.Failed(status.e.message?.ifBlank { null } ?: DOWNLOAD_FAILED))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
            Log.w(TAG, "On-device model download failed", failed)
            emit(OnDeviceDownload.Failed(failed.message?.ifBlank { null } ?: DOWNLOAD_FAILED))
        }
    }

    @Suppress("TooGenericExceptionCaught") // See `ask`. A name that cannot be read is blank,
    // never an exception reaching a settings screen.
    override suspend fun baseModelName(): String = try {
        client(preferFull = false).getBaseModelName()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Throwable) {
        Log.w(TAG, "On-device model name could not be read", failed)
        ""
    }

    private fun client(preferFull: Boolean): GenerativeModel = synchronized(clients) {
        clients.getOrPut(preferFull) { Generation.getClient(configFor(preferFull)) }
    }

    /**
     * The client configuration for a tier.
     *
     * [ModelReleaseStage.STABLE] is left at its default deliberately: a preview model here
     * is the same bet `GeminiProtocol` refuses when it names no `-preview` id, and it has
     * already gone wrong twice on the cloud side.
     */
    private fun configFor(preferFull: Boolean): GenerationConfig {
        val model = ModelConfig.builder()
        model.preference = if (preferFull) ModelPreference.FULL else ModelPreference.FAST
        val config = GenerationConfig.Builder()
        config.modelConfig = model.build()
        return config.build()
    }

    private companion object {
        const val TAG = "MlKitAi"

        const val NO_ANSWER = "The on-device model returned an empty answer"

        const val GENERIC_FAILURE = "The on-device model could not answer"

        const val UNREADABLE_PICTURE = "That picture could not be read"

        const val DOWNLOAD_FAILED = "The on-device model could not be downloaded"
    }
}
