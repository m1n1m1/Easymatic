package com.example.ottomatic.data.mail

import com.example.ottomatic.core.service.MailLimits
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.MimeUtility

/**
 * Turns a MIME message into the plain text a macro can actually use.
 *
 * This is the most quietly-wrong-able code in the whole feature, which is why it
 * is a pure function over [Part] with no connection behind it: a subject that
 * renders as `=?utf-8?Q?Re=3A_Rechnung?=` in a notification is not a crash, it is
 * a macro that looks like it works. Everything here has a JVM test.
 *
 * The walk prefers `text/plain`, falls back to `text/html` with its tags stripped,
 * and skips anything the sender marked as an attachment — a PDF invoice is not the
 * message and its bytes are not text.
 *
 * The cap is applied **during** the walk rather than to the finished string, which
 * is the difference between a cap and a truncation: a 4 MB HTML newsletter is
 * never materialised, only read until there is enough.
 */
object MailBodyText {

    /** [text] with the cap applied, and whether the cap bit. */
    data class Extracted(val text: String, val truncated: Boolean)

    /**
     * The readable body of [part].
     *
     * Failures are swallowed to an empty body on purpose: a message whose content
     * cannot be decoded is still a message that *arrived*, and a trigger that threw
     * here would take down the run rather than fire it with a blank body and a
     * subject that is perfectly readable.
     */
    fun extract(part: Part): Extracted = runCatching {
        val builder = StringBuilder()
        val complete = append(part, builder, preferHtml = false) || append(part, builder, preferHtml = true)
        if (!complete && builder.isEmpty()) return@runCatching Extracted("", truncated = false)
        cap(builder.toString())
    }.getOrElse { Extracted("", truncated = false) }

    /** The subject with any RFC 2047 encoded-words decoded, or the raw text if that fails. */
    fun decodeHeader(raw: String?): String {
        val text = raw.orEmpty()
        return runCatching { MimeUtility.decodeText(text) }.getOrDefault(text)
    }

    /**
     * Walks [part] for the first body of the wanted kind, appending into [into].
     *
     * Returns whether anything was found, so the caller can make a second pass for
     * HTML only when there was no plain-text part anywhere in the tree — which is
     * why this cannot simply take the first leaf it meets.
     */
    private fun append(part: Part, into: StringBuilder, preferHtml: Boolean): Boolean {
        if (into.length >= MailLimits.BODY_CHARS) return true
        val content = if (isAttachment(part)) null else runCatching { part.content }.getOrNull()
        return when {
            content is Multipart -> {
                var found = false
                for (index in 0 until content.count) {
                    val child = runCatching { content.getBodyPart(index) }.getOrNull() ?: continue
                    if (append(child, into, preferHtml)) found = true
                }
                found
            }
            content is String && part.isMimeType(if (preferHtml) TEXT_HTML else TEXT_PLAIN) -> {
                into.append(if (preferHtml) stripHtml(content) else content)
                true
            }
            else -> false
        }
    }

    /**
     * Whether the sender marked this part as an attachment.
     *
     * A null disposition is deliberately *not* treated as one: plenty of senders
     * omit it on the body part itself, and reading that as "attached" would empty
     * the body of a perfectly ordinary message.
     */
    private fun isAttachment(part: Part): Boolean =
        runCatching { Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) }.getOrDefault(false)

    /** Whether [part] carries at least one part the sender marked as attached. */
    fun hasAttachments(part: Part): Boolean = runCatching {
        val content = part.content
        content is Multipart && (0 until content.count).any { index ->
            isAttachment(content.getBodyPart(index))
        }
    }.getOrDefault(false)

    /**
     * The readable text inside HTML, for the messages that carry no plain part.
     *
     * Deliberately crude — drop scripts and styles wholesale, drop every tag, undo
     * the five entities that matter, collapse whitespace. A real parser would be a
     * dependency and a much larger surface for the sake of output that is going
     * into a notification or a text comparison either way.
     */
    fun stripHtml(html: String): String = html
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(BLOCK_BREAK, "\n")
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(BLANK_RUN, "\n")
        .trim()

    private fun cap(text: String): Extracted =
        if (text.length <= MailLimits.BODY_CHARS) {
            Extracted(text, truncated = false)
        } else {
            Extracted(text.take(MailLimits.BODY_CHARS), truncated = true)
        }

    private const val TEXT_PLAIN = "text/plain"
    private const val TEXT_HTML = "text/html"

    private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)\\b.*?</\\1>")
    private val BLOCK_BREAK = Regex("(?i)<(br|/p|/div|/tr|/li)\\s*/?>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val BLANK_RUN = Regex("\\n\\s*\\n+")
}
