package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.DayFilter
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.TimeOfDay as ClockTime
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.TimeOfDay
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.nextTimeOfDay
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.Fork
import io.github.m1n1m1.easymatic.engine.ForkAction
import io.github.m1n1m1.easymatic.engine.ExecOutputs
import io.github.m1n1m1.easymatic.engine.NodeInput
import io.github.m1n1m1.easymatic.engine.effectNode
import kotlinx.serialization.Serializable

/** The `plannedAt` port on `action.wait_until` — the moment it intends to resume. */
val WAIT_PLANNED_OUT = PortName("plannedAt")

/** The `resumedAt` port on `action.wait_until` — the moment it actually did. */
val WAIT_RESUMED_OUT = PortName("resumedAt")

/**
 * Permission to set an alarm accurate to the minute.
 *
 * Declared, unlike on `trigger.schedule`, because this is the node whose *whole
 * value* is the moment: without the grant the platform is free to batch the
 * wake-up and "wait until 07:00" quietly becomes "wait until some time after
 * 07:00". It still works, so this is a warning rather than a block — but a
 * warning somewhere, which is more than the silent degradation it replaces.
 */
internal val EXACT_ALARM_PERMISSION = PermissionRequirement(
    manifestPermission = null,
    type = PrerequisiteType.EXACT_ALARM,
    rationaleKey = "alarm.exact",
)

/** How `action.wait_until` is told which moment to wait for. */
@Serializable
enum class WaitMode {
    @Label("For a length of time")
    DURATION,

    @Label("Until a time of day")
    TIME_OF_DAY,

    @Label("Until a date and time")
    DATE_TIME,
}

/**
 * Config for `action.wait_until`.
 *
 * Three ways of naming one moment, because the three are how people actually
 * describe a wait and none of them converts comfortably into another: "in twenty
 * minutes" is arithmetic on now, "at 07:00 on weekdays" is a calendar search,
 * and "at this instant" is a value that may well have been computed upstream.
 *
 * The day filters are the schedule trigger's, down to the field names, and they
 * resolve through the same [DayFilter] — so "the next 07:00 that is a weekday"
 * cannot mean one thing to a trigger and another to a wait.
 */
@Suppress("LongParameterList") // One property per form field; a config class is a flat declaration.
@Serializable
data class WaitUntilConfig(
    @Label("Wait") val mode: WaitMode = WaitMode.DURATION,

    @VisibleWhen("mode", "DURATION")
    @Label("Duration")
    @Wired
    val duration: Int = 30,

    @VisibleWhen("mode", "DURATION")
    @Label("Unit")
    val unit: DelayUnit = DelayUnit.MINUTES,

    @VisibleWhen("mode", "TIME_OF_DAY")
    @Label("At")
    @TimeOfDay
    val atTime: String = "07:30",

    @VisibleWhen("mode", "TIME_OF_DAY") @Label("Mondays") val monday: Boolean = false,
    @VisibleWhen("mode", "TIME_OF_DAY") @Label("Tuesdays") val tuesday: Boolean = false,
    @VisibleWhen("mode", "TIME_OF_DAY") @Label("Wednesdays") val wednesday: Boolean = false,
    @VisibleWhen("mode", "TIME_OF_DAY") @Label("Thursdays") val thursday: Boolean = false,
    @VisibleWhen("mode", "TIME_OF_DAY") @Label("Fridays") val friday: Boolean = false,
    @VisibleWhen("mode", "TIME_OF_DAY") @Label("Saturdays") val saturday: Boolean = false,
    @VisibleWhen("mode", "TIME_OF_DAY") @Label("Sundays") val sunday: Boolean = false,

    @VisibleWhen("mode", "TIME_OF_DAY")
    @Label("Days of month")
    @Hint("e.g. 1,15, empty = every day")
    val daysOfMonth: String = "",

    @VisibleWhen("mode", "DATE_TIME")
    @Label("Until")
    @Wired
    val until: DateTime = DateTime.EPOCH,
) {
    /** The days a [WaitMode.TIME_OF_DAY] wait is allowed to land on. */
    val dayFilter: DayFilter
        get() = DayFilter.of(
            monday = monday,
            tuesday = tuesday,
            wednesday = wednesday,
            thursday = thursday,
            friday = friday,
            saturday = saturday,
            sunday = sunday,
            daysOfMonth = daysOfMonth,
        )

    /**
     * The moment to resume at, relative to [nowEpochMs], or null when there is
     * none to wait for.
     *
     * Null has three causes and they all mean the same thing to the user — this
     * will never resume — so they are one answer rather than three:
     *  - a date that has already passed, which is what an unconfigured node has
     *    (the default is [DateTime.EPOCH]) and so the ordinary case rather than
     *    an edge one;
     *  - a day filter no day within a year satisfies, such as the 31st of a month
     *    that has thirty days combined with a weekday no such date falls on;
     *  - never for a duration: "wait zero minutes" is a moment that has arrived,
     *    not a moment that will not come, so it resumes at once.
     */
    fun resumeAt(nowEpochMs: Long): Long? = when (mode) {
        WaitMode.DURATION -> nowEpochMs + duration.toLong() * unit.millis
        WaitMode.TIME_OF_DAY -> nextTimeOfDay(minutesOf(atTime), dayFilter, nowEpochMs)
        WaitMode.DATE_TIME -> until.epochMs.takeIf { it > nowEpochMs }
    }

    private fun minutesOf(hhmm: String): Int = ClockTime.parse(hhmm)?.minutesPastMidnight ?: 0
}

