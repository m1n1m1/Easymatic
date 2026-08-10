package com.example.ottomatic.core.service

import kotlinx.serialization.Serializable

/**
 * The lights, as the engine sees them.
 *
 * **Suspending**, owning its dispatcher in the `data/` half, for [Mail]'s reason:
 * three actions reach this and none of them is written by somebody thinking about
 * threads.
 *
 * **Nothing here throws.** Every member answers a result carrying an error string,
 * mirroring [Mail] and [SystemServices.httpRequest] answering `-1` for a request
 * that never happened: a downstream `action.if` sees one shape whether the light
 * changed, the bridge was unplugged, or its certificate stopped matching.
 *
 * **An action's facade and never a value node's**, and provably so — every member
 * is a network round trip, which is both of the things the pull side may not be.
 * "Is the hall light on?" is `action.light_state` feeding an `action.if`, on the
 * exec wire where the latency is visible. That is also why there is no
 * `value.light_state` despite CLAUDE.md's "every trigger over a readable state gets
 * a value node too": the escape clause — *reading it is expensive or failable,
 * which is an action's job instead* — is exactly this case, and there is no trigger
 * here to pair with anyway.
 *
 * **Generic rather than `Hue`**, and the test of whether that abstraction is real
 * is that nothing Hue-shaped crosses this boundary: brightness is a percentage,
 * colour is an sRGB int and warmth is kelvin, where the bridge speaks CIE xy and
 * mireds. Mireds, `grouped_light` services and a pinned certificate all stop at
 * `data/hue/`. A second vendor is a new
 * [com.example.ottomatic.domain.model.SmartHomeKind] and a branch in the `data/`
 * implementation, with nothing edited here, in `engine/`, or in the nodes.
 */
interface SmartHome {

    /** Turns a light or group on, off, or to a brightness, colour or warmth. */
    suspend fun apply(command: LightCommand): LightCommandResult

    /** Recalls one scene. */
    suspend fun recall(request: SceneRecall): SceneResult

    /** Reads one light's or group's current state. */
    suspend fun read(request: LightRead): LightReading
}

/**
 * What kind of thing a [com.example.ottomatic.domain.model.SmartHomeRef] names.
 *
 * In `core` rather than `domain` so this facade can speak it, and `@Serializable`
 * because `domain` persists it inside a spec and inside the cached resource list.
 *
 * A room and a zone are both [GROUP]: they differ in how they are *arranged* on the
 * bridge — a light belongs to exactly one room and to any number of zones — and not
 * at all in how they are controlled. The picker keeps them in separate sections
 * because that difference is worth seeing; nothing below the picker needs it.
 */
@Serializable
enum class SmartHomeTargetKind { LIGHT, GROUP, SCENE }

/**
 * What `action.light_control` does.
 *
 * Carries no `@Label`s, which is a package rule rather than an omission: that
 * annotation lives in `domain`, and `core` may not import it. It costs nothing —
 * `NodeSchema` prettifies an unlabelled enum name, and "Turn on" / "Turn off" /
 * "Toggle" / "Set brightness" / "Set colour" / "Set temperature" is what these
 * already say.
 *
 * `@Serializable` because an `engine/` config class names it as a property type,
 * and the config form is derived from the serialization descriptor.
 */
@Serializable
enum class LightOp {
    TURN_ON,
    TURN_OFF,
    TOGGLE,
    SET_BRIGHTNESS,
    SET_COLOUR,
    SET_TEMPERATURE,
}

/**
 * One change to one light or group.
 *
 * [op] decides which of the three value fields is read; the others are ignored
 * rather than validated, because the node's `@VisibleWhen` form has already made
 * only one of them visible and a hidden property still decodes to its default.
 */
@Suppress("LongParameterList") // One parameter per thing the command carries; a nested struct would only hide it.
data class LightCommand(
    val hubId: String,
    val kind: SmartHomeTargetKind,
    val rid: String,
    val op: LightOp,
    /** 0..100, as a percentage. Read only for [LightOp.SET_BRIGHTNESS]. */
    val brightnessPercent: Int = FULL_BRIGHTNESS_PERCENT,
    /** sRGB as `0xRRGGBB`. [NO_COLOUR] means "not given". Read only for [LightOp.SET_COLOUR]. */
    val colourRgb: Int = NO_COLOUR,
    /** Correlated colour temperature. `0` means "not given". Read only for [LightOp.SET_TEMPERATURE]. */
    val kelvin: Int = 0,
    /** How long the change takes. `0` is an instant snap. */
    val transitionMs: Int = SmartHomeLimits.DEFAULT_TRANSITION_MS,
    /**
     * Change only what is **already lit**, and turn nothing on.
     *
     * Read for the three value-setting operations and ignored by the other three,
     * where it would mean nothing: an explicit turn-on that skipped a light for
     * being off would do nothing at all.
     *
     * It exists because the ordinary behaviour — documented on
     * [LightOp.SET_BRIGHTNESS] and deliberate — is that setting a value also turns
     * the light on. That is right for "dim the lamp to 30 %" and wrong for "warm
     * everything in the living room down for the evening", which should not light
     * four lamps nobody had switched on. This is the opt-out, and it is honest
     * about its cost: the states have to be read first, and a group is then written
     * one light at a time rather than in a single request.
     */
    val onlyIfOn: Boolean = false,
) {
    companion object {
        const val FULL_BRIGHTNESS_PERCENT = 100

        /**
         * Not a colour. `-1` rather than a nullable `Int?`, so that the same
         * sentinel means "no colour" on the way in here and on the way out in
         * [LightReading] — a light with no colour gamut answers the same thing a
         * caller that asked for none sent.
         */
        const val NO_COLOUR = -1
    }
}

