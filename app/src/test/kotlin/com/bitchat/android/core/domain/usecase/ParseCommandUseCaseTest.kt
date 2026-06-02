package com.bitchat.android.core.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseCommandUseCaseTest {

    private val parse = ParseCommandUseCase()

    @Test fun `non-command returns null`() {
        assertNull(parse("hello world"))
        assertNull(parse("@alice hi"))
    }

    @Test fun `join normalizes tag and reads password`() {
        assertEquals(ChatCommand.Join("#gen", null), parse("/j gen"))
        assertEquals(ChatCommand.Join("#foo", "secret"), parse("/join #foo secret"))
    }

    @Test fun `join without arg is usage`() {
        assertEquals(ChatCommand.Usage("usage: /join <channel>"), parse("/join"))
    }

    @Test fun `msg strips at and joins body`() {
        assertEquals(ChatCommand.Msg("alice", "hey there"), parse("/msg @alice hey there"))
        assertEquals(ChatCommand.Msg("bob", null), parse("/m bob"))
    }

    @Test fun `simple commands`() {
        assertEquals(ChatCommand.Who, parse("/w"))
        assertEquals(ChatCommand.Clear, parse("/clear"))
        assertEquals(ChatCommand.Channels, parse("/channels"))
    }

    @Test fun `block list vs target`() {
        assertEquals(ChatCommand.Block(null), parse("/block"))
        assertEquals(ChatCommand.Block("x"), parse("/block @x"))
    }

    @Test fun `unblock requires nickname`() {
        assertEquals(ChatCommand.Usage("usage: /unblock <nickname>"), parse("/unblock"))
        assertEquals(ChatCommand.Unblock("x"), parse("/unblock x"))
    }

    @Test fun `actions hug and slap`() {
        assertEquals(ChatCommand.Action(ActionKind.HUG, "sam"), parse("/hug @sam"))
        assertEquals(ChatCommand.Action(ActionKind.SLAP, "sam"), parse("/slap sam"))
    }

    @Test fun `unknown command`() {
        assertEquals(ChatCommand.Unknown("/foo"), parse("/foo bar"))
    }
}