/**
 * Action for `action.wait_until` — the graph's way of waiting for a *moment*
 * rather than a duration, without stopping everything else.
 *
 * It is a [ForkAction], so it has two execution outputs and pulses **both**:
 * `Carry on now` immediately, and `When the time comes` when the moment arrives.
 * That is the difference from `action.delay`, which blocks its one `out` until
 * its duration is up — "at 22:00, turn the lights off" is rarely the *only*
 * thing a macro wants to do when it starts.
 *
 * Each branch carries the moment it is about: `plannedAt` says when it means to
 * resume, which is what goes into "I'll do that at 22:00"; `resumedAt` says when
 * it did. Under an inexact alarm — see [EXACT_ALARM_PERMISSION] — those two
 * differ, which is the only reason the second port is worth having.
 *
 * Two limits, stated here because both look like bugs from outside:
 *
 *  - **A pending wait does not survive the process dying.** The alarm resumes a
 *    coroutine this process is still holding; nothing persists a half-finished
 *    run. Alarm-backed means it survives *doze*, not a restart. The durable "at
 *    07:00 tomorrow, do X" is `trigger.schedule`, which is re-armed from disk on
 *    every boot.
 *  - **A `Stop Macro` cannot un-run a branch that has already resumed.** It does
 *    cancel a wait that has not fired yet; see `action.stop`.
 */
class WaitUntilAction : ForkAction<WaitUntilConfig> {

    override val definition = effectNode<WaitUntilConfig>(
        typeId = "action.wait_until",
        displayName = "Wait Until",
        description = "Carries on at once and continues again at a chosen moment",
        category = NodeCategory.TIMING,
        icon = NodeIcon.SCHEDULE,
        execOutputs = ExecOutputs.FORK,
        extraPorts = listOf(
            dateOut(WAIT_PLANNED_OUT, "Planned for"),
            dateOut(WAIT_RESUMED_OUT, "Resumed at"),
        ),
        permissions = listOf(EXACT_ALARM_PERMISSION),
    )

    override suspend fun begin(
        config: WaitUntilConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): Fork {
        val now = System.currentTimeMillis()
        val target = config.resumeAt(now)
        if (target == null) {
            context.log("No moment left to wait for", LogLevel.WARN)
            return Fork(resume = null)
        }
        context.log("Waiting until ${DateTime(target)}", LogLevel.DEBUG)
        return Fork(
            values = mapOf(WAIT_PLANNED_OUT to Item.of(DateTime(target))),
            resume = {
                context.waits.awaitUntil(target) {
                    // The instant we actually woke, not the one we asked for: an
                    // inexact alarm lands late, and a macro that cares can compare
                    // the two.
                    mapOf(WAIT_RESUMED_OUT to Item.of(DateTime(System.currentTimeMillis())))
                }
            },
        )
    }
}

/**
 * A DATA output port carrying a [DateTime].
 *
 * Declared as an extra port rather than through `actionNode`'s typed `output`
 * because this node has *two* of them, one per branch — which is a shape the
 * single `DataOut` cannot express, and which is inherent to a fork: the two
 * branches happen at different times and have different things to say.
 */
private fun dateOut(name: PortName, label: String): Port = Port(
    name = name,
    kind = PortKind.DATA,
    direction = Direction.OUT,
    schema = ItemSchema.Primitive(DateTime::class),
    label = label,
)
