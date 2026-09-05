# dill

An App to handle Doctor Bills. Bills are photographed and the picture optimized. 

## Goals

This project is in alpha, the goals are

* manage PDFs oder Images of doctor bills
* store some meta infos (billing date, cost, uploaded to insurer and/or beihilfe)
* local storage only for now

## What it does

* Photograph doctor bills with the built-in document scanner — the picture is cropped,
  deskewed and enhanced by an on-device ML model, nothing leaves the phone
* Existing PDFs (e.g. already lying in the hand-over folder) can be imported as bills too;
  their pages are rendered and OCRed just like scans
* Stores every bill as a PDF, local only, in the app's private storage
* Reads the scan with on-device OCR and suggests billing date, doctor and cost;
  the suggestions can be reviewed and corrected per bill
* New bills are marked *unverarbeitet*; one action copies all unprocessed PDFs into a
  hand-over folder on the phone, where the HUK and Beihilfe apps can pick them up for upload
* Bills can also be shared straight into the HUK and Beihilfe apps via the Android share
  sheet — both register as share targets for PDFs and jump right into their submission flow
* Per bill, checkboxes track whether it has been submitted to HUK and/or Beihilfe

## How it works

1. **Scannen** — der Scanner-Button startet den ML-Kit-Dokumentenscanner. Der erkennt die
   Blattränder, schneidet zu, entzerrt und bereitet das Bild auf. Das läuft komplett
   lokal auf dem Gerät (via Google Play Services), nichts geht in die Cloud.
2. **PDF** — der Scanner liefert direkt ein PDF, das nur im privaten App-Speicher landet
   (`files/bills/`). Kein Cloud-Backup, kein Netzwerkzugriff.
3. **Auslesen** — die On-Device-Texterkennung von ML Kit (gebündeltes Modell, offline)
   liest die Seiten. Aus dem Text werden per Heuristik **Rechnungsdatum**, **Arzt/Praxis**
   und **Rechnungsbetrag** vorgeschlagen — in der Detailansicht prüfen und ggf. korrigieren.
4. **Unverarbeitet** — jede neue Rechnung startet als *unverarbeitet*. Neben dem Scannen
   können über „PDFs importieren…“ auch vorhandene PDFs übernommen werden (die Seiten
   werden dafür gerastert und genauso ausgelesen); die Originaldatei bleibt unangetastet.
   Der Verarbeitet-Status lässt sich in der Detailansicht auch von Hand setzen — nützlich
   für importierte PDFs, die schon eingereicht sind.
5. **Einreichen** — „Einreichen…“ in der Detailansicht (bzw. „Unverarbeitete teilen…“ im
   Menü) öffnet das Android-Share-Sheet. Die Apps der HUK und der bayerischen Beihilfe
   nehmen PDFs als Share-Ziel an und springen direkt in ihren Einreichungs-Dialog.
   Ob eingereicht wurde, wird pro Rechnung per Häkchen gepflegt.
6. **Export** — alternativ kopiert „Unverarbeitete exportieren“ alle unverarbeiteten PDFs
   in einen frei wählbaren Übergabeordner (z. B. `Downloads/Arztrechnungen`). Von dort
   können die Apps der HUK und der Beihilfe die Dateien über ihren Dateiwähler einlesen.
   Die Rechnungen gelten danach als *exportiert*.

Die exportierten Dateien bekommen sprechende, sortierbare Namen:
`Arztrechnung_2026-07-15_Dr-med-Musterfrau_25-64-EUR.pdf`

## Technik

