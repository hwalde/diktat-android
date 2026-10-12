# Diktat – Offline-Spracheingabe für Android

Ersatz für Wispr Flow auf dem Galaxy S26 Ultra. Sobald der Cursor in einem Textfeld steht, erscheint ein
verschiebbares Mikrofon. Ein Tipp darauf startet die Aufnahme und zeigt eine Leiste mit **✕** (abbrechen),
Waveform und **✓** (übernehmen, in der Primärfarbe des Systems). Der erkannte Text wird an der Cursor-Position eingefügt.

Die Erkennung läuft komplett auf dem Gerät: **Parakeet TDT 0.6B v3** (multilingual, int8) über
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Das ist dieselbe ONNX-Modellfamilie, die
[Handy](https://github.com/cjpais/Handy) auf dem Desktop nutzt.

## Warum kein direkter Port von Handy?

Handy ist eine Tauri-Desktop-App mit globalen Tastenkürzeln, Tray und Auto-Paste. Ein schwebendes Symbol über
fremden Apps und das Einfügen in deren Textfelder geht auf Android nur über einen nativen
**AccessibilityService**. Deshalb ist Diktat eine native Kotlin-App. Folgende Teile sind aus Handy übernommen:

| Handy (Rust) | Diktat (Kotlin) |
|---|---|
| `audio_toolkit/text.rs` → `apply_custom_words` (Levenshtein + Soundex + N-Gramme, Schwelle 0,18) | `TextPostProcessor.applyCustomWords`, erweitert um Umlaute |
| `remove_filler_words` (universelle + deutsche Füllwörter) | `TextPostProcessor.removeFillerWords` |
| Verlauf mit gespeicherten Aufnahmen + erneute Transkription | `HistoryStore` + „Erneut erkennen“ |
| Parakeet v3 int8 über ONNX | `SpeechEngine` (sherpa-onnx 1.13.8) |

Zusätzlich gibt es einen Schutz gegen ein bekanntes Parakeet-Problem: Das Modell lässt manchmal stillschweigend
Passagen weg. Lange Aufnahmen werden deshalb an leisen Stellen in Stücke von höchstens 25 s zerlegt. Ist die
Wortzahl im Verhältnis zur Sprechdauer auffällig niedrig, wird der Eintrag als „evtl. unvollständig“ markiert.
„Erneut erkennen“ zerlegt dann feiner (12 s).

## Aufbau

- `DiktatAccessibilityService`: erkennt das Textfeld, steuert Aufnahme und Einfügen.
  - **Erkennung, zwei Fälle:** (1) Das fokussierte Feld meldet sich als bearbeitbar (Chrome, Messenger,
    Formulare). (2) Die Tastatur ist sichtbar, das Feld meldet sich aber nicht als bearbeitbar (eigene Editoren
    wie Samsung Notes). Passwortfelder sind ausgenommen, erkannt am Feld selbst und am Eingabetyp der Tastatur.
  - **Aufnahme:** Sie läuft weiter, bis ✓ oder ✕ gedrückt wird, auch beim Scrollen oder beim Wechsel der App.
  - **Einfügen beim Bestätigen:** Nur wenn dann ein Textfeld den Fokus hat. Sonst bleibt der Text im Verlauf und
    liegt zusätzlich in der Zwischenablage, mit Hinweis.
  - **Reihenfolge:** Zuerst die Eingabeverbindung der Bedienungshilfe
    (`AccessibilityService.getInputMethod()`, Android 13+, `flagInputMethodEditor`). Sie schreibt wie eine Tastatur
    am Cursor und ersetzt nie Inhalt, ist also sicher für Rich Text, Checklisten und Bilder in Notizen. Rückfall nur
    für normale Felder ist `ACTION_SET_TEXT` mit bis zu 1,2 s Nachprüfung (Chrome übernimmt verzögert). Danach
    Zwischenablage + `ACTION_PASTE` am selben Feld, zuletzt nur Zwischenablage mit Hinweis. Für Rich-Text-Apps
    (Samsung Notes, Keep) gibt es nie `ACTION_SET_TEXT`.
- `OverlayController` / `WaveformView`: Mikrofon-Bubble und Aufnahme-Leiste (`TYPE_ACCESSIBILITY_OVERLAY`,
  nie fokussierbar, damit die Tastatur offen bleibt)
- `ModelManager`: lädt die 4 Modelldateien von Hugging Face über den DownloadManager und prüft SHA-256
- `MainActivity`: Einrichtung (Mikrofon, Modell, Bedienungshilfe), Testfeld, Wörterbuch, Verlauf

## Bauen und installieren

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Das Android SDK liegt unter `/opt/homebrew/share/android-commandlinetools` (siehe `local.properties`).
Die Release-APK ist mit dem Debug-Schlüssel signiert (private App), damit Updates per `adb install -r` gehen.

## Einrichtung auf dem Handy

1. App „Diktat“ öffnen → Mikrofon erlauben
2. Sprachmodell laden (670 MB, einmalig, am besten im WLAN), **oder** per USB übertragen (Sekunden statt Minuten):
   ```bash
   D=/sdcard/Android/data/de.herbertwalde.diktat/files/import
   adb shell mkdir -p $D
   for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do adb push <modellordner>/$f $D/; done
   ```
   Die App übernimmt die Dateien beim nächsten Öffnen. Vorher prüft sie die SHA-256-Prüfsumme, danach bricht sie
   einen laufenden Download ab.
3. Bedienungshilfe „Diktat“ einschalten (Einstellungen → Eingabehilfe → Installierte Apps)

## Wörterbuch

Ähnlich klingende erkannte Wörter werden durch die Schreibweise des Eintrags ersetzt (Handys Verfahren).
Abweichend von Handy werden echte Wörter geschützt:
- Den Soundex-Bonus gibt es nur bei einem abweichenden Zeichen oder bei verkürzt erkannten langen Einträgen.
  So bleibt „Cloud“ stehen und wird nicht zu „Claude“, und „Heike“ wird nicht zu „Haiku“.
- Gebeugte Formen bleiben stehen: „Haikus“ wird nicht zu „Haiku“.
