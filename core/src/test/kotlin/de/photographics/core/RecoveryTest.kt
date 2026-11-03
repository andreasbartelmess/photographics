package de.photographics.core

import de.photographics.core.doc.Document
import de.photographics.core.editor.Recovery
import de.photographics.core.psd.PsdReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** Automatische Sicherung: schreiben, Beschreibung lesen, PSD wieder öffnen, aufräumen. */
class RecoveryTest {
    @Test
    fun writeReadClear() {
        val dir = Files.createTempDirectory("pg-recovery").toFile()
        val r = Recovery(java.io.File(dir, "a"))
        assertNull(r.info())
        val d = Document.blank(64, 48)
        r.write(d, "bild.psd", "/tmp/bild.psd", "PSD", Files.createTempDirectory("pg-spool").toFile())
        val i = r.info()!!
        assertEquals("bild.psd", i.name); assertEquals(64, i.width); assertEquals("/tmp/bild.psd", i.target); assertEquals("PSD", i.format)
        val back = PsdReader.read(r.file.readBytes())
        assertEquals(64, back.width); assertEquals(48, back.height)
        r.delete()
        assertNull(r.info())
        dir.deleteRecursively()
    }
}
