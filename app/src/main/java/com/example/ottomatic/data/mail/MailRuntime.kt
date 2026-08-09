package com.example.ottomatic.data.mail

import com.example.ottomatic.core.service.Mail

/**
 * The mail facade and the seen-store, reachable from a component nobody can inject
 * into.
 *
 * [MailPollWorker] is built by WorkManager from a `(Context, WorkerParameters)`
 * constructor and nothing else, so there is no seam to pass a repository through —
 * the same problem `VariableStore` solves the same way, and for the same reason it
 * is attached from `ServiceLocator` rather than constructed on demand. Building a
 * second `MailAccountRepository` inside the worker would read the same file and be
 * *almost* right, which is worse than obviously wrong: the process would then have
 * two caches of one library and no rule about which was current.
 *
 * Both members are null until `ServiceLocator.init` has run. That is not a state
 * anything can observe in practice — `Application.onCreate` precedes every worker
 * and every trigger — but a worker that somehow ran first reports "not ready" and
 * asks WorkManager to retry rather than crashing on a `lateinit`.
 */
object MailRuntime {

    private var mailRef: Mail? = null
    private var seenRef: MailSeenStore? = null

    /** Called once from `ServiceLocator.init`, before any macro can be armed. */
    fun attach(mail: Mail, seen: MailSeenStore) {
        mailRef = mail
        seenRef = seen
    }

    val mail: Mail? get() = mailRef

    val seen: MailSeenStore? get() = seenRef
}
