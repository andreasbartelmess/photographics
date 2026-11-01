# photographics für GNOME (in Arbeit)

Desktop-Version mit GTK 4 und libadwaita. Sie nutzt denselben Kern (`:core`) wie die Android-App:
Ebenen, Mischmodi, PSD/TIFF/XCF, Verlauf und Speicherverwaltung sind dieselben. Die Oberfläche ist
in Kotlin über [java-gi](https://java-gi.org) (GTK-Bindings über die Foreign-Function-API) geschrieben.

**Stand:** Der Funktionsumfang der Android-App ist zum großen Teil übernommen:

- **Dateien:** Öffnen (PSD, TIFF, XCF, PNG, JPEG, WebP; auch per Ziehen), Neues Bild, Als Ebene platzieren,
  Speichern als PSD/TIFF/XCF, Export als PNG/JPEG (sRGB), zuletzt verwendete Dateien
- **Werkzeuge** (Photoshop-Kürzel): Verschieben (V, automatisch auswählen), Transformieren (Strg+T),
  Rechteck/Ellipse (M), Lasso (L), Zauberstab/Objektauswahl (W), Freistellen (C), Pinsel/Radierer (B/E) mit
  Vorgaben und allen Pinseldynamiken, Verlauf (G), Pipette (I), Bereichsreparatur (J), KI-Entfernen,
  Reparatur-Pinsel, Kopierstempel (S, Strg+Klick = Quelle), Ausbessern, Rote Augen, Weichzeichner,
  Scharfzeichner, Wischfinger, Verflüssigen, Abwedler/Nachbelichter/Schwamm, Text (T), Hand (H, Leertaste)
- **Ebenen:** Gruppen, Masken (malen per Klick auf das Masken-Vorschaubild), Schnittmasken,
  Smartobjekte, Deckkraft/Fläche/Mischmodus, Eigenschaften, Rechtsklickmenü; Protokoll mit Rücksprung
- **Korrekturen** als Einstellungsebenen oder direkt (Kurven mit Histogramm und Kanälen u. a.), alle Filter
  der Android-App mit Live-Vorschau in der Seitenleiste
- **Bild:** Bildgröße (auch KI-Hochskalierung), Arbeitsfläche, Drehen/Spiegeln, Farbprofil zuweisen/umwandeln
- **KI-Modelle** (MI-GAN, LaMa, Real-ESRGAN) laden bei Bedarf und laufen lokal über ONNX Runtime

Noch nicht übernommen: Farbfelder, Hilfslinien/Einrasten, Farbproof, LUT-Import, eigene Pinselvorgaben
speichern, perspektivisches Freistellen und Verzerren, Drucken, Einstellungen.

## Starten

Voraussetzungen: GTK 4 und libadwaita (bei GNOME vorhanden), Java 25 zum Ausführen
(Fedora: `sudo dnf install java-25-openjdk`). Gebaut wird mit dem JDK des Projekts; das JDK 25
für die Übersetzung lädt Gradle bei Bedarf selbst.

```sh
./gradlew :gnome:run --args="bild.psd"   # aus dem Quellbaum
./gnome/install.sh                         # für den Benutzer installieren (GNOME-Menü, Befehl photographics)
./gnome/install.sh --remove                # wieder entfernen
./gradlew :gnome:test                      # Öffnen/Speichern über GdkPixbuf und den Kern
```

Für Tests und Bilder der Website kann sich die App selbst fotografieren:
`PG_SCREENSHOT=bild.png PG_SCREENSHOT_SCALE=1.5 PG_ACTIONS="tool:BRUSH,drag:10;10;500;300,undo" ./gradlew :gnome:run --args=…`
(`PG_SCREENSHOT_DELAY` in ms, `PG_SCREENSHOT_ZOOM` setzt den Zoom; läuft als eigene Instanz neben einer offenen App).
Optionaler KI-Test (lädt MI-GAN): `PG_AI_TEST=1 XDG_DATA_HOME=/tmp/pg ./gradlew :gnome:test`.

## Aufbau

- `Main.kt` – Anwendung, Sprache, Kachelspeicher (außerhalb des Java-Heaps), Icon und Stile
- `Engine.kt` – ein Thread besitzt die Dokumente (der Kern ist nicht threadsicher); GTK läuft im Hauptthread
- `MainWindow.kt` – ein Fenster je Dokument: Kopfleiste, Aktionen und Tastenkürzel, Dateidialoge
- `CanvasView.kt` – eigenes Widget: Kacheln je Verkleinerungsstufe, als Texturen von GTK skaliert,
  Kanten auf Gerätepixel (auch bei 125/150 % Skalierung)
- `Controller.kt` – Werkzeuge und Bearbeitungsbefehle (entspricht dem ViewModel der Android-App)
- `Tools.kt`, `ToolPalette.kt`, `OptionsBar.kt` – Werkzeugliste, Werkzeugleiste, Werkzeugoptionen
- `PropertiesPanel.kt` – Filter, Korrekturen, Text in der Seitenleiste; `Dialogs.kt` – kleine Formulare
- `PangoText.kt` – Textebenen über Pango/Cairo; `ai/` – KI-Modelle (ONNX Runtime)
- `LayersPanel.kt`, `HistoryPanel.kt` – Ebenen und Protokoll
- `FileIO.kt` – Öffnen/Speichern (Ebenenformate über den Kern, PNG/JPEG/WebP über GdkPixbuf)
