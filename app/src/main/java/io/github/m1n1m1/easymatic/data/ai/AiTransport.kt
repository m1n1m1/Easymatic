package io.github.m1n1m1.easymatic.data.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * The one HTTP call every AI provider makes, as `(status, body)`.
 *
 * **Its own transport rather than [io.github.m1n1m1.easymatic.core.service.SystemServices.httpRequest]**,
 * for exactly one reason, and it is not architecture: that facade reads with a
 * 15-second timeout, which is right for an API call and wrong for a generation — a
 * thorough model answering a long prompt regularly takes longer, and would arrive as
 * a `-1` indistinguishable from having no signal. Raising the shared one would slow
 * down every `action.http` in every macro to suit this one node. `data/hue/` owns
 * its transport for its own reason (a pinned certificate); this is the second,
 * smaller one.
 *
 * **Written once and shared by every protocol**, because this half genuinely does
 * not vary: the URL, the header names and the body differ per provider and are
 * [AiProtocol]'s job, but "POST some JSON, wait a long time, read the error stream
 * in preference to the input stream" is the same sentence for all of them.
 *
 * Nothing here throws. Every failure — no route to host, a DNS failure, a read that
 * timed out, a cleartext connection the platform refused — means the same thing to
 * the node, and arrives as [NO_RESPONSE] for the protocol to word.
 *
 * **Cancelling the caller closes the socket**, and that is why these are suspending
 * functions that switch dispatcher themselves rather than blocking ones every call site
 * wraps. Ninety seconds is the right bound for a generation nobody is watching and the
 * wrong one for somebody who has just pressed Stop: a blocked socket read answers no
 * interrupt, so a cancelled coroutine would go on holding the request until the server
 * got round to replying. `disconnect()` from the job's completion handler is what
 * actually ends it. The aborted read arrives here as an ordinary [NO_RESPONSE] and is
 * never seen — `withContext` throws the cancellation before the answer can be returned.
 */
internal object AiTransport {

    /** Sends [body] to [url] and answers what came back. */
    suspend fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> =
        call(url, headers) { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

    /** Reads [url]. Used only by [AiModelCatalog], never on an execution path. */
    suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String> =
        call(url, headers) { it.requestMethod = "GET" }

    /**
     * Uploads [upload] with [fields] beside it, as `multipart/form-data`.
     *
     * **A third caller of [call] rather than a second transport**, which is this
     * object's whole argument: the connect timeout, the ninety-second read, the
     * cancel-on-stop handler and the error-stream-first read do not vary by body
     * format, and a second copy of them is a second place for one of the four to be
     * forgotten.
     *
     * **`Content-Type` is replaced rather than merged**, and that is not tidiness: every
     * protocol's `headers()` puts `application/json` there, and a multipart body
     * carrying that header is a 400 on every server that has ever existed. The boundary
     * has to travel with the header, so this is the one call that cannot use the
     * protocol's headers unchanged.
     *
     * The parts go **straight to the socket**. Assembling them into a `String` first
     * would hold the whole recording twice more — once as text and once as the bytes of
     * that text — inside the process holding every armed macro, which is the same
     * objection `readBoundedBase64` answers one layer down.
     */
    suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        upload: AiUpload,
    ): Pair<Int, String> {
        val boundary = "----easymatic" + System.nanoTime().toString(RADIX_HEX)
        val sending = headers + (CONTENT_TYPE to "multipart/form-data; boundary=$boundary")
        return call(url, sending) { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { out ->
                fields.forEach { (name, value) ->
                    out.ascii("--$boundary\r\n")
                    out.ascii("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.ascii("$value\r\n")
                }
                out.ascii("--$boundary\r\n")
                out.ascii(
                    "Content-Disposition: form-data; name=\"${upload.fieldName}\"; " +
                        "filename=\"${upload.fileName}\"\r\n",
                )
                out.ascii("Content-Type: ${upload.mediaType}\r\n\r\n")
                out.write(upload.bytes)
                out.ascii("\r\n--$boundary--\r\n")
            }
        }
    }

    private fun java.io.OutputStream.ascii(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * The error stream is read in preference to the input stream, as
     * `AndroidSystemServices.httpRequest` does: a 400 from any of these APIs carries
     * the only sentence that says *why* the key was refused, and reading the input
     * stream on a failed connection throws it away.
     */
    private suspend fun call(
        url: String,
        headers: Map<String, String>,
        prepare: (HttpURLConnection) -> Unit,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        var closeOnCancel: DisposableHandle? = null
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            // Registered before the first blocking call, and on a job that is *already*
            // cancelled it runs right here — so there is no window in which a stopped
            // turn is left waiting on a socket nobody will read.
            closeOnCancel = coroutineContext.job.invokeOnCompletion { connection.disconnect() }
            prepare(connection)
            val status = connection.responseCode
            val text = (connection.errorStream ?: connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            status to text
        }.getOrElse { e ->
            // Deliberately not rethrown: the Ai facade's contract is that nothing throws.
            NO_RESPONSE to (if (e is IOException) e.message.orEmpty() else "")
        }.also { closeOnCancel?.dispose() }
    }

    /** Reaching the host is either quick or not happening; this is not the slow part. */
    private const val CONNECT_TIMEOUT_MS = 15_000

    private const val CONTENT_TYPE = "Content-Type"
    private const val RADIX_HEX = 16

    /**
     * Ninety seconds. A generation is the slow part and a bounded wait is the point —
     * but the bound has to be past the honest worst case for a thorough model on a
     * long prompt, or the node reports a network failure for a request that was
     * working.
     */
    private const val READ_TIMEOUT_MS = 90_000
}
