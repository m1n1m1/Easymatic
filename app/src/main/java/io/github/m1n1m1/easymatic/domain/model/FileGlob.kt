package io.github.m1n1m1.easymatic.domain.model

/**
 * The name filter `action.file_list` applies — `*.csv`, `report-*`, `IMG_????.jpg`.
 *
 * A **glob and deliberately not a regular expression**, which is the one interesting
 * decision here. `*.txt` is what somebody types into a file filter without being
 * taught anything, where the regular expression that means the same thing is
 * `.*\.txt` — and the near-miss `*.txt` read as a regex is not an error but a
 * *different valid pattern* that matches nothing. A filter that silently matches
 * nothing looks exactly like a folder that is empty, so the failure would be
 * attributed to the wrong thing entirely.
 *
 * `*` matches any run of characters including none, `?` matches exactly one, and
 * everything else is literal. Matching is **case-insensitive**, because the volumes
 * this runs against disagree with each other — FAT and exFAT fold case where ext4
 * does not — so a case-sensitive filter would make `*.JPG` a question about which
 * card the file is on.
 *
 * A blank pattern means **everything**, which is the same degradation rule the
 * scoped choosers follow: nothing configured narrows nothing.
 *
 * Pure, and JVM-tested, on [WebUrl]'s and `MessengerLink`'s reasoning.
 */
object FileGlob {

    /** Whether [name] matches [pattern]; a blank pattern matches everything. */
    fun matches(name: String, pattern: String): Boolean {
        val glob = pattern.trim()
        if (glob.isEmpty()) return true
        return matches(name.lowercase(), 0, glob.lowercase(), 0)
    }

    /**
     * Whether `name` from [n] on matches `glob` from [g] on.
     *
     * Recursive rather than backtracking by hand: a `*` tries consuming nothing and
     * then one more character at a time, which is the whole of the algorithm and is
     * bounded by the two lengths. Names and patterns here are both short.
     */
    private fun matches(name: String, n: Int, glob: String, g: Int): Boolean {
        if (g == glob.length) return n == name.length
        return when (glob[g]) {
            '*' -> matches(name, n, glob, g + 1) ||
                (n < name.length && matches(name, n + 1, glob, g))
            '?' -> n < name.length && matches(name, n + 1, glob, g + 1)
            else -> n < name.length && name[n] == glob[g] && matches(name, n + 1, glob, g + 1)
        }
    }
}
