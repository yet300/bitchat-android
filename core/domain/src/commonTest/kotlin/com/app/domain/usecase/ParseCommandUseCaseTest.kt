package com.app.domain.usecase

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ParseCommandUseCaseTest {

    private val parse = ParseCommandUseCase()

    @Test fun `non-command returns null`() {
        assertNull(parse("hello world"))
        assertNull(parse("@alice hi"))
    }

    @Test fun `msg strips at and joins body`() {
        assertEquals(ChatCommand.Msg("alice", "hey there"), parse("/msg @alice hey there"))
        assertEquals(ChatCommand.Msg("bob", null), parse("/m bob"))
    }

    @Test fun `simple commands`() {
        assertEquals(ChatCommand.Who, parse("/w"))
        assertEquals(ChatCommand.Clear, parse("/clear"))
    }

    @Test fun `retired password-channel commands are unknown`() {
        // Password channels were retired in favor of private groups (0x25); their commands no
        // longer parse and fall through to Unknown.
        assertEquals(ChatCommand.Unknown("/j"), parse("/j gen"))
        assertEquals(ChatCommand.Unknown("/join"), parse("/join #foo secret"))
        assertEquals(ChatCommand.Unknown("/pass"), parse("/pass hunter2"))
        assertEquals(ChatCommand.Unknown("/save"), parse("/save"))
        assertEquals(ChatCommand.Unknown("/channels"), parse("/channels"))
        assertEquals(ChatCommand.Unknown("/transfer"), parse("/transfer @alice"))
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
