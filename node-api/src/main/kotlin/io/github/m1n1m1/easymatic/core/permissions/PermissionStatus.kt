package io.github.m1n1m1.easymatic.core.permissions

/**
 * Outcome of checking a [Permission]. [Denied.showRationale] is true when the
 * user has previously denied the request (and not "don't ask again"); the UI
 * may show an explanation before re-prompting or direct the user to Settings.
 */
sealed interface PermissionStatus {
    data object Granted : PermissionStatus
    data class Denied(val showRationale: Boolean) : PermissionStatus
}
