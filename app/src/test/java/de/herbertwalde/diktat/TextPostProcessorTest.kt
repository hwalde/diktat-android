package de.herbertwalde.diktat

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPostProcessorTest {

    @Test fun ersetztAehnlichKlingendesWort() =
        assertEquals("Ich nutze Kubernetes heute.", TextPostProcessor.applyCustomWords("Ich nutze Kubernetis heute.", listOf("Kubernetes")))

    @Test fun ziehtMehrereWoerterZusammen() =
        assertEquals("Wir zahlen mit ChargeBee.", TextPostProcessor.applyCustomWords("Wir zahlen mit Charge B.", listOf("ChargeBee")))

    @Test fun nichtUeberSatzzeichenHinweg() =
        assertEquals("ChargeBee, che", TextPostProcessor.applyCustomWords("Charge B, che", listOf("ChargeBee")))

    @Test fun laesstUnaehnlicheWoerterInRuhe() =
        assertEquals("Das ist ein Test.", TextPostProcessor.applyCustomWords("Das ist ein Test.", listOf("Kubernetes")))

    @Test fun grossschreibungAmSatzanfang() =
        assertEquals("Docker läuft.", TextPostProcessor.applyCustomWords("Doker läuft.", listOf("docker")))

    @Test fun funktioniertMitUmlauten() =
        assertEquals("Die Größenordnung passt.", TextPostProcessor.applyCustomWords("Die Grössenordnung passt.", listOf("Größenordnung")))

    @Test fun behaeltSchreibweiseDesEintrags() =
        assertEquals("mein iPhone", TextPostProcessor.applyCustomWords("mein Iphone", listOf("iPhone")))

    @Test fun entferntFuellwoerter() =
        assertEquals("Also, ich denke das passt.", TextPostProcessor.process("Also, äh, ich ähm denke das passt.", emptyList()))

    @Test fun laesstDeutschesUmStehen() =
        assertEquals("Wir treffen uns um acht.", TextPostProcessor.process("Wir treffen uns um acht.", emptyList()))

    @Test fun soundexWieReferenz() {
        assertEquals("R163", TextPostProcessor.soundex("Robert"))
        assertEquals("R163", TextPostProcessor.soundex("Rupert"))
        assertEquals("A261", TextPostProcessor.soundex("Ashcraft"))
        assertEquals("T522", TextPostProcessor.soundex("Tymczak"))
    }

    private val herbertsWoerter = listOf("Claude", "Claude Code", "Anthropic", "Haiku")

    @Test fun cloudBleibtCloud() =
        assertEquals("Wir gehen in die Cloud.", TextPostProcessor.applyCustomWords("Wir gehen in die Cloud.", herbertsWoerter))

    @Test fun heikeBleibtHeike() =
        assertEquals("Heike kommt morgen.", TextPostProcessor.applyCustomWords("Heike kommt morgen.", herbertsWoerter))

    @Test fun anthropischBleibtAnthropisch() =
        assertEquals("ein anthropisch geprägter Blick", TextPostProcessor.applyCustomWords("ein anthropisch geprägter Blick", herbertsWoerter))

    @Test fun pluralBleibtStehen() =
        assertEquals("Ich schreibe Haikus.", TextPostProcessor.applyCustomWords("Ich schreibe Haikus.", herbertsWoerter))

    @Test fun klaudeWirdClaude() =
        assertEquals("Frag Claude.", TextPostProcessor.applyCustomWords("Frag Klaude.", herbertsWoerter))

    @Test fun anthropikWirdAnthropic() =
        assertEquals("Das Modell von Anthropic.", TextPostProcessor.applyCustomWords("Das Modell von Anthropik.", herbertsWoerter))
}
