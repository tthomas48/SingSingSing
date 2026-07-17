package com.singsingsing.server

import com.singsingsing.party.PartyMessage

internal class NewPartyMessageDetector(initialMessages: Collection<PartyMessage> = emptyList()) {
    private val knownIds = initialMessages.mapTo(mutableSetOf()) { it.id }

    fun update(messages: Collection<PartyMessage>): List<PartyMessage> {
        val newMessages = messages.filter { it.id !in knownIds }
        knownIds += messages.map { it.id }
        return newMessages
    }
}
