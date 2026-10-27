package dev.wahh.quotashell.core

import dev.wahh.quotashell.core.ui.*
import org.junit.Assert.*
import org.junit.Test

class InputUxTest {
    @Test fun credentialStatesReuseTheCredentialContractWithoutEchoingInput() {
        assertEquals(FieldState.EMPTY, credentialField("").state)
        assertTrue(credentialField("valid-token").valid)
        for (value in listOf("white space", "line\nbreak", "é", "x".repeat(16385))) {
            val result = credentialField(value)
            assertEquals(FieldState.INVALID, result.state)
            assertFalse(result.message.contains(value))
        }
        assertTrue(credentialField("sessionKey=valid", kind = ClaudeCredentialKind.SESSION_KEY).valid)
        assertFalse(credentialField("bad;cookie", kind = ClaudeCredentialKind.SESSION_KEY).valid)
        assertFalse(credentialField("valid", "bad account").valid)
        assertTrue(credentialField("valid", "").valid)
    }

    @Test fun optionalAccountHasItsOwnNonSecretErrorAndAcceptsEmpty() {
        assertTrue(accountField("").valid)
        assertTrue(accountField("workspace-id").valid)
        assertFalse(accountField("wrong account").valid)
        assertFalse(accountField("wrong account").message.contains("wrong account"))
        assertFalse(accountField("x".repeat(16385)).valid)
    }

    @Test fun hexAndStaleFieldsExposeBoundaries() {
        assertEquals(FieldState.EMPTY, hexField("").state)
        assertTrue(hexField("aB09F0").valid)
        assertFalse(hexField("#ABCDEF").valid)
        assertFalse(hexField("12345G").valid)
        assertFalse(hexField("1234567").valid)
        assertTrue(staleField("30").valid)
        assertTrue(staleField("1440").valid)
        assertFalse(staleField("29").valid)
        assertFalse(staleField("1441").valid)
        assertFalse(staleField("not a number").valid)
    }

    @Test fun settingsDraftRequiresValidChangesAndDiscardsToBaseline() {
        val initial = PhantomTheme.default()
        val draft = SettingsDraft(initial, 60)
        assertFalse(draft.dirty)
        assertFalse(draft.validToSave)
        draft.update(initial.withChartStyle(ChartStyle.RING), "60")
        assertTrue(draft.dirty)
        assertTrue(draft.validToSave)
        draft.update(null, "60")
        assertTrue(draft.dirty)
        assertFalse(draft.validToSave)
        draft.update(initial, "29")
        assertFalse(draft.validToSave)
        draft.discard()
        assertFalse(draft.dirty)
        assertEquals(initial.toJson(), draft.theme?.toJson())
        assertEquals("60", draft.stale)
        draft.update(PhantomTheme.parse(initial.toJson()), "60")
        assertFalse(draft.dirty)
    }

    @Test fun switchingModesClearsOnlyWhenTheModeActuallyChanges() {
        assertEquals("draft", secretAfterModeChange(ClaudeCredentialKind.OAUTH_TOKEN, ClaudeCredentialKind.OAUTH_TOKEN, "draft"))
        assertEquals("", secretAfterModeChange(ClaudeCredentialKind.OAUTH_TOKEN, ClaudeCredentialKind.SESSION_KEY, "draft"))
        assertEquals("", secretAfterModeChange(ClaudeCredentialKind.SESSION_KEY, ClaudeCredentialKind.OAUTH_TOKEN, "draft"))
    }

    @Test fun clipboardClearNeedsASuccessfulPasteAndSupportedApiAndUnchangedClipboard() {
        assertTrue(canClearClipboard(true, true, true, 28))
        assertFalse(canClearClipboard(false, true, true, 36))
        assertFalse(canClearClipboard(true, false, true, 36))
        assertFalse(canClearClipboard(true, true, false, 36))
        assertFalse(canClearClipboard(true, true, true, 27))
    }
}
