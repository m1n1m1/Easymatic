package io.github.m1n1m1.easymatic.data

/**
 * A library that can re-read its file after something else rewrote it.
 *
 * Every JSON library under `data/` reads its file once, in its constructor, and serves
 * a cache from then on — deliberately, because the trigger host resolves a place or an
 * account while arming and has no suspending context to read a file in. A restore writes
 * those files from outside any repository, so it has to ask each one to look again; the
 * flows collected off the caches (`SmartHomeHubs`, `AiConnections`, the hub sockets) then
 * follow on their own. `BackupRepository` holds these as a list and never calls anything
 * more specific.
 */
interface ReloadableLibrary {

    /** Re-reads the file and republishes the cache, under the same lock every write takes. */
    suspend fun reload()
}
