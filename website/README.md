# Website

Statische Projektseite ohne Build-Schritt: `index.html` (Deutsch), `en/index.html` (Englisch),
`impressum.html`, Bilder in `img/`, Schrift lokal in `fonts/` (keine Anfragen an Google).

Veröffentlicht wird automatisch per GitHub Pages (`.github/workflows/pages.yml`), sobald sich etwas in
`website/` ändert. Adresse: https://photographics.andreasbartelmess.de (DNS bei Strato: CNAME
`photographics` → `<konto>.github.io`).

- Die Repository-Adresse steht oben im Skript von `index.html` (`const REPO = ""`). Solange sie leer ist,
  sind „APK herunterladen“ und „Quellcode“ ausgegraut.
- Fotos von Unsplash (Unsplash-Lizenz), Urheber im Fuß der Seite.
- Die Screenshots `editor-*.jpg` stammen aus der App mit Beispieldokumenten (siehe `tools/screenshots.sh`).
