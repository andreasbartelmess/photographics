# Entwurf: Veröffentlichung auf GitHub

Alles, was auf GitHub außerhalb der Dateien im Repository eingetragen wird. Die README (`README.md`) ist die
Startseite des Repositorys und bereits fertig.

## Repository

- **Name:** `photographics`
- **Beschreibung** (Feld *About*, max. 350 Zeichen):
  > A layer-based photo editor for Android tablets. Layers, masks and retouching with the pen; opens and saves PSD, TIFF and GIMP files with all their layers. Early preview.
- **Website:** Adresse der GitHub-Pages-Seite (siehe unten) oder andreasbartelmess.de
- **Topics:** `android` `photo-editor` `image-editor` `psd` `photoshop` `gimp` `xcf` `tiff` `retouching`
  `jetpack-compose` `kotlin` `color-management` `icc-profiles` `tablet` `stylus`
- **Social Preview** (*Settings → General → Social preview*): `docs/social-preview.png` (1280 × 640)
- **Häkchen:** Issues an, Discussions an (für Fragen und Feedback), Wiki aus, Projects aus

## Vor dem ersten Push

1. Git-Identität setzen (`git config user.name` / `user.email`) und den ersten Commit machen.
2. In `gradle.properties` die Adresse eintragen: `photographics.sourceUrl=https://github.com/<konto>/photographics`
   (erscheint in der App unter *Einstellungen → Über*).
3. In `website/index.html` oben im Skript `const REPO = "https://github.com/<konto>/photographics";` setzen.
4. Prüfen, dass `keystore.properties` und `*.jks` nicht im Commit sind (stehen in `.gitignore`).

## Signierte Releases

Der Workflow `.github/workflows/release.yml` baut bei jedem Tag `v*` die APKs, signiert sie und hängt sie
an ein GitHub-Release. Dafür unter *Settings → Secrets and variables → Actions* anlegen:

| Secret | Inhalt |
|---|---|
| `PG_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `PG_KEYSTORE_PASSWORD` | Passwort des Keystores |
| `PG_KEY_ALIAS` | Alias des Schlüssels |
| `PG_KEY_PASSWORD` | Passwort des Schlüssels |

Danach: `git tag v0.2.0 && git push origin v0.2.0`.

## Website (GitHub Pages)

*Settings → Pages → Source: Deploy from a branch → `main`, Ordner `/website`*. GitHub erlaubt als
Ordner nur `/` oder `/docs`; für `/website` entweder den Ordner in `docs/` verschieben oder eine kleine
Pages-Action ergänzen (kann ich machen, sobald das Repository existiert).

## Release-Text v0.2.0

**Titel:** photographics 0.2 – preview

> First public preview of photographics, a layer-based photo editor for Android tablets.
>
> **What works**
> - PSD/PSB, TIFF (with Photoshop layers) and GIMP XCF: open and save with layers, groups, masks and blend modes
> - Pixel, text, smart object and adjustment layers; clipping masks; all 27 Photoshop blend modes
> - Retouching with the pen: spot healing, healing brush, patch, clone stamp, dodge/burn, brush engine with presets
> - Colour management with ICC profiles, soft proof, LUTs and film looks
> - Optional on-device AI for object removal and upscaling (models are downloaded on first use)
>
> **Good to know**
> - The interface is German only for now.
> - Tested on a POCO Pad and with files from GIMP and ImageMagick, not yet with files from current Photoshop versions.
> - This is a preview. Keep backups of important files.
>
> **Which APK?** `app-arm64-v8a-release.apk` for almost all current tablets and phones,
> `app-universal-release.apk` if you are unsure.
>
> Feedback, bug reports and help are very welcome, see the README.

## Unterstützung (optional)

Wenn du Spenden annehmen willst, kann eine Datei `.github/FUNDING.yml` einen *Sponsor*-Knopf anzeigen,
z. B. mit `github: <konto>`, `ko_fi: <name>` oder `custom: ["https://paypal.me/<name>"]`. Dafür brauche ich
die Konten, die du nutzen möchtest.
