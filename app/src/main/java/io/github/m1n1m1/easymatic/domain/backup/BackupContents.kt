package io.github.m1n1m1.easymatic.domain.backup

/**
 * What a backup is made of: the `filesDir` entries that are "settings and macros".
 *
 * **One list, read by three things.** The backup file the user writes from the Backup
 * screen carries exactly these entries; the two Auto Backup rule files
 * (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`) `<include>` exactly
 * these, so an Android restore or a device-to-device transfer carries the same set;
 * and `BackupContentsTest` pins both XML files to this list and pins this list to the
 * directories the repositories actually create — so a new library that nobody classified
 * fails the build rather than quietly missing from every backup.
 *
 * **An include list, not an exclude list, and that is the decision here.** "Everything
 * under `filesDir` except the run log" reads naturally and is wrong: it also sweeps in
 * Glance's widget DataStore (keyed by widget ids that do not survive a restore),
 * WorkManager's database (re-enqueued on arm, and a restored copy names jobs that no
 * longer exist), whatever a library caches next year, and the restore staging area
 * itself. Android's rule files switch to include-only the moment one `<include>` appears,
 * which is exactly the behaviour wanted. The six SharedPreferences files holding dedup
 * and hysteresis state (mail high-water marks, seen images, fence registrations) are
 * therefore excluded by construction — restored onto another phone they would suppress
 * first fires and claim fences that were never registered there.
 */
object BackupContents {

    /** Where the workflows live, one `<id>.json` each. */
    const val WORKFLOWS_DIR = "workflows"

    /** The suffix of every workflow file under [WORKFLOWS_DIR]. */
    const val WORKFLOW_SUFFIX = ".json"

    /** The variable *values*, keyed by `VariableRef.storeKey`. */
    const val VARIABLES_FILE = "variables.json"

    /**
     * The `filesDir`-relative top-level entries a backup carries, in the order they are
     * written. Every one but [VARIABLES_FILE] is a directory. `secrets` is the escrow:
     * every credential again, under the backup password rather than the Keystore, which
     * is what lets one typed password put them all back after a restore.
     */
    val included: List<String> = listOf(
        WORKFLOWS_DIR,
        VARIABLES_FILE,
        "variables",
        "places",
        "nfc",
        "mail",
        "smarthome",
        "ai",
        "api",
        "plugins",
        "secrets",
    )

    /**
     * App-owned `filesDir` entries deliberately left out: the run log, the file nodes'
     * own storage area and the microphone's unfinished recordings. Listed so the drift
     * test can assert that `included + excludedDirs` is everything the app creates.
     */
    val excludedDirs: List<String> = listOf("logs", "macrofiles", "recordings-part")

    /**
     * The entries a restore replaces wholesale: every included one except the two that
     * are merged — workflows are added beside the phone's own, and variable values are
     * merged so a renamed copy keeps its counters.
     */
    val replaced: List<String> = included - WORKFLOWS_DIR - VARIABLES_FILE

    /** Whether a `filesDir`-relative path is part of a backup. */
    fun isIncluded(relativePath: String): Boolean =
        relativePath.substringBefore('/') in included
}