* Java, ein Modul, keine Architektur-Frameworks — wie in den Schwesterprojekten
* minSdk 26, targetSdk 34, AGP 8.5.2
* [ML Kit Document Scanner](https://developers.google.com/ml-kit/vision/doc-scanner) —
  Scan-UI, Zuschnitt und Bildaufbereitung, braucht keine Kamera-Berechtigung
* [ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2)
  mit gebündeltem Modell — OCR offline
* Metadaten in einer schlichten `bills.json` im App-Speicher, keine Datenbank
* Export über das Storage Access Framework — die App braucht keinerlei Berechtigungen

## Download

Signierte APKs hängen an den [Releases](https://gitlab.noisebleed.de/pub/dill/-/releases);
der Direktlink zur jeweiligen Version steht dort als Asset (Paket-Registry-URL, funktioniert
bei öffentlichem Projekt ohne Anmeldung).

Zum Installieren die Datei auf dem Gerät öffnen. Android fragt einmalig nach der Erlaubnis,
Apps aus dieser Quelle zu installieren; Play Protect zeigt bei selbst signierten Apps eine
Warnung, die man bestätigen kann. Voraussetzung ist Android 8 oder neuer.

## Bauen und installieren

Gebraucht wird ein **JDK 17**. Das in Android Studio mitgelieferte JBR ist inzwischen ein JDK 25,
das Gradle 8.7 mit `Unsupported class file major version 69` ablehnt — der frühere Pfad
`/opt/JetBrains/android-studio/jbr` funktioniert deshalb nicht mehr. Ein JDK 17 gibt es als Temurin
von [adoptium.net](https://adoptium.net), hier liegt es unter `~/.jdks/jdk-17.0.20.1+1`:

```bash
export JAVA_HOME=~/.jdks/jdk-17.0.20.1+1
./gradlew assembleDebug        # braucht außerdem das Android SDK (local.properties)
./gradlew testDebugUnitTest    # Tests für die Extraktions-Heuristiken
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release

Der Signaturschlüssel liegt **nicht im Repo** (das ist öffentlich), sondern in
`~/Documents/Android/own/dill/` — per Syncthing zwischen den eigenen Rechnern gespiegelt und
im Backup. `keystore.properties` daneben nennt Alias und Passwörter, `storeFile` wird relativ
zu dieser Datei aufgelöst. Ein abweichender Pfad geht über `-Pdill.signing=…`; fehlt der
Schlüssel ganz, bleibt das Release-APK unsigniert statt den Build zu brechen.

Einmalig erzeugen (Verlust bedeutet, dass alle Nutzer erst deinstallieren müssten):

```bash
mkdir -p ~/Documents/Android/own/dill && cd ~/Documents/Android/own/dill
keytool -genkeypair -v -keystore dill-release.jks -alias dill \
  -keyalg RSA -keysize 4096 -validity 10000
printf 'storeFile=dill-release.jks\nstorePassword=…\nkeyAlias=dill\nkeyPassword=…\n' \
  > keystore.properties
```

## Release über CI

`.gitlab-ci.yml` erledigt das alles selbst, sobald ein Tag der Form `v<Ziffer>` gepusht wird:
Tests, bauen, signieren, in die Paket-Registry laden, Release mit Asset-Link anlegen. Für
Branches läuft die Pipeline bewusst gar nicht. Der übliche Ablauf:

```bash
# versionCode und versionName in app/build.gradle anheben, committen
git tag -a v0.2 -m "Dill 0.2" && git push origin main && git push origin v0.2
```

Zwei Prüfungen brechen den Job absichtlich ab, statt ein kaputtes Release zu veröffentlichen:
das Umbenennen der APK schlägt fehl, wenn mangels Schlüssel nur `app-release-unsigned.apk`
entstanden ist, und `versionName` im APK muss zum Tag passen.

### Einmalige Einrichtung

Wie bei Focus (dort ausführlich dokumentiert): ein Runner mit Docker-Executor und Tag
`docker`, dazu geschützte CI-Variablen unter Settings → CI/CD → Variables — alle vier auf
*protected*, das Tag-Muster `v*` muss geschützt sein:

| Variable | Typ | maskiert | Inhalt |
|---|---|---|---|
| `ANDROID_KEYSTORE_B64` | File | nein | `base64 -w0 dill-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Variable | ja | `storePassword` |
| `ANDROID_KEY_ALIAS` | Variable | nein | `dill` |
| `ANDROID_KEY_PASSWORD` | Variable | ja | `keyPassword` |
