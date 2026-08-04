package com.example.ottomatic.core.service

/**
 * The device's address book, seen from the engine: one question, "what number can
 * this person be reached on right now?".
 *
 * Its own facade rather than a member of [SystemServices], which is deliberately
 * write-only — every one of its members *changes* something — and not part of
 * [DeviceState], which is for cheap synchronous device properties rather than a
 * content-provider round trip that fails outright on a missing permission.
 *
 * Action-only, and provably so rather than by convention: reading a contact needs
 * `READ_CONTACTS`, and a value node may declare no permissions at all
 * (`NodeDeclarationContractTest`), so there is no pull-side reader that could reach
 * this.
 */
interface Contacts {

    /**
     * The number the contact [lookupKey] names, or null.
     *
     * Null on **every** failure — no permission, a contact since deleted, a contact
     * whose last number was removed — because all three mean the same thing to a
     * caller: do not dial. A node that gets null says so in the log and pulses `out`,
     * which is the degradation a missing variable declaration already gets.
     */
    fun phoneNumber(lookupKey: String): String?
}

/** No address book at all: every lookup fails closed. The engine-only default. */
object NoContacts : Contacts {
    override fun phoneNumber(lookupKey: String): String? = null
}