data class LightCommandResult(val changed: Boolean, val error: String = "")

/**
 * What `action.light_scene` does with a scene.
 *
 * **A scene has no "off" of its own**, and that is the fact this enum is built
 * around: a scene is a saved arrangement of lights, so recalling it is a real
 * operation and un-recalling it is not. What people mean by turning a scene off is
 * turning off *the room or zone it belongs to* — which is exactly what
 * [TURN_OFF] and the second half of [TOGGLE] do. The group is found from the scene,
 * so the node still asks for one thing and not two.
 *
 * Carries no `@Label`s for [LightOp]'s reason: that annotation lives in `domain`,
 * and "Activate" / "Turn off" / "Toggle" is what the prettified names already say.
 */
@Serializable
enum class SceneOp {
    ACTIVATE,
    TURN_OFF,
    TOGGLE,
}

data class SceneRecall(
    val hubId: String,
    val rid: String,
    val op: SceneOp = SceneOp.ACTIVATE,
    val transitionMs: Int = SmartHomeLimits.DEFAULT_TRANSITION_MS,
)

/**
 * [changed] rather than "activated", because two of the three operations do not
 * activate anything — and a `false` reported after a successful turn-off would read
 * as a failure to an `action.if`. [LightCommandResult]'s shape, for that reason.
 */
data class SceneResult(val changed: Boolean, val error: String = "")

data class LightRead(val hubId: String, val kind: SmartHomeTargetKind, val rid: String)

/**
 * One light or group as the hub reports it right now.
 *
 * [found] is separate from the values for [MailFetchResult]'s reason: a light that
 * answered "off" and a bridge that could not be reached must not look the same to
 * an `action.if`. Everything below [found] is meaningless when it is false.
 *
 * [colourRgb] is [LightCommand.NO_COLOUR] and [kelvin] is `0` for a light with no
 * colour or no temperature support, and for a **group** — a group reports aggregate
 * on-ness and brightness and nothing else, which is a limitation of the API rather
 * than of this design, and is reported honestly rather than guessed at.
 */
data class LightReading(
    val found: Boolean,
    val name: String = "",
    val on: Boolean = false,
    val brightnessPercent: Double = 0.0,
    val colourRgb: Int = LightCommand.NO_COLOUR,
    val kelvin: Int = 0,
    val reachable: Boolean = false,
    val error: String = "",
)

/**
 * Sizes and timeouts, in one place so the transport and the nodes cannot drift
 * apart about them.
 */
object SmartHomeLimits {

    /** What a hub feels like when it responds. The bridge's own default is 400 ms. */
    const val DEFAULT_TRANSITION_MS = 400

    /** Ceiling on a fade, announced in the run log the way `MAX_ITERATIONS` is. */
    const val MAX_TRANSITION_MS = 60_000

    /**
     * Load-bearing rather than a tidy default, on [MailLimits]' reasoning: a bridge
     * that has been unplugged does not refuse the connection, it silently drops the
     * SYN — and an unbounded connect inside an action is a macro that never
     * finishes.
     */
    const val CONNECT_TIMEOUT_MS = 5_000

    const val READ_TIMEOUT_MS = 10_000

    /**
     * How long the `data/` half waits between two commands to the same hub.
     *
     * A bridge accepts roughly ten light commands and one group command a second
     * before it starts dropping them on the floor, and it drops them *silently* —
     * an `action.for_each` over twenty lights would light twelve of them and report
     * success for all twenty. Pacing is therefore not politeness; it is the
     * difference between the node working and the node lying.
     */
    const val COMMAND_SPACING_MS = 100L
}

/**
 * No hub available: every call fails closed, naming the one thing the user can do
 * about it.
 *
 * The engine-only default, so a test that builds an
 * [com.example.ottomatic.engine.ExecutionContext] without a bridge sees exactly
 * what a phone with an empty hub library reports — which the nodes already have to
 * handle.
 */
object NoSmartHome : SmartHome {

    override suspend fun apply(command: LightCommand) = LightCommandResult(changed = false, error = UNAVAILABLE)

    override suspend fun recall(request: SceneRecall) = SceneResult(changed = false, error = UNAVAILABLE)

    override suspend fun read(request: LightRead) = LightReading(found = false, error = UNAVAILABLE)

    private const val UNAVAILABLE = "No smart-home hub is set up on this phone"
}
