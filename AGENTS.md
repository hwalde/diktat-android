# Installationsanleitung für Coding-Agenten

Diese Datei richtet sich an einen **Coding-Agenten** (z. B. Claude Code), der einen Menschen durch die Installation
von Diktat auf seinem Android-Handy führt. Der Mensch braucht dafür nur sein Handy, ein USB-Kabel und einen
Computer, auf dem du läufst.

## Regeln für dich als Agent

- Erkläre jeden Schritt am Handy in einfachen Worten. Der Mensch ist nicht unbedingt Entwickler.
- Frag nach, bevor du etwas tust, das sich nicht rückgängig machen lässt, z. B. eine App deinstallieren.
- Fass am Handy nichts an, was nicht zu Diktat gehört: keine anderen Apps, Daten, Einstellungen.
- **Bedienungshilfe:** Wenn du `enabled_accessibility_services` per adb setzt, hänge Diktat an die bestehende
  Liste an. Überschreibe sie nie, sonst schaltest du andere Bedienungshilfen ab (z. B. Passwortmanager).
  Besser: den Menschen den Schalter selbst umlegen lassen (Schritt 7).
- **Keine Wischgesten per adb, solange die Tastatur offen ist.** Die Wischgesten landen sonst als Tippen in
  der Tastatur. Vorher `adb shell input keyevent 4` (Zurück) senden.
- **Nicht `uiautomator dump` aufrufen, während Diktat aufnimmt.** `uiautomator` meldet die Bedienungshilfen
  kurz ab. Diktat sichert die laufende Aufnahme dann zwar im Verlauf, fügt aber nichts ein.

## Schritt 1: adb bereitstellen

Prüfe, ob `adb` vorhanden ist (`adb version`). Falls nicht:

| System | Befehl |
|---|---|
| macOS | `brew install --cask android-platform-tools` |
| Windows | `winget install Google.PlatformTools` |
| Linux (Debian/Ubuntu) | `sudo apt install android-tools-adb` |

## Schritt 2: USB-Debugging am Handy einschalten (macht der Mensch)

Die Wege unterscheiden sich je nach Hersteller:

| Hersteller | Entwickleroptionen freischalten | Wo danach „USB-Debugging“ liegt |
|---|---|---|
| Samsung (One UI) | Einstellungen → Telefoninfo → Softwareinformationen → 7× auf „Buildnummer“ tippen, PIN eingeben | Einstellungen → ganz unten „Entwickleroptionen“ |
| Google Pixel | Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ | Einstellungen → System → Entwickleroptionen |
| Xiaomi / Redmi / POCO | Einstellungen → Über das Telefon → 7× auf „MIUI-Version“ bzw. „OS-Version“ | Einstellungen → Zusätzliche Einstellungen → Entwickleroptionen |
| Andere | Meist „Über das Telefon“ → 7× auf die Build-Nummer | Meist unter „System“ |

Danach Handy per USB anschließen. Am Handy erscheint „USB-Debugging zulassen?“. Der Mensch tippt auf
**Zulassen**, am besten mit Haken bei „Von diesem Computer immer zulassen“.
`adb devices` muss das Gerät als `device` zeigen. Steht dort `unauthorized`, ist die Abfrage am Handy noch
offen. Hilft das nicht, `adb kill-server && adb start-server` und erneut prüfen.

## Schritt 3: Voraussetzungen prüfen

```bash
adb shell getprop ro.build.version.sdk          # muss ≥ 31 sein (Android 12)
adb shell getprop ro.product.cpu.abi            # arm64-v8a (Handy) oder x86_64 (Emulator)
adb shell cat /proc/meminfo | head -1           # ab ca. 6 GB RAM läuft es flüssig, 4 GB ist knapp
adb shell df -h /data | tail -1                 # mindestens 1,5 GB frei (Modell 670 MB + Kopie beim Import)
```

Bei Android 12 (SDK 31/32) funktioniert Diktat, das Einfügen in Apps mit eigenem Editor (z. B. Samsung Notes)
ist aber unzuverlässiger. Sag das dem Menschen.

## Schritt 4: App installieren

Fertige APK aus dem neuesten Release laden und installieren:

```bash
gh release download -R hwalde/diktat-android --pattern '*.apk' --dir /tmp/diktat --clobber
# ohne gh: curl -L --create-dirs -o /tmp/diktat/diktat.apk https://github.com/hwalde/diktat-android/releases/latest/download/diktat.apk
adb install -r /tmp/diktat/*.apk
```

