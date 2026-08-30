package io.github.m1n1m1.easymatic.domain.model.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A moment in time, as a graph primitive.
 *
 * Every timestamp a node exposes is one of these rather than a `Long`, which is
 * what lets the editor colour it differently from a battery percentage, offer a
 * date picker instead of a decimal field when comparing against it, and print
 * `2026-07-27T14:03:11+02:00` in a notification instead of `1753617791000`.
 *
 * It is a [ItemSchema.Primitive] like any other and stays **invariant**: a `Long`
 * is not silently accepted by a DateTime port. A mismatched drop instead routes
 * through the ordinary autocast — see
 * [io.github.m1n1m1.easymatic.domain.model.schema.conversionTarget] and
 * [io.github.m1n1m1.easymatic.domain.model.config.ValueType.DATE_TIME].
 *
 * A *duration* is not one of these. `action.delay`'s duration, a poll interval and
 * `ScheduleFire.elapsedMs` stay plain numbers; this type is only ever an instant.
 */
@Serializable(with = DateTimeSerializer::class)
@JvmInline
value class DateTime(val epochMs: Long) : Comparable<DateTime> {

    override fun compareTo(other: DateTime): Int = epochMs.compareTo(other.epochMs)

    /**
     * ISO-8601 with the device's current UTC offset — the canonical text form.
     *
     * Overriding `toString` is what carries that form everywhere without a special
     * case: [Item.asText] renders any primitive as `value.toString()`, so this one
     * function is what a notification, a `transform.text` template, a wired config
     * value and `action.if` all see. Sub-second digits appear only when they are
     * non-zero, so the text round-trips through [parse] exactly.
     */
    override fun toString(): String = ZonedDateTime
        .ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        /**
         * The serial name the schema builder matches on.
         *
         * `PrimitiveKind` is a closed set in kotlinx-serialization, so a DateTime
         * cannot announce itself through its *kind* — it reports `STRING`. The name
         * is therefore the hook [buildSchemaNotNull] and
         * [io.github.m1n1m1.easymatic.domain.registry.NodeSchema] use to recognise one.
         */
        const val SERIAL_NAME = "io.github.m1n1m1.easymatic.DateTime"

        /** 1970-01-01T00:00:00Z — where a failed conversion lands. */
        val EPOCH = DateTime(0)

        /** The current moment. */
        fun now(): DateTime = DateTime(System.currentTimeMillis())

        /**
         * Reads [text] as a moment, or null when it is not one.
         *
         * Deliberately lenient, because the same function has to accept what a
         * device produces (epoch millis), what a web API produces (ISO-8601, or
         * epoch *seconds*) and what a user types into a comparison. First match
         * wins:
         *
         * | text | read as |
         * |---|---|
         * | `1753617791000` (12+ digits) | epoch milliseconds |
         * | `1753617791` (fewer) | epoch seconds |
         * | `2026-07-27T12:03:11Z`, `…+02:00` | an instant, offset honoured |
         * | `2026-07-27T14:03[:11]` | local, device timezone |
         * | `2026-07-27 14:03[:11]` | local, device timezone |
         * | `2026-07-27` | that day at midnight, device timezone |
         * | `18:00[:30]` | **today** at that time, device timezone |
         *
         * The last rung is what makes "run only after 18:00" expressible: a stored
         * `18:00` resolves against *today* every time it is read, so a comparison
         * against it means the same thing tomorrow.
         */
        @Suppress("ReturnCount") // A parse ladder is a sequence of early returns.
        fun parse(text: String?): DateTime? {
            val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            epochOf(raw)?.let { return it }
            instantOf(raw)?.let { return it }
            localOf(raw)?.let { return it }
            return null
        }

        /** A bare integer: milliseconds when long enough to be one, else seconds. */
        private fun epochOf(raw: String): DateTime? {
            val value = raw.takeIf { it.matches(INTEGER) }?.toLongOrNull() ?: return null
            val digits = raw.length - if (raw.first().isDigit()) 0 else 1
            return DateTime(if (digits >= MILLIS_DIGITS) value else value * MS_PER_SECOND)
        }

        /** A form that carries its own offset, so the device timezone is irrelevant. */
        private fun instantOf(raw: String): DateTime? =
            runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()?.let(::DateTime)

        /** A form without an offset, resolved against the device timezone. */
        private fun localOf(raw: String): DateTime? {
            val zone = ZoneId.systemDefault()
            val local = runCatching { LocalDateTime.parse(raw) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(raw, SPACE_SEPARATED) }.getOrNull()
                ?: runCatching { LocalDate.parse(raw).atStartOfDay() }.getOrNull()
                ?: runCatching { LocalTime.parse(raw).atDate(LocalDate.now(zone)) }.getOrNull()
                ?: return null
            return DateTime(local.atZone(zone).toInstant().toEpochMilli())
        }

        private val INTEGER = Regex("^[+-]?\\d+$")

        /** `2026-07-27 14:03` and `2026-07-27 14:03:11`, the space-separated ISO form. */
        private val SPACE_SEPARATED: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

        /**
         * Digits at which a bare integer reads as milliseconds rather than seconds.
         * Epoch seconds have had 10 digits since 2001 and reach 11 in 2286; epoch
         * millis passed 12 digits in 1973, so the split is unambiguous in practice.
         */
        private const val MILLIS_DIGITS = 12
        private const val MS_PER_SECOND = 1000L
    }
}

/**
 * Encodes a [DateTime] as its canonical ISO-8601 text.
 *
 * Text rather than a number on purpose: [flattenItem] and the struct encoding in
 * [asText] both go through this serializer, so a struct's JSON reads
 * `"timestamp":"2026-07-27T14:03:11+02:00"` and `action.if`'s struct-field lookup
 * (`Item.flat`) says exactly what a broken-out port would render. A numeric
 * encoding would make those two disagree.
 */
object DateTimeSerializer : KSerializer<DateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(DateTime.SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DateTime) = encoder.encodeString(value.toString())

    /** Unreadable text lands on [DateTime.EPOCH]; decoding a config must not throw. */
    override fun deserialize(decoder: Decoder): DateTime =
        DateTime.parse(decoder.decodeString()) ?: DateTime.EPOCH
}
