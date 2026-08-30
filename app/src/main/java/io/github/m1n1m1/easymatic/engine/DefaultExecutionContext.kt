package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.service.Ai
import io.github.m1n1m1.easymatic.core.service.Calendars
import io.github.m1n1m1.easymatic.core.service.NoCalendars
import io.github.m1n1m1.easymatic.core.service.Files
import io.github.m1n1m1.easymatic.core.service.Images
import io.github.m1n1m1.easymatic.core.service.Contacts
import io.github.m1n1m1.easymatic.core.service.DeviceState
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.NoAi
import io.github.m1n1m1.easymatic.core.service.NoFiles
import io.github.m1n1m1.easymatic.core.service.NoImages
import io.github.m1n1m1.easymatic.core.service.NoContacts
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.LogSource
import io.github.m1n1m1.easymatic.core.service.MacroControl
import io.github.m1n1m1.easymatic.core.service.Mail
import io.github.m1n1m1.easymatic.core.service.Media
import io.github.m1n1m1.easymatic.core.service.Messaging
import io.github.m1n1m1.easymatic.core.service.Microphone
import io.github.m1n1m1.easymatic.core.service.NoMail
import io.github.m1n1m1.easymatic.core.service.NoMedia
import io.github.m1n1m1.easymatic.core.service.NoMessaging
import io.github.m1n1m1.easymatic.core.service.NoMicrophone
import io.github.m1n1m1.easymatic.core.service.NoSpeech
import io.github.m1n1m1.easymatic.core.service.NoTranslation
import io.github.m1n1m1.easymatic.core.service.NoNotifications
import io.github.m1n1m1.easymatic.core.service.Notifications
import io.github.m1n1m1.easymatic.core.service.NoPrompts
import io.github.m1n1m1.easymatic.core.service.HomeAssistant
import io.github.m1n1m1.easymatic.core.service.Mqtt
import io.github.m1n1m1.easymatic.core.service.NoHomeAssistant
import io.github.m1n1m1.easymatic.core.service.NoMqtt
import io.github.m1n1m1.easymatic.core.service.NoSmartHome
import io.github.m1n1m1.easymatic.core.service.SmartHome
import io.github.m1n1m1.easymatic.core.service.NoScripts
import io.github.m1n1m1.easymatic.core.service.NoVariables
import io.github.m1n1m1.easymatic.core.service.Prompts
import io.github.m1n1m1.easymatic.core.service.ScriptEngine
import io.github.m1n1m1.easymatic.core.service.Variables
import io.github.m1n1m1.easymatic.core.service.Speech
import io.github.m1n1m1.easymatic.core.service.Translation
import io.github.m1n1m1.easymatic.core.service.SystemServices
import io.github.m1n1m1.easymatic.core.service.UnknownDeviceState
import io.github.m1n1m1.easymatic.core.service.DelayWaits
import io.github.m1n1m1.easymatic.core.service.Waits
import io.github.m1n1m1.easymatic.engine.trigger.NoSensors
import io.github.m1n1m1.easymatic.engine.trigger.SensorReader

/**
 * Default [ExecutionContext] wiring core-provided services to the engine.
 * Pure Kotlin — depends only on `core/` types and the engine's own sensor port.
 *
 * The [logger] is injected so the app can route entries to the run log and to
 * Logcat without `engine/` importing Android. It takes a whole [LogEntry] rather
 * than a string because attribution — which workflow, which run, which node —
 * is decided by the executor and would not survive being flattened into the
 * message. It stays the **last** parameter so `DefaultExecutionContext(services) {}`
 * keeps meaning "swallow the logs", which is how most tests build one.
 */
@Suppress("LongParameterList") // One parameter per facade the context exposes; the interface sets the count.
class DefaultExecutionContext(
    override val systemServices: SystemServices,
    override val macroControl: MacroControl? = null,
    override val deviceState: DeviceState = UnknownDeviceState,
    override val sensors: SensorReader = NoSensors,
    override val scripts: ScriptEngine = NoScripts,
    override val variables: Variables = NoVariables,
    override val contacts: Contacts = NoContacts,
    override val prompts: Prompts = NoPrompts,
    override val waits: Waits = DelayWaits,
    override val mail: Mail = NoMail,
    override val messaging: Messaging = NoMessaging,
    override val notifications: Notifications = NoNotifications,
    override val smartHome: SmartHome = NoSmartHome,
    override val homeAssistant: HomeAssistant = NoHomeAssistant,
    override val mqtt: Mqtt = NoMqtt,
    override val ai: Ai = NoAi,
    override val files: Files = NoFiles,
    override val images: Images = NoImages,
    override val calendars: Calendars = NoCalendars,
    override val microphone: Microphone = NoMicrophone,
    override val media: Media = NoMedia,
    override val speech: Speech = NoSpeech,
    override val translation: Translation = NoTranslation,
    private val logger: (LogEntry) -> Unit = {},
) : ExecutionContext {

    override fun log(message: String, level: LogLevel) = logger(LogEntry(level, message))

    override fun scoped(source: LogSource): ExecutionContext = Scoped(this, source, logger)
}

/**
 * A context that logs as [source] and is otherwise the one it wraps.
 *
 * `by delegate` is what keeps [systemServices], [ExecutionContext.scripts] and
 * every other facade pointing at the real ones — only logging is overridden.
 *
 * [scoped] is overridden even though the generated forwarder would be correct
 * here (a [LogSource] carries the whole attribution, so re-scoping through the
 * delegate produces the same object). Stating it makes the composition explicit
 * rather than a property of two things happening to line up.
 */
private class Scoped(
    private val delegate: ExecutionContext,
    private val source: LogSource,
    private val logger: (LogEntry) -> Unit,
) : ExecutionContext by delegate {

    override fun log(message: String, level: LogLevel) = logger(LogEntry(level, message, source))

    override fun scoped(source: LogSource): ExecutionContext = Scoped(delegate, source, logger)
}