Möchte der Mensch selbst bauen: JDK 17 oder neuer und das Android SDK (Platform 36) sind nötig, dann
`./gradlew assembleRelease` und `app/build/outputs/apk/release/app-release.apk` installieren. Ohne eigene
`keystore.properties` wird mit dem Debug-Schlüssel signiert. **Achtung:** Eine selbst gebaute APK lässt sich
nicht über eine Release-APK installieren und umgekehrt (anderer Schlüssel). Dafür müsste man vorher
deinstallieren, und dabei gehen Verlauf und Wörterbuch verloren.

## Schritt 5: App einmal öffnen und Mikrofon erlauben

```bash
adb shell am start -n de.herbertwalde.diktat/.MainActivity
```

Der Mensch tippt auf „Mikrofon erlauben“. Alternativ:
`adb shell pm grant de.herbertwalde.diktat android.permission.RECORD_AUDIO`

## Schritt 6: Sprachmodell (670 MB)

**Variante A, am Handy:** In der App auf „Sprachmodell laden“ tippen. Am besten im WLAN, das dauert einige Minuten.

**Variante B, per USB (schneller):** Die App muss vorher einmal geöffnet worden sein (Schritt 5). Dabei legt sie
den Import-Ordner selbst an. Lege den Ordner **nicht** per `adb shell mkdir` an, sonst gehört er der Shell und die
App kann die Dateien nicht übernehmen.

```bash
B=https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main
mkdir -p /tmp/diktat-modell && cd /tmp/diktat-modell
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do curl -L -o $f "$B/$f?download=true"; done
shasum -a 256 *.onnx
# erwartet:
# acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247  encoder.int8.onnx
# 179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e  decoder.int8.onnx
# 3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3  joiner.int8.onnx
D=/sdcard/Android/data/de.herbertwalde.diktat/files/import
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do adb push $f $D/; done
adb shell am force-stop de.herbertwalde.diktat
adb shell am start -n de.herbertwalde.diktat/.MainActivity
```

Die App prüft die Dateien (Größe und SHA-256) und übernimmt sie. Das kann 20 bis 60 Sekunden dauern. Fertig ist
es, wenn in der App „✓ Sprachmodell Parakeet v3 bereit“ steht.

Übernimmt die App nichts: `adb shell ls -la $D` prüfen. Gehört der Ordner `shell` statt der App, ihn mit
`adb shell rm -rf $D` löschen, die App neu öffnen und die Dateien erneut übertragen.

## Schritt 7: Bedienungshilfe einschalten (macht der Mensch)

In der App auf „Bedienungshilfe öffnen“ tippen, dann **Installierte Apps → Diktat → einschalten**.

Ist der Schalter ausgegraut („Eingeschränkte Einstellung“, ab Android 13 bei Apps außerhalb des Play Store):
**Einstellungen → Apps → Diktat → ⋮ (oben rechts) → „Eingeschränkte Einstellungen zulassen“**, danach
noch einmal versuchen.

Per adb geht es auch, aber nur durch **Anhängen** an die bestehende Liste:

```bash
S=de.herbertwalde.diktat/de.herbertwalde.diktat.DiktatAccessibilityService
CUR=$(adb shell settings get secure enabled_accessibility_services)
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW=$S; else NEW="$CUR:$S"; fi
adb shell settings put secure enabled_accessibility_services "$NEW"
adb shell settings put secure accessibility_enabled 1
```

## Schritt 8: Ausprobieren

In der App ins Feld „Ausprobieren“ tippen. Rechts erscheint ein rundes Mikrofon. Darauf tippen, sprechen, dann ✓.
Der Text erscheint nach ein bis drei Sekunden im Feld. Danach in einer beliebigen anderen App ein Textfeld antippen.

## Wenn etwas nicht klappt

| Symptom | Ursache und Lösung |
|---|---|
| Kein Mikrofon-Symbol | Bedienungshilfe nicht aktiv (Schritt 7), oder das Feld ist ein Passwortfeld (dort absichtlich aus) |
| Symbol verschwindet nach einiger Zeit | Akku-Optimierung beendet den Dienst (häufig bei Xiaomi, Huawei, Oppo). Einstellungen → Apps → Diktat → Akku → „Nicht eingeschränkt“ |
| „Nichts verstanden“ | Mikrofon wird von einer anderen App belegt, oder es war zu leise. Der Eintrag steht im Verlauf, dort „Erneut erkennen“ |
| Text landet nur in der Zwischenablage | Beim Bestätigen hatte kein Textfeld den Fokus. Einfach einfügen (lange tippen → Einfügen) |
| Erkennung sehr langsam | Schwaches Gerät. Kurze Diktate (unter 30 s) helfen |

## Deinstallieren

`adb uninstall de.herbertwalde.diktat`. Das löscht auch Modell, Verlauf und Wörterbuch.
