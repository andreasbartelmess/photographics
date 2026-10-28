# photographics

*[English version](README.md)* · [Website](https://photographics.andreasbartelmess.de)

**Ebenenbasierter Fotoeditor für Android, gemacht für professionelle Workflows unterwegs.**
Retusche, Montage und Farbkorrektur mit dem Stift – mit Ebenen, Masken, Einstellungsebenen, Mischmodi und
Farbmanagement; Dateien wandern als PSD, TIFF oder GIMP-Datei zwischen Schreibtisch und unterwegs.
Kotlin + Jetpack Compose, minSdk 27 (Android 8.1).

> **Vorschau (Version 0.2).** Vieles funktioniert, manches fehlt noch oder ändert sich. Bitte arbeite
> nicht ohne Sicherungskopie an wichtigen Dateien. Ich freue mich über Rückmeldungen, Fehlerberichte,
> Mitentwicklerinnen und Mitentwickler und alle, die das Projekt unterstützen möchten: über ein
> [Issue](../../issues/new/choose) oder an info@andreasbartelmess.de. Wobei Hilfe gebraucht wird, steht
> in der [englischen README](README.md#help-wanted).

## Bauen und auf ein Gerät bringen

Die Build-Umgebung liegt unter `~/Android` (JDK 21, Android SDK, Gradle). Android Studio findet
das SDK dort ebenfalls.

```bash
./build.sh          # Tests + Speicher-Sperre, dann Release-APKs; ist ein Gerät verbunden, wird die passende installiert
./build.sh debug    # Debug-Version ohne Tests (deutlich langsamer, mit Diagnose-Protokoll)
./build.sh test     # alle Tests inkl. Speicher-Sperre
./build.sh smoke    # Speicher-Rauchtest auf dem verbundenen Gerät
```

Die fertigen APKs liegen danach in `dist/`. Für die meisten Tablets und Handys ist
**`app-arm64-v8a-release.apk`** (≈ 30 MB) die richtige; `app-universal-release.apk` läuft überall,
ist aber größer. Release-Versionen werden mit dem eigenen Schlüssel aus `keystore.properties`
signiert (anlegen mit `tools/create_release_key.sh`, bleibt lokal); fehlt er, mit dem Debug-Schlüssel.
Zum Testen immer die Release-Version nehmen: Debug-Apps laufen auf Android um ein Vielfaches
langsamer (Retusche im Emulator: 12,9 s statt 3,7 s).

**Auf ein echtes Tablet:** *Einstellungen → Über das Tablet →* 7× auf *Build-Nummer* tippen,
dann *Entwickleroptionen → USB-Debugging* aktivieren und das Tablet per USB anschließen
(Zugriff am Tablet bestätigen). `./build.sh` installiert und startet die App.
Kabellos geht es mit *Debugging über WLAN* und `~/Android/Sdk/platform-tools/adb pair <IP:Port>`.
Alternativ das APK auf das Tablet kopieren und dort öffnen („Unbekannte Quellen“ erlauben).

**Mit Android Studio:** *Open* → diesen Ordner, dann ▶ *Run*.

## Aufbau

```
core/   reines Kotlin/JVM, ohne Android-Abhängigkeiten → auf dem PC testbar
  image/      TileGrid (256×256-Kacheln, leere Bereiche kosten nichts), Farben, Blur, Transformationen
  doc/        Document, Layer (Pixel-, Text-, Smartobjekt-, Einstellungs-, Gruppenebene), LayerMask
  render/     BlendMode (alle 27 PS-Modi), Compositor (Gruppen, Masken, Schnittmasken, Einstellungsebenen),
              StrokeRenderCache (schnelles Neuzeichnen beim Malen)
  adjust/     12 Korrekturen (Kurven, Tonwert, Farbbalance, Schwarzweiß, ...) für Einstellungsebenen
  retouch/    PatchMatch-Inpainting (inhaltsbasiert), Poisson-Blending
  psd/        PsdReader, PsdWriter, Action-Descriptor, Einstellungs-/Text-/Smartobjekt-Blöcke
  tools/      Pinsel-Engine + Retusche-Werkzeuge
  filters/    Korrekturen und Filter
  selection/  Auswahl (Rechteck, Ellipse, Lasso, weiche Kante, Kombinieren)
  history/    Undo/Redo über Kachel-Patches (speichersparend)
  editor/     Editor: alle Befehle mit Undo, die API für die Oberfläche
app/    Android-Oberfläche (Compose, Material 3)
  ai/              ONNX Runtime: Modellverwaltung, KI-Inpainting (MI-GAN/LaMa), KI-Upscaling (Real-ESRGAN)
  AndroidTextRasterizer  Text direkt in der Zieltransformation rendern
  EditorViewModel  ein Engine-Thread für Dokument und Rendern, gebündeltes Neuzeichnen geänderter Bereiche
  ui/CanvasArea    Zoom/Pan mit zwei Fingern, Werkzeuge mit einem Finger/Stift (inkl. Druckstufen)
  ui/LayersPanel, ToolPanels, Dialogs, FilterSpecs
```

## PSD-Unterstützung

| Bereich | Lesen | Schreiben |
|---|---|---|
| PSD (v1) / PSB (v2, Großformat) | ✅ / ✅ | ✅ / – |
| Farbtiefe 1 / 8 / 16 / 32 Bit | ✅ (intern 8 Bit) | 8 Bit |
| RGB, Graustufen, Duplex, Bitmap, Indiziert | ✅ | RGB |
| CMYK, Lab | ✅ (über das eingebettete ICC-Profil, sonst Näherung) | – |
| Kompression Raw, RLE, ZIP, ZIP+Prädiktion | ✅ | RLE |
| Ebenen, Sichtbarkeit, Deckkraft, Fläche, Unicode-Namen | ✅ | ✅ |
| Alle Mischmodi inkl. Hindurchwirken | ✅ (werden gerendert) | ✅ |
| Gruppen (auf-/zugeklappt, verschachtelt) | ✅ | ✅ |
| Pixelmasken (auch „real user mask“ bei Vektor+Pixel) | ✅ | ✅ |
| Schnittmasken, Transparenz fixieren | ✅ | ✅ |
| Ebenen-IDs, aktive Ebene | ✅ | ✅ |
| 16-Bit-Ebenendaten in `Lr16`/`Lr32` | ✅ | – |
| Einstellungsebenen (Helligkeit/Kontrast, Tonwert, Kurven, Belichtung, Dynamik, Farbton/Sättigung, Farbbalance, Schwarzweiß, Fotofilter, Umkehren, Tontrennung, Schwellenwert) | ✅ editierbar, gerendert | ✅ im Photoshop-Format |
| Textebenen | ✅ Text, Größe, Farbe, Ausrichtung, Lage (Originalpixel bis zur Änderung) | unverändert: Photoshop-Text bleibt editierbar; geändert/neu: als Pixel + eigene Textdaten |
| Smartobjekte (eigene) | ✅ Original in voller Auflösung | ✅ |
| Effekte, Photoshop-Smartobjekte, Füllebenen | erkannt, als Pixel angezeigt | Rohdaten werden **unverändert zurückgeschrieben** |
| ICC-Profil | ✅ als Dokumentprofil | ✅ |
| Color-Lookup-Ebenen (LUT) | ✅ | ✅ |
| XMP, EXIF, IPTC, Hilfslinien | übernommen | ✅ |
| Zusammengeführtes Bild („Kompatibilität maximieren“) | Fallback ohne Ebenen | ✅ |

Beim Import zeigt die App an, was umgewandelt oder nicht dargestellt wird.

**Eigene PSD-Sammlung prüfen:** `./gradlew :core:psdCheck -PpsdDir=<ordner>` vergleicht jede PSD/PSB/TIFF (XCF: nur Rundlauf)
(rekursiv) mit dem Bild, das Photoshop selbst in der Datei gespeichert hat („Kompatibilität
maximieren“), und prüft den Rundlauf Lesen → Speichern → Lesen. Die Dateien werden nur gelesen; der
Bericht (`report.html` mit Vergleichsbildern, `report.csv`) liegt in `core/build/psd-check`.

**Geprüft mit echten Dateien** (Skripte zum Erzeugen in `tools/`):
- Von GIMP 3.2 erzeugte PSD (Gruppe, Maske, Multiplizieren, Negativ multiplizieren, 70 % Deckkraft,
  ausgeblendete Ebene) wird vollständig gelesen; die Mischung entspricht exakt der Photoshop-Formel
  (GIMP selbst mischt linear und zeigt daher leicht andere Farben).
- 16-Bit-, Graustufen-, CMYK- und unkomprimierte PSDs von ImageMagick: Abweichung ≤ 0,2 % (Rundung).
- Von photographics gespeicherte PSDs öffnen GIMP und ImageMagick mit allen Ebenen, Gruppen,
  Masken, Mischmodi und Sichtbarkeiten; das zusammengeführte Bild ist pixelidentisch.

## Oberfläche (Material Design 3)

- **Material You:** Ab Android 12 übernimmt die App die Farben des Hintergrundbilds, sonst ein
  Indigo-Schema. Hell- und Dunkelmodus folgen der Systemeinstellung. Die Fläche um das Bild bleibt
  neutral grau, damit Farben beim Retuschieren nicht verfälscht wirken.
- **Navigation Rail** links mit 7 Werkzeuggruppen (Bewegen, Auswahl, Zuschnitt, Malen, Retusche,
  Schärfe, Licht); oben ein FAB mit der aktuellen Farbe.
- **Schwebende Optionsleiste** über dem Bild: Segmented Buttons für die Werkzeuge der Gruppe,
  dazu Regler, Chips und Aktionen des aktiven Werkzeugs.
- **Top App Bar:** Dokumentname, Rückgängig/Wiederholen, Stift-Modus, Korrekturen & Filter
  (Bottom Sheet), Ebenen, Überlaufmenü für Bild- und Auswahlbefehle.
- **Navigation Drawer** (☰) für Neu, Öffnen, Platzieren, Speichern und Export.
- **Ebenen als Side Sheet:** angedockt ab 840 dp Breite, darunter modal von rechts.
- **Filter** erscheinen als schwebende Karte mit Live-Vorschau; auf breiten Bildschirmen rechts
  unten, damit das Bild sichtbar bleibt.
- Adaptives App-Icon mit Unterstützung für Themed Icons (Android 13+).

## Bedienung auf dem Tablet

- **Stift-Modus** (schaltet sich beim ersten Stiftkontakt automatisch ein, Symbol oben rechts):
  Nur der Stift malt, Finger zoomen und verschieben. Berührungen der Hand während eines
  Stiftstrichs werden ignoriert (Handballen-Schutz).
- **Stift:** Druckstärke steuert die Pinselgröße, Radier-Ende = Radiergummi, Seitentaste = Pipette.
  Schwebt der Stift über dem Display, wird der Pinselumriss vorab angezeigt.
- **Gesten:** 2 Finger = Zoom/Verschieben, kurz mit 2 Fingern tippen = Rückgängig, mit 3 Fingern = Wiederholen.
- **Schnellregler** am linken Bildrand für Pinselgröße und Deckkraft (mit dem Daumen der freien Hand).
- **Vollbild** (Überlaufmenü oder Taste F) blendet Systemleisten und App Bar aus.
- **Tastatur & Maus** (Tastatur-Cover, Chromebook, Samsung DeX): Photoshop-ähnliche Kürzel
  (B, E, S, J, V, M, C, I, [ ], 1–0, Strg+Z/S/O/J/E/L/U, Leertaste zum Verschieben), Mausrad zoomt.
  Übersicht im Navigation Drawer unter *Gesten & Tastaturkürzel*.

## Werkzeuge und Funktionen

- **Retusche:** Bereichsreparatur (PatchMatch – füllt aus passenden Bildstücken der Umgebung),
  **KI-Entfernen** (lokales Modell), Reparatur-Pinsel und Ausbessern (Poisson-Blending: Struktur der
  Quelle, Farbe/Helligkeit des Ziels), Kopierstempel, Rote Augen, Wischfinger, Weich-/Scharfzeichner,
  Abwedler/Nachbelichter, Schwamm, Filter „Haut glätten“. „Alle Ebenen aufnehmen“ erlaubt
  zerstörungsfreies Retuschieren auf einer leeren Ebene. *Bearbeiten → Inhaltsbasiert füllen*
  (⇧F5) und *KI-Füllen* wirken auf die Auswahl.
- **Malen:** Pinsel und Radiergummi mit Größe, Härte, Deckkraft, Fluss und **Glättung**;
  Stiftdruck; Malen in Ebenenmasken. Auch sehr große Pinsel (500 px+) bleiben flüssig.
- **Pinseleinstellungen (F5, Symbol in der Optionsleiste):** Spitze mit Winkel und Rundheit;
  Formeigenschaften (Größen-, Winkel-, Rundheit-Jitter, Steuerung über Stiftdruck oder Verblassen,
  Winkel nach Strichrichtung); Streuung (eine oder beide Achsen, Anzahl); Übertragung (Deckkraft und
  Fluss über Stiftdruck, Verblassen oder Jitter – die Deckkraft begrenzt je Abdruck wie in Photoshop);
  Farbeinstellungen je Spitze (Vorder-/Hintergrund, Farbton, Sättigung, Helligkeit); Malmodus (alle
  Mischmodi, „Dahinter auftragen“, „Löschen“); Airbrush. 12 eingebaute Vorgaben (Hart/Weich rund,
  Airbrush, Bleistift, Tusche, Kalligrafie, Marker, Sprenkel, Kreide, Laub, Retusche weich,
  Verblassend) und eigene Vorgaben; Live-Vorschau des Strichs.
- **Vorder- und Hintergrundfarbe** wie in Photoshop: Doppelfeld in der Werkzeugleiste, X tauscht,
  D setzt Schwarz/Weiß, Alt+Entf / Strg+Entf füllt, Pipette mit Alt (oder Schalter) nimmt die
  Hintergrundfarbe auf, Radieren auf Ebenen mit fixierter Transparenz malt mit der Hintergrundfarbe,
  beim Erweitern der Arbeitsfläche wählbare Farbe für die unterste Ebene.
- **Farbfelder** (Palettensymbol unter den Farbfeldern): zuletzt verwendete Farben, eingebaute
  Bibliotheken (Standard, Graustufen, Hauttöne, Material) und eigene; Import von Photoshop-Farbfeldern
  (.aco), Farbtabellen (.act), Adobe Swatch Exchange (.ase, auch aus Illustrator/InDesign) und
  GIMP-Paletten (.gpl), Export als .aco, .ase oder .gpl.
- **Ebenen:** Pixel-, Text-, Smartobjekt-, Einstellungsebenen und Gruppen; Mischmodus,
  Deckkraft/Fläche, Masken, Rastern, In Smartobjekt konvertieren.
- **Ebenenmasken verbinden/lösen** (Kettensymbol zwischen Ebene und Maske): verbundene Masken
  wandern beim Verschieben und Frei-Transformieren mit; bei gelösten verschiebt das
  Verschieben-Werkzeug je nach Auswahl (Ebene/Maske) nur das eine oder das andere.
- **Schnittmasken** (Strg+Alt+G oder Ebenenmenü): Basis unterstrichen, beschnittene Ebenen
  eingerückt; Zurückwandeln löst auch die darüberliegenden; neue Ebenen innerhalb einer Schnittmaske
  gehören dazu; Einstellungsebenen mit „Nur auf Ebene darunter“.
- **Frei transformieren** (Strg+T): Verschieben, proportional/frei skalieren, drehen (⇧: 15°-Schritte).
  Pixelebenen werden dabei zum Smartobjekt – mehrfaches Transformieren ohne Qualitätsverlust.
- **Text:** Systemschriften, Größe, Farbe, Fett/Kursiv, Ausrichtung, Zeilenabstand, Laufweite.
- **Korrekturen:** 12 Typen als Einstellungsebene (nicht-destruktiv) oder direkt angewendet;
  Gradationskurven mit Kurveneditor.
- **Filter:** Gaußscher Weichzeichner, Bewegungsunschärfe, Radialer Weichzeichner, Matter machen,
  Tilt-Shift, Unscharf maskieren, Selektiver Scharfzeichner, Hochpass, Rauschen reduzieren, Median,
  Staub & Kratzer, Rauschen hinzufügen, Haut glätten, Klarheit, Dunst entfernen, Vignette,
  Objektivkorrektur, Ölfarbe, Relief, Konturen finden, Mosaik, Strudel – alle mit Live-Vorschau.
- **Bild:** Bildgröße (Pixel/Prozent/dpi; Pixelwiederholung, Bilinear, Bikubisch, Lanczos,
  **KI-Hochskalierung**), Arbeitsfläche mit Anker, Freistellen, Drehen (auch um beliebigen Winkel),
  Spiegeln, **Drucken**.
- **Freistellen:** Seitenverhältnis-Vorgaben (Original, 1:1, 5:4, 3:2, 4:3, 7:5, 16:9, DIN A) mit
  Hoch-/Querformat, Zielgröße in Pixel oder cm mit Auflösung, Überlagerungen (Drittelregel, Goldener
  Schnitt, Raster, Diagonalen), Drehen per Regler oder **Gerade ausrichten** (Linie am Horizont ziehen,
  der Ausschnitt ohne leere Ecken wird automatisch gesetzt), „Pixel löschen“ aus/an, Rahmen verschieben.
  **Perspektivisch freistellen:** vier Ecken auf ein schräg aufgenommenes Rechteck setzen
  (Gebäude, Dokument, Bild an der Wand) – heraus kommt ein gerades Bild.
- **Frei transformieren** mit den Modi Frei, **Neigen, Verzerren und Perspektive**; Smartobjekte bleiben
  dabei verlustfrei (die Ecken werden gespeichert und erst beim Rendern angewendet, auch in PSD).
- **Hilfslinien und Lineale:** Hilfslinien aus den Linealen ziehen, mit dem Verschieben-Werkzeug versetzen
  oder aus dem Bild ziehen zum Löschen; „Neue Hilfslinie …“ und „Hilfslinienlayout …“ (Spalten, Zeilen,
  Rand, Abstand); **Ausrichten** an Hilfslinien, Arbeitsflächenrand und -mitte und an Kanten/Mitten
  anderer Ebenen, mit **intelligenten Hilfslinien** beim Verschieben. Hilfslinien bleiben in PSD (auch
  mit Photoshop und GIMP getauscht) und XCF erhalten und wandern bei Freistellen, Drehen usw. mit.
- **Film-Looks** (in der Einstellungsebene „Farbtabelle“, Gruppe „Film“): Porträt 400, Farbnegativ
  100 brillant / 400 kühl / 200 warm, Kunstlicht 800, Diafilm 50 brillant / 100 neutral / 64 klassisch,
  Schwarzweiß 400 klassisch / weich, Infrarot, Selen getont, Kinokopie, Sofortbild, Abgelaufener Film –
  eigene Filmmodelle (Schichtgradation, Kennlinie, Farbübersprechen, Tonung), keine fremden LUTs.
- **Lokale KI (optional, auf Wunsch geladen, läuft komplett auf dem Gerät):**
  MI-GAN (28 MB, MIT) und LaMa (208 MB, Apache 2.0) zum Entfernen von Objekten,
  Real-ESRGAN General (4,5 MB) und x4plus (62 MB, BSD-3) zum Hochskalieren.
- **Startbildschirm:** Vorlagen, Öffnen, Galerie, zuletzt verwendete Dateien mit Vorschau.
- **Tastatur & Maus:** Rechtsklick-Kontextmenü, werkzeugabhängiger Mauszeiger, Alt-Klick =
  Stempelquelle, Alt beim Pinsel = Pipette, Pfeiltasten verschieben (⇧ 10 px), Mausrad zoomt,
  Photoshop-Kürzel (Übersicht im Menü *Gesten & Tastaturkürzel*).
- **Dateien:** PSD/PSB/TIFF/XCF/PNG/JPEG/WebP/HEIF öffnen, als Ebene platzieren, PSD, TIFF und XCF speichern,
  PNG/JPEG exportieren. **Verlauf:** Undo/Redo für alle Aktionen.

## GIMP (XCF)

- **Lesen:** alle XCF-Versionen bis GIMP 3.2 (v023); Ebenen mit Versatz (auch über die Arbeitsfläche
  hinaus), Gruppen, Masken, Deckkraft, Sichtbarkeit, Transparenz fixieren, alle Mischmodi (auf die
  passenden Photoshop-Modi abgebildet), Hilfslinien, Auflösung, Farbprofil; RGB, Graustufen, indiziert;
  8/16/32 Bit Ganzzahl und 16/32/64 Bit Gleitkomma, linear oder wahrnehmungsgleich; unkomprimiert,
  RLE und zlib. Geprüft Ebene für Ebene gegen GIMP 3 (`tools/make_xcf_fixtures.py`): Abweichung 0.
- **Speichern** (Menü → Als GIMP-Datei speichern): XCF v011 (ab GIMP 2.10) mit zlib. Mischen und
  Zusammensetzen stehen auf „wahrnehmungsgleich“ – GIMP zeigt die Datei damit pixelgleich wie
  photographics (und Photoshop); GIMPs Standard (linear) weicht sichtbar ab. Text- und Smartobjekte
  werden als Pixel gespeichert; Einstellungsebenen und Schnittmasken gibt es in GIMP nicht (Hinweis).
- GIMPs „Körnung extrahieren/zusammenführen“ und ähnliche GIMP-eigene Modi haben keine Entsprechung
  und werden als „Normal“ übernommen (mit Hinweis).

## TIFF

- **Lesen:** Bytefolge Intel und Motorola, BigTIFF; Streifen und Kacheln; unkomprimiert, LZW,
  ZIP/Deflate und PackBits, jeweils mit Prädiktor (auch Gleitkomma); 1, 2, 4, 8, 16 Bit und 32 Bit
  Gleitkomma; Graustufen, RGB, indizierte Farben, CMYK und Lab; getrennte oder verschachtelte Kanäle;
  Transparenz (auch vormultipliziert); ICC-Profil, Auflösung, Ausrichtung. JPEG- und
  Fax-komprimierte TIFFs werden mit einer Meldung abgelehnt.
- **Photoshop-Ebenen** im TIFF (Photoshop: „Ebenen: … speichern“) werden mit dem PSD-Leser
  übernommen. Bei Bytefolge „IBM PC“ kommen Pixel, Masken, Gruppen, Namen und Mischmodi an;
  Effekt-, Text- und Einstellungsdaten nur bei Bytefolge „Macintosh“.
- **Speichern** (Menü → Als TIFF speichern …): LZW, ZIP oder unkomprimiert, wahlweise mit Ebenen im
  Photoshop-Format, Farbprofil und Auflösung; Transparenz nur, wenn das Bild welche hat. Geprüft mit
  libtiff/ImageMagick (liest Gesamtbild und alle Ebenen).
- Große Dateien werden streifenweise und speicherabgebildet verarbeitet (42-MP-TIFF mit Ebenen:
  Speichern 2,4 s, Öffnen 0,35 s, Java-Heap höchstens 32 MB).

## Speicher und Leistung

Android gibt jeder App nur einen begrenzten Java-Speicher (mit `largeHeap` meist 512–576 MB, auch
auf Tablets mit 8 GB RAM). photographics umgeht diese Grenze für die Bilddaten:

- **Nativer Kachelspeicher:** Alle Bildpixel liegen in 256×256-Kacheln in nativen
  SharedMemory-Blöcken (ashmem/memfd, je 64 MB) – außerhalb des Java-Heaps. Budget: 35 % des
  Geräte-RAM (8-GB-Tablet ≈ 2,8 GB, Geräte mit wenig RAM 25 %). Nicht mehr benutzte Kacheln werden
  automatisch zurückgegeben, leere Blöcke ans System. Ein kleiner Kachel-Cache (≈ 24 MB im Heap)
  hält gerade bearbeitete Kacheln als Array – Pinsel und Anzeige sind damit so schnell wie vorher.
  Der Java-Heap enthält nur noch Verwaltungsdaten und kurzlebige Streifen-Puffer.
- **Ebenen über die Arbeitsfläche hinaus:** Wie bei Photoshop bleiben Pixel erhalten, wenn eine
  Ebene ganz oder teilweise aus dem Bild geschoben wird. Arbeitsfläche ändern, Zusammenfügen,
  Drehen/Spiegeln, Bildgröße, Filter und PSD (Lesen und Schreiben) behalten diese Bereiche;
  nur Freistellen verwirft sie („Beschnittene Pixel löschen“).
- **Alle Prozessorkerne:** Bildgröße, Filter, Zusammenfügen/Reduzieren und das Neuzeichnen großer
  Bereiche rechnen Streifen parallel (POCO Pad M1: Bildgröße 12 MP → 140 % in 3,9 s statt 12,8 s).
  Wie viele Streifen gleichzeitig laufen, richtet sich nach einem Speicherbudget (⅕ des Heaps).
- **Systemwarnungen:** Geht die App in den Hintergrund oder wird der RAM knapp (`onTrimMemory`),
  lagert sie den Verlauf auf den Gerätespeicher aus und leert den Kachel-Cache.
- **Arbeitsvolume wie in Photoshop:** Ältere Verlaufsschritte werden in den App-Cache ausgelagert
  und erst beim Rückgängigmachen zurückgeladen. Pixeländerungen speichern nur die geänderten
  256-px-Kacheln und tauschen sie beim Rückgängig/Wiederholen aus (keine Doppelkopien). Wird der
  Speicher knapp, lagert die App vor großen Aktionen automatisch aus; bei einem Speicherfehler wird
  der Verlauf gekürzt und die Aktion einmal wiederholt.
- **Streifenweise Verarbeitung:** Filter, Bildgröße (alle Verfahren), Zusammenfügen/Reduzieren,
  PSD-Speichern, Export, Drucken und das Neuzeichnen der Anzeige arbeiten in Streifen – nie mit einem
  Puffer in Dokumentgröße. PSDs werden speicherabgebildet geöffnet (die Datei liegt nicht im Heap)
  und Ebene für Ebene dekodiert; beim Speichern landen die Kanaldaten zwischendurch im Cache.
- **KI-Hochskalierung** mit gleitendem Zeilenfenster statt eines Puffers in Zielgröße.
- **Live-Vorschau:** Deckkraft, Einstellungsebenen, Verschieben und Transformieren werden während
  des Ziehens in reduzierter Auflösung (≈ 1,5 MP, etwa 9× schneller) gezeigt; die volle Qualität
  folgt automatisch 0,3 s nach dem Loslassen.
  Die Ebenen unter der gerade geänderten werden dabei einmal zusammengerechnet und zwischengespeichert;
  je Bild kommen nur die Ebene selbst und die darüberliegenden hinzu (24 MP auf dem POCO Pad:
  Einstellungsebene ziehen 29 statt 51 ms, Transformieren 30 statt 63 ms je Bild).
- **Hineinzoomen:** Der scharfe Ausschnitt in voller Auflösung zeigt zuerst den sichtbaren Teil,
  danach den Rand für kleines Verschieben.
- **Zeichnen der Oberfläche:** Bild und Überlagerungen (Auswahl, Pinselumriss, Hilfslinien) liegen in
  eigenen Ebenen; ein Render-Schritt zeichnet nicht mehr die ganze Oberfläche neu auf (5,7 → 0,4 ms je
  Bild). Die laufenden Ameisen laufen nur bei sichtbarer Auswahl, das Schachbrett liegt nur unter
  nicht deckenden Bildteilen. Bildzeiten je Phase misst `tools/device_bench.sh`.
- **Mipmaps:** Jede Ebene (und Maske) hält verkleinerte Fassungen im Kachelspeicher, die nur dort
  neu berechnet werden, wo sich Kacheln geändert haben. Bei verkleinerter Ansicht wird direkt in
  Anzeigeauflösung komponiert statt in voller Auflösung (48 MP mit vier Ebenen: 0,3 s statt 3,1 s).
- **Transformieren:** Die Vorschau tastet das Smartobjekt direkt aus der Quelle ab, statt es in
  Dokumentgröße neu zu berechnen (je Bewegung 0,15 s statt 0,7 s bei 48 MP); das endgültige
  bikubische Rendern läuft auf allen Kernen.
- Messung: `PG_BENCH=1 ./gradlew :core:memoryTest --tests "*CompositingBenchmark*"`.
### Stabilität absichern (Release-Sperre)

`./build.sh` baut eine Release-Version **nur, wenn alle Tests bestehen**:

1. `:core:test` – Funktionstests (PSD, Mischmodi, Retusche, Pinsel, Farbproof, Verlauf, …) mit
   512 MB Heap.
2. `:core:memoryTest` – **Speicher-Sperre**: eine harte Sitzung mit 20-MP-Dokument und drei Ebenen
   (alle 24 Filter, Pinsel, Masken, Zusammenfügen, Drehen, Bildgröße 140 %, Arbeitsfläche,
   Freistellen, PSD speichern/öffnen, alles rückgängig/wiederholen) mit nur **192 MB Java-Heap**,
   während die Bilddaten auf rund 2 GB anwachsen. Lädt eine Funktion ein ganzes Bild in den
   Java-Speicher, scheitert der Test sofort; zusätzlich darf der Heap 120 MB nicht überschreiten.
   Die Sitzung steht in `core/.../diagnostics/MemorySelfTest.kt` – **neue Funktionen gehören dort
   hinein.**
3. `./build.sh smoke` – dieselbe Sitzung (12 MP) **auf dem verbundenen Gerät** in der installierten
   App; Ergebnis mit Speicherwerten im Terminal. Für das eigene Tablet nach jedem Update empfohlen.

`./build.sh release --ohne-tests` überspringt die Sperre (nur für Notfälle).

## Farbtabellen (LUT)

- **Einstellungsebene „Farbtabelle (LUT)“** (Symbol Verlauf im Einstellungs-Panel): 10 eingebaute
  Looks (Film warm, Kühl, Teal & Orange, Bleach Bypass, Matt, Kontrastreich, Sepia, Schwarzweiß
  Rotfilter, Cross-Process, Nacht) oder eigene Dateien im Format `.cube` (3D, auch 1D/DOMAIN) und
  `.3dl`. Geladene LUTs liegen in der App-Bibliothek und stehen in jedem Dokument zur Verfügung.
- Auswertung tetraedrisch (wie Photoshop/Resolve), mit Deckkraft und Maske wie jede Einstellungsebene.
- **PSD:** wird als Photoshop-Einstellungsebene *Color Lookup* (`clrL`) gespeichert, samt LUT-Daten –
  Photoshop zeigt sie identisch an; Color-Lookup-Ebenen aus Photoshop werden gelesen.
- **Menü → Farbtabelle exportieren (.cube):** alle sichtbaren Einstellungsebenen (Kurven, Farbton,
  Gradationen, LUTs …) als 33er-Cube für Lightroom, Resolve, Premiere oder Kameras.

## Farbmanagement (Menü → Bild → Farbeinstellungen)

- **ICC-Engine** im Kern (ohne native Bibliothek): Profile v2/v4 mit Matrix/TRC, Grau-TRC und
  LUT-Tabellen (mft1/mft2/mAB/mBA), RGB/CMYK/Grau/Lab, alle vier Prioritäten (perzeptiv, relativ
  und absolut farbmetrisch, Sättigung) und Tiefenkompensierung. Geprüft gegen LittleCMS
  (RGB höchstens 1 Stufe, CMYK höchstens 4 Stufen Abweichung).
- **Eingebaute Arbeitsfarbräume:** sRGB, Display P3, Adobe RGB (1998), ProPhoto RGB – aus den
  Normdaten erzeugt und frei einbettbar.
- **Dokumentprofil:** jedes Dokument hat ein Profil (Anzeige im Untertitel). PSDs behalten ihr
  eingebettetes Profil; JPEG/PNG/HEIF übernehmen ihren Farbraum (z. B. Display P3 vom Tablet).
  CMYK-, Graustufen- und Lab-PSDs werden über ihr eingebettetes Profil in den Arbeitsfarbraum umgerechnet.
- **Richtlinie beim Öffnen:** eingebettetes Profil beibehalten oder in den Arbeitsfarbraum umwandeln.
  Platzierte Bilder werden in das Dokumentprofil umgerechnet.
- **Bild → Profil zuweisen …** (Pixelwerte bleiben) und **In Profil umwandeln …** (Farben bleiben;
  Priorität und Tiefenkompensierung wählbar, mehrkernig, rückgängig machbar).
- **Anzeige:** Dokumente in anderen Profilen werden farbrichtig auf dem Bildschirm (sRGB) dargestellt.
- **Export:** PNG/JPEG mit eingebettetem Dokumentprofil oder in sRGB umgewandelt (Einstellung);
  Drucken erfolgt immer in sRGB.
- **ICC-Profilbibliothek:** eigene Profile (Druckerei, Fotodrucker, Bildschirm) hinzufügen – für
  Proof, Zuweisen und Umwandeln.

## Farbproof (Ansicht → Proof einrichten)

Strg+Y blendet den Farbproof ein, Strg+⇧+Y die Farbumfang-Warnung. Mit einem **eigenen ICC-Profil**
(z. B. FOGRA/PSO der Druckerei) wird exakt über das Profil simuliert: Dokument → Ausgabegerät
(Priorität und Tiefenkompensierung wählbar) → Bildschirm, mit „Papierfarbe simulieren“ absolut
farbmetrisch. Die Farbumfang-Warnung markiert dann Farben, deren Rundlauf über das Profil um mehr als
ΔE 5 abweicht. Ohne eigenes Profil simuliert werden Offsetdruck
gestrichen (FOGRA39), ungestrichen (PSO/FOGRA52), Zeitungsdruck (IFRA26), Graustufen sowie
Protanopie/Deuteranopie. Die Druckbedingungen werden ohne ICC-Profile aus den Normfarbwerten
(ISO 12647) mit einem Yule-Nielsen-Neugebauer-Modell berechnet, relativ farbmetrisch mit
Tiefenkompensierung; optional mit Papierweiß und Schwärze („Papierfarbe simulieren“). Das ist eine
gute Näherung für die Bildschirmkontrolle, ersetzt aber keinen verbindlichen Proof mit dem Profil der
Druckerei. Der Proof betrifft nur die Anzeige – Export und gespeicherte Dateien bleiben unverändert.

## Einstellungen (Menü → Einstellungen)

- **Darstellung:** Hell/Dunkel/System, Farben aus dem Hintergrundbild (Material You) oder aus einer
  eigenen Akzentfarbe (12 Vorgaben oder frei wählbar – das ganze Material-3-Farbschema wird daraus
  abgeleitet), Tiefschwarz für OLED, Hintergrund der Arbeitsfläche (Standard, Schwarz, Grautöne, eigene Farbe).
- **Farbmanagement:** RGB-Arbeitsfarbraum, Richtlinie beim Öffnen, Priorität und
  Tiefenkompensierung für Umwandlungen, Exportprofil, ICC-Profilbibliothek.
- **Arbeitsbereich:** Werkzeugleiste links/rechts, mit/ohne Beschriftung; Ebenen-Panel links/rechts
  mit einstellbarer Breite; Werkzeugoptionen oben/unten; Schnellregler für Größe/Deckkraft
  links/rechts/aus; Vorlagen *Standard*, *Linkshänder* und *Minimal*; Live-Skizze des Layouts.
- Alle Einstellungen werden dauerhaft gespeichert.

## Bekannte Grenzen

- Intern 8 Bit pro Kanal; Dokumente sind immer RGB (CMYK/Lab werden beim Öffnen umgerechnet, kein
  CMYK-Export). Gesättigte Farben verlieren beim Umwandeln zwischen Farbräumen durch 8 Bit einige Stufen.
- Die Bildschirmanzeige geht von einem sRGB-Display aus (kein Monitorprofil).
- Smartobjekte werden beim Umwandeln in ein anderes Profil nicht umgerechnet (Hinweis in der Meldung).
- Ebeneneffekte, Photoshop-Smartobjekte und Füllebenen werden nicht live gerendert (bleiben beim
  Speichern erhalten). Vektormasken werden nicht dargestellt.
- Neue oder geänderte Texte erscheinen in Photoshop als Pixelebene (in photographics bleiben sie
  editierbar). Photoshop-Schriften werden auf Android-Systemschriften abgebildet.
- Große Retuschen dauern auf dem Gerät einige Sekunden (Bereichsreparatur eines 1500-px-Strichs im
  Emulator ≈ 3,7 s, KI ≈ 4 s; KI-Hochskalierung Full HD → 150 % ≈ 1 min).
- Mit echter Photoshop-Software wurde noch nicht getestet (nur GIMP/ImageMagick).

## Roadmap

Ohne feste Termine, die Reihenfolge kann sich ändern. Vorschläge sind willkommen.

- **Als Nächstes:** Verschieben und Transformieren über die GPU (OpenGL ES/Vulkan), damit große Dateien
  flüssiger laufen; Ebeneneffekte (Schlagschatten, Kontur, Schein) anzeigen; Oberfläche auf Englisch
- **Danach:** Verflüssigen; Verlaufswerkzeug, Zauberstab und Objektauswahl; Kurven je Farbkanal mit
  Histogramm; eigene Schriften (TTF/OTF); automatisches Sichern und Wiederherstellen nach Abstürzen
- **Später:** Bearbeitung mit 16 Bit pro Kanal; CMYK-Dokumente und -Export; Anzeige über das Farbprofil
  des Bildschirms; Versionen für Linux (GNOME) und iPad

## Über mich

Ich heiße Andreas Bartelmess und fotografiere beruflich, vor allem für Firmen und für die Gastronomie.
Ein großer Teil meiner Arbeit passiert nach dem Shooting in der Retusche. Angefangen habe ich mit einer
Ausbildung im Grafikdesign an der Glasfachschule Rheinbach, heute studiere ich Fotografie an der
FH Dortmund. photographics entwickle ich neben meinen Aufträgen. Meine Fotografie:
[andreasbartelmess.de](https://andreasbartelmess.de).

## Lizenz

photographics ist freie Software unter der [GNU General Public License v3.0 oder neuer](LICENSE).
Verwendete Bibliotheken, KI-Modelle und Testdaten: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Adobe und Photoshop sind eingetragene Marken von Adobe Inc.; photographics ist ein unabhängiges
Projekt ohne Verbindung zu Adobe.
