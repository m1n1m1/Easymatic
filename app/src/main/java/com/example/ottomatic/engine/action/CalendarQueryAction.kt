package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.CalendarLimits
import com.example.ottomatic.core.service.EventQuery
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CalendarEvent
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** Which stretch of time `action.calendar_query` looks at. */
@Serializable
enum class CalendarWindow {
    @Label("The rest of today")
    TODAY,

    @Label("The next few days")
    NEXT_DAYS,

    @Label("Between two times")
    BETWEEN,
}

/**
 * Config for `action.calendar_query`.
 *
 * The window is a **mode plus its own fields** rather than two date pickers, because the
 * two questions people actually ask are "what is on today?" and "what is coming up this
 * week?", and answering either with two absolute timestamps means editing the macro every
 * day. [CalendarWindow.BETWEEN] is there for the third case and is the only one that
 * needs a calendar picker in the form.
 */
@Serializable
data class CalendarQueryConfig(
    @Label("Calendar")
    @Picker(PickerKind.CALENDAR_FILTER, optional = true)
    val calendar: String = "",

    @Label("Look at") val window: CalendarWindow = CalendarWindow.NEXT_DAYS,

    @VisibleWhen("window", "NEXT_DAYS")
    @Label("Days ahead")
    @Wired
    val days: Int = DEFAULT_DAYS,

    @VisibleWhen("window", "BETWEEN")
    @Label("From")
    @Wired
    val from: DateTime = DateTime.EPOCH,

    @VisibleWhen("window", "BETWEEN")
    @Label("Until")
    @Wired
    val until: DateTime = DateTime.EPOCH,

    @Label("Title contains") @Wired val titleContains: String = "",
    @Label("Include cancelled and declined ones") val includeCancelled: Boolean = false,
    @Label("How many at most") val limit: Int = DEFAULT_LIMIT,
)

private const val DEFAULT_DAYS = 7
private const val DEFAULT_LIMIT = 20
private const val MS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * Action for `action.calendar_query`. Reads appointments onto a list.
 *
 * `action.fetch_mail`'s node, for calendars, and it is the same shape for the same
 * reason: the output is a **list port**, which `action.for_each` accepts as it is, so what
 * this is built for is query → for-each → do something per appointment. The `ref` each
 * appointment carries is what `action.calendar_update` then acts on.
 *
 * **Occurrences, not series.** A weekly stand-up inside the window comes back once per
 * week, each with its own reference. That is what makes "cancel today's stand-up" a thing
 * a macro can express at all.
 *
 * **Never throws.** No calendar access, a calendar that has been removed, an unreadable
 * window — all of them are an empty list plus one line in the console, and `out` still
 * pulses. `action.if` on `transform.list_count` is how a macro branches on "nothing on
 * today", which reads the same whether the day is empty or the read failed; the console
 * says which.
 */
class CalendarQueryAction : Action<CalendarQueryConfig, List<CalendarEvent>> {

    override val definition = actionNode<CalendarQueryConfig, List<CalendarEvent>>(
        typeId = "action.calendar_query",
        displayName = "Find Calendar Events",
        description = "Reads appointments out of a calendar onto a list you can loop over",
        category = NodeCategory.CALENDAR,
        icon = NodeIcon.CALENDAR,
        output = dataOut<List<CalendarEvent>>("events", label = "Events"),
        permissions = listOf(READ_CALENDAR_PERMISSION),
    )

    override suspend fun execute(
        input: CalendarQueryConfig,
        context: ExecutionContext,
    ): NodeOutput<List<CalendarEvent>> {
        val now = System.currentTimeMillis()
        val (from, until) = input.windowAt(now)
        if (until <= from) {
            context.log("That time window ends before it starts, so there is nothing to look at", LogLevel.ERROR)
            return NodeOutput(emptyList())
        }
        // Announced rather than silently applied, the way MAX_ITERATIONS is: a cap nobody
        // is told about reads as "that is all there was in the diary".
        val limit = input.limit.coerceIn(1, CalendarLimits.MAX_EVENTS)
        if (limit != input.limit) {
            context.log("Asked for ${input.limit} appointments; reading $limit, which is the most allowed")
        }

        val listing = context.calendars.events(
            EventQuery(
                calendarSpec = input.calendar,
                fromEpochMs = from,
                untilEpochMs = until,
                titleContains = input.titleContains,
                liveOnly = !input.includeCancelled,
                limit = limit,
            ),
        )
        if (listing.error.isNotEmpty()) {
            context.log("Could not read the calendar: ${listing.error}", LogLevel.ERROR)
        }
        if (listing.truncated) {
            context.log("There are more appointments in that window than the $limit asked for", LogLevel.WARN)
        }
        return NodeOutput(listing.events.map { it.toItem() })
    }

    private companion object {
        val READ_CALENDAR_PERMISSION = PermissionRequirement(
            manifestPermission = Permissions.READ_CALENDAR.manifest,
            type = PrerequisiteType.RUNTIME,
            rationaleKey = "calendar.read",
        )
    }
}

/**
 * The window this config describes at [now], as `[from, until)`.
 *
 * [CalendarWindow.TODAY] runs to the **end of the local day** rather than a rolling
 * twenty-four hours, which is what "today" means to a person and what makes an evening
 * run of the macro answer "nothing left today" rather than listing tomorrow morning.
 * It deliberately starts at [now] and not at midnight: an appointment that has already
 * finished is not something a macro is about to act on.
 */
internal fun CalendarQueryConfig.windowAt(
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<Long, Long> = when (window) {
    CalendarWindow.TODAY -> now to Instant.ofEpochMilli(now)
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    CalendarWindow.NEXT_DAYS -> now to now + days.coerceAtLeast(1) * MS_PER_DAY
    CalendarWindow.BETWEEN -> from.epochMs to until.epochMs
}
