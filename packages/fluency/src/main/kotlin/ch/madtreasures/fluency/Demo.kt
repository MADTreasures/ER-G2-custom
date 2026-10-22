package ch.madtreasures.fluency

/**
 * "Probelauf": a short made-up conversation that takes the same way as Fluency's messages, at Fluency's
 * pace – a partial result about every 400 ms with the translation one step behind the words, then the
 * finished sentence and time to read it. So the captions can be judged on the glasses without a phone;
 * nothing is translated here. The script switches languages, as Fluency's "Automatisch" does, and has a
 * long sentence (the caption rolls on) and an emoji.
 */
class Demo(private val script: List<Line> = SCRIPT) {
    /** One utterance: spoken in [from], what was said ([source]) and a translation ([text]). */
    class Line(val from: String, val source: String, val text: String)

    /** The next message, and how long to wait before the one after it. */
    class Step(val utterance: Utterance, val delayMs: Long)

    private var line = 0
    private var step = 0

    /** All of the script has been played, and the last sentence read: [next] said so. */
    var finished = false
        private set

    /** The next message, or null at the end of the script. */
    fun next(): Step? {
        val l = script.getOrNull(line)
        if (l == null) {
            finished = true
            return null
        }
        val id = (line + 1).toLong()
        val said = l.source.split(' ')
        val translated = l.text.split(' ')
        val steps = maxOf(said.size, translated.size)
        step++
        if (step <= steps) {
            // The words come in step by step; the translation follows one step later.
            val source = said.take(share(step, said.size, steps)).joinToString(" ")
            val text = translated.take(share(step - 1, translated.size, steps)).joinToString(" ")
            return Step(Utterance(id, source, text, final = false, from = l.from), PARTIAL_MS)
        }
        line++
        step = 0
        return Step(Utterance(id, l.source, l.text, final = true, from = l.from), readingMs(l.text))
    }

    /** Words of [total] shown after [step] of [steps] steps, rounded up. */
    private fun share(step: Int, total: Int, steps: Int): Int = (step * total + steps - 1) / steps

    companion object {
        /** Until the first words, after starting. */
        const val FIRST_MS = 1_200L

        /** Between partial results, as often as Fluency recognises anew. */
        const val PARTIAL_MS = 400L

        /** Time to read a finished sentence before the next one starts. */
        fun readingMs(text: String): Long = (1_500L + 45L * text.length).coerceIn(2_000L, 5_000L)

        val SCRIPT = listOf(
            Line("en", "Good morning! Are you looking for the train station?", "Guten Morgen! Suchen Sie den Bahnhof?"),
            Line(
                "en",
                "It is about ten minutes on foot: straight down this street, then left at the bakery.",
                "Es sind etwa zehn Minuten zu Fuss: diese Strasse geradeaus, dann bei der Bäckerei links.",
            ),
            Line("fr", "Le prochain train pour Genève part à quinze heures douze, voie quatre.", "Der nächste Zug nach Genf fährt um 15.12 Uhr ab, Gleis 4."),
            Line(
                "it",
                "Attenzione, il treno regionale per Bellinzona ha circa dieci minuti di ritardo.",
                "Achtung, der Regionalzug nach Bellinzona hat etwa zehn Minuten Verspätung.",
            ),
            Line(
                "en",
                "If you have a little more time, the old town is beautiful in the evening, and the restaurant by the lake " +
                    "serves the best fish soup I have ever had.",
                "Wenn Sie etwas mehr Zeit haben: Die Altstadt ist am Abend wunderschön, und das Restaurant am See " +
                    "serviert die beste Fischsuppe, die ich je gegessen habe.",
            ),
            Line("es", "¿Le escribo el camino en un papel?", "Soll ich Ihnen den Weg aufschreiben?"),
            Line("en", "Have a nice trip! 😊", "Gute Reise! 😊"),
        )
    }
}
