package com.bitchat.android.model

/**
 * Command suggestion data class for IRC-style command autocomplete.
 */
data class CommandSuggestion(
    val command: String,
    val aliases: List<String> = emptyList(),
    val syntax: String? = null,
    val description: String
)
