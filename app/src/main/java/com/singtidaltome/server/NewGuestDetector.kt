package com.singtidaltome.server

import com.singtidaltome.party.Guest

internal class NewGuestDetector(initialGuests: Collection<Guest> = emptyList()) {
    private val knownIds = initialGuests.mapTo(mutableSetOf()) { it.id }

    fun update(guests: Collection<Guest>): List<Guest> {
        val newGuests = guests.filter { it.id !in knownIds }
        knownIds += guests.map { it.id }
        return newGuests
    }
}
