package io.github.m1n1m1.easymatic.data.mqtt

/**
 * MQTT topic and topic-filter rules, as pure functions.
 *
 * Pure and JVM-tested for `WebUrl`'s and `MessengerLink`'s reason: the interesting half
 * is *what a filter means*, and every one of the cases worth pinning — a `#` that swallows
 * the rest of a tree, a `+` that must not, and the `$SYS` carve-out — is a rule about
 * text rather than about a broker. Getting one wrong produces a trigger that never fires
 * or one that fires on everything, and neither reports anything.
 *
 * **The matching is ours rather than the broker's, and it has to be.** A broker matches
 * the filters it was *subscribed with*, and this app subscribes one filter per armed node
 * over one shared connection — so when a message arrives, the thing that has to decide
 * which nodes wanted it is here. That is `HaConnections`' routing table in a second
 * setting, and the same rule applies: a busy broker publishes constantly, and waking
 * every armed trigger to run its own filter would be the design that rule exists to
 * refuse.
 */
object MqttTopics {

    /**
     * Whether [topic] is something that can be published to.
     *
     * Wildcards are refused, which is the one rule a user is likely to break on purpose:
     * `home/+/set` reads like a way to set every room at once, and MQTT has no such
     * thing. Refusing it here is what lets the node say so instead of a broker dropping
     * the connection, which is what most brokers do with an illegal publish.
     */
    fun isPublishable(topic: String): Boolean =
        topic.isNotEmpty() &&
            topic.length <= MAX_LENGTH &&
            WILDCARDS.none { it in topic } &&
            NULL_CHARACTER !in topic

    /**
     * Whether [filter] is something that can be subscribed to.
     *
     * The two structural rules are the ones people get wrong: **`#` must be the last
     * level and alone in it**, so `home/#/state` and `home/te#` are both illegal, and
     * **`+` must occupy a whole level**, so `home/+/state` is fine and `home/te+t` is
     * not.
     */
    fun isSubscribable(filter: String): Boolean {
        if (filter.isEmpty() || filter.length > MAX_LENGTH || NULL_CHARACTER in filter) return false
        val levels = filter.split(SEPARATOR)
        return levels.withIndex().all { (index, level) ->
            when {
                MULTI_LEVEL in level -> level == MULTI_LEVEL.toString() && index == levels.lastIndex
                SINGLE_LEVEL in level -> level == SINGLE_LEVEL.toString()
                else -> true
            }
        }
    }

    /**
     * Whether [topic] is one of the things [filter] names.
     *
     * Both are assumed well-formed; an ill-formed filter simply matches nothing, which is
     * the fail-closed answer and is also what a broker would do with it.
     *
     * **The `$` rule is not an optimisation.** A topic beginning with `$` is a broker's
     * own — `$SYS/broker/uptime`, and the `$share` prefix on a shared subscription — and
     * the specification says a leading `#` or `+` must not reach them. Without it, a
     * macro watching `#` to see what a broker publishes would be woken by the broker's
     * own statistics several times a second, which reads as the trigger being broken.
     */
    fun matches(filter: String, topic: String): Boolean {
        if (topic.startsWith(RESERVED_PREFIX) && startsWildcard(filter)) return false
        return matchLevels(filter.split(SEPARATOR), topic.split(SEPARATOR))
    }

    /** Level by level, with the two wildcards handled where they are found. */
    @Suppress("ReturnCount") // One exit per way a filter can stop matching; that is the algorithm.
    private fun matchLevels(filter: List<String>, topic: List<String>): Boolean {
        var index = 0
        while (index < filter.size) {
            val level = filter[index]
            when {
                // Swallows every remaining level, **including none at all**: `sport/#`
                // matches `sport` itself. That is a rule of the specification rather than
                // an accident of this loop, and it is the one people are surprised by.
                level == MULTI_LEVEL.toString() -> return true
                index >= topic.size -> return false
                level == SINGLE_LEVEL.toString() -> Unit
                level != topic[index] -> return false
            }
            index++
        }
        return index == topic.size
    }

    private fun startsWildcard(filter: String): Boolean =
        filter.startsWith(MULTI_LEVEL) || filter.startsWith(SINGLE_LEVEL)

    private const val SEPARATOR = '/'
    private const val SINGLE_LEVEL = '+'
    private const val MULTI_LEVEL = '#'
    private const val RESERVED_PREFIX = "$"
    /**
     * The one character the specification forbids outright.
     *
     * A **space is not** one, deliberately: it is discouraged and perfectly legal, and
     * real devices do publish under names containing them. Refusing it here would make
     * this the only client in the house that cannot see those topics.
     */
    private const val NULL_CHARACTER = '\u0000'

    /** The protocol's own bound: a topic name is a two-byte-prefixed UTF-8 string. */
    private const val MAX_LENGTH = 65_535

    private val WILDCARDS = listOf(SINGLE_LEVEL, MULTI_LEVEL)
}
