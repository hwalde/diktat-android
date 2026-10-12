# Herkunft und Lizenzen

Diktat steht unter der [MIT-Lizenz](LICENSE). Diese Seite listet alles auf, was **nicht** für Diktat neu
geschrieben wurde: was übernommen, portiert oder eingebunden ist und unter welcher Lizenz es steht.

| Bestandteil | Was davon in Diktat steckt | Wie | Lizenz | Lizenztext |
|---|---|---|---|---|
| [Handy](https://github.com/cjpais/Handy) von CJ Pais | Wörterbuch-Korrektur (`apply_custom_words`: Levenshtein, Soundex, N-Gramme, Schwelle 0,18) und Füllwort-Filter aus `audio_toolkit/text.rs` | Von Rust nach Kotlin portiert und erweitert, siehe `TextPostProcessor.kt` | MIT | [LICENSES/Handy-MIT.txt](LICENSES/Handy-MIT.txt) |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (k2-fsa) | Spracherkennungs-Laufzeit | Als fertige Bibliothek eingebunden: `app/libs/sherpa-onnx-1.13.8.aar`, unverändert | Apache 2.0 | [LICENSES/sherpa-onnx-Apache-2.0.txt](LICENSES/sherpa-onnx-Apache-2.0.txt) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (Microsoft) | Ausführung des Modells | Steckt in der sherpa-onnx-Bibliothek (`libonnxruntime.so`), unverändert | MIT | [LICENSES/onnxruntime-MIT.txt](LICENSES/onnxruntime-MIT.txt) |
| [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) von NVIDIA | Das Sprachmodell | **Nicht im Repo und nicht in der APK.** Die App lädt es beim Einrichten herunter, in der int8-Fassung von [csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) | CC BY 4.0 | [creativecommons.org/licenses/by/4.0](https://creativecommons.org/licenses/by/4.0/) |
| AndroidX, Material Components, Kotlin Coroutines | UI- und Hilfsbibliotheken | Über Gradle eingebunden, unverändert | Apache 2.0 | wie sherpa-onnx |
| Gradle Wrapper | Build-Werkzeug (`gradle/wrapper/`) | Unverändert | Apache 2.0 | wie sherpa-onnx |

**Namensnennung für das Modell (CC BY 4.0):** „Parakeet TDT 0.6B v3“ von NVIDIA, Lizenz CC BY 4.0. Konvertiert nach
ONNX und auf int8 quantisiert vom sherpa-onnx-Projekt. Diktat verändert das Modell nicht. Die App nennt es
auch selbst, am Ende der Einstellungsseite.

## Abweichungen von Handy

Handys Wörterbuch-Verfahren ist übernommen, aber an vier Stellen geändert:

- Umlaute und andere Unicode-Buchstaben zählen als Wortzeichen.
- Den Soundex-Bonus gibt es nur bei höchstens einem abweichenden Zeichen oder bei verkürzt erkannten langen
  Einträgen. So wird „Cloud“ nicht zu „Claude“ und „Heike“ nicht zu „Haiku“.
- Gebeugte Formen eines Eintrags bleiben stehen („Haikus“ wird nicht zu „Haiku“).
- Einträge mit Großbuchstaben behalten exakt ihre Schreibweise („iPhone“ bleibt „iPhone“).

## Wer den Code geschrieben hat

Den Code von Diktat hat **Claude Opus 5.5** (Anthropic) mit **Claude Code** geschrieben und auf einem echten
Gerät getestet. Herbert Walde hat die Anforderungen gestellt, die Ergebnisse abgenommen und betreibt das
Projekt. Siehe [README](README.md#wie-diktat-entstanden-ist).
