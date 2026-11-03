package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.history.History
import de.photographics.core.history.HistoryEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verlauf merkt sich den gespeicherten Stand (Punkt im Titel, Nachfrage vor dem Verwerfen). */
class SavedStateTest {
    private class E(override val label: String) : HistoryEntry {
        override fun undo(doc: Document) {}
        override fun redo(doc: Document) {}
    }
    private val doc = Document(4, 4)

    @Test
    fun undoRedoBackToSavedState() {
        val h = History()
        assertTrue("neu = unverändert", h.atSavedState)
        h.push(E("a")); assertFalse(h.atSavedState)
        h.undo(doc); assertTrue("zurück zum Anfang", h.atSavedState)
        h.redo(doc); h.push(E("b")); h.markSaved(); assertTrue(h.atSavedState)
        h.undo(doc); assertFalse(h.atSavedState)
        h.redo(doc); assertTrue("Wiederholen bis zum gespeicherten Stand", h.atSavedState)
        h.undo(doc); h.push(E("c")); assertFalse("gespeicherter Stand im verworfenen Wiederholen-Zweig", h.atSavedState)
    }

    @Test
    fun droppedStartIsUnreachable() {
        val h = History(maxEntries = 3)
        repeat(4) { h.push(E("$it")) }   // erster Schritt fällt weg
        repeat(3) { h.undo(doc) }
        assertFalse("Anfangszustand nicht mehr erreichbar", h.atSavedState)
        h.clear(); assertFalse(h.atSavedState)
        h.markSaved(); assertTrue(h.atSavedState)
    }
}
