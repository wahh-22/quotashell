package dev.wahh.quotashell.core

import dev.wahh.quotashell.core.ui.ClipboardProvenance
import org.junit.Assert.*
import org.junit.Test

class ClipboardProvenanceTest {
    @Test fun unchangedPastedAndSavedValueCanClearOnlyOnSupportedApis() {
        val provenance = ClipboardProvenance()
        provenance.pasted("A")
        val saved = provenance.saved("A")
        assertNotNull(saved)
        assertTrue(saved!!.canClear("A", 28))
        assertFalse(saved.canClear("A", 27))
        assertFalse(saved.canClear(null, 36))
        assertFalse(saved.toString().contains("A"))
    }

    @Test fun clipboardChangesBetweenPasteAndSaveCannotAttributeTheSecondValue() {
        val provenance = ClipboardProvenance()
        provenance.pasted("A")
        val saved = provenance.saved("A")!!
        assertFalse(saved.canClear("B", 36))
        assertNull(provenance.saved("B"))
    }

    @Test fun clipboardChangesAfterSaveAreAlsoRejectedAtClearTime() {
        val provenance = ClipboardProvenance()
        provenance.pasted("A")
        val saved = provenance.saved("A")!!
        assertTrue(saved.canClear("A", 36))
        assertFalse(saved.canClear("B", 36))
    }

    @Test fun editingAfterPasteInvalidatesProvenanceEvenIfTheSameTextIsRetyped() {
        val provenance = ClipboardProvenance()
        provenance.pasted("A")
        provenance.edited()
        assertNull(provenance.saved("A"))
        assertNull(provenance.saved("B"))
    }

    @Test fun laterPasteReplacesProvenanceAndManualEntryHasNone() {
        val provenance = ClipboardProvenance()
        assertNull(provenance.saved("A"))
        provenance.pasted("A")
        provenance.pasted("B")
        assertNull(provenance.saved("A"))
        assertTrue(provenance.saved("B")!!.canClear("B", 36))
    }

    @Test fun savedReceiptSurvivesClearingTheDraftButDoesNotMatchOtherClipboardText() {
        val provenance = ClipboardProvenance()
        provenance.pasted("A")
        val saved = provenance.saved("A")!!
        provenance.edited()
        assertNull(provenance.saved("A"))
        assertTrue(saved.canClear("A", 36))
        assertFalse(saved.canClear("B", 36))
    }
}
