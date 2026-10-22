package ch.madtreasures.fluency

import java.text.Collator
import java.util.Locale

/** A language Fluency translates from or to: its code in Fluency (BCP 47: "de", "de-CH", "zh-Hant") and its German name. */
class Language(val code: String, val name: String)

/** Fluency's languages (`Languages.all` of Fluency 1.8.0) and "Automatisch" for the source. */
object Languages {
    /** Source: Fluency recognises the language by itself. Never a target. */
    const val AUTO = "auto"

    val all: List<Language> = listOf(
        Language("de", "Deutsch"),
        Language("de-CH", "Deutsch (Schweiz)"),
        Language("en", "Englisch"),
        Language("fr", "Französisch"),
        Language("it", "Italienisch"),
        Language("es", "Spanisch"),
        Language("pt", "Portugiesisch"),
        Language("nl", "Niederländisch"),
        Language("pl", "Polnisch"),
        Language("cs", "Tschechisch"),
        Language("sk", "Slowakisch"),
        Language("sl", "Slowenisch"),
        Language("hr", "Kroatisch"),
        Language("bg", "Bulgarisch"),
        Language("ro", "Rumänisch"),
        Language("hu", "Ungarisch"),
        Language("el", "Griechisch"),
        Language("da", "Dänisch"),
        Language("sv", "Schwedisch"),
        Language("no", "Norwegisch"),
        Language("fi", "Finnisch"),
        Language("ca", "Katalanisch"),
        Language("ru", "Russisch"),
        Language("uk", "Ukrainisch"),
        Language("tr", "Türkisch"),
        Language("ar", "Arabisch"),
        Language("he", "Hebräisch"),
        Language("fa", "Persisch"),
        Language("ur", "Urdu"),
        Language("hi", "Hindi"),
        Language("bn", "Bengalisch"),
        Language("ta", "Tamil"),
        Language("te", "Telugu"),
        Language("mr", "Marathi"),
        Language("gu", "Gujarati"),
        Language("zh", "Chinesisch (vereinfacht)"),
        Language("zh-Hant", "Chinesisch (traditionell)"),
        Language("yue", "Kantonesisch"),
        Language("ja", "Japanisch"),
        Language("ko", "Koreanisch"),
        Language("th", "Thailändisch"),
        Language("vi", "Vietnamesisch"),
        Language("id", "Indonesisch"),
        Language("ms", "Malaiisch"),
        Language("tl", "Filipino"),
        Language("km", "Khmer"),
        Language("lo", "Laotisch"),
        Language("my", "Burmesisch"),
        Language("kk", "Kasachisch"),
        Language("az", "Aserbaidschanisch"),
        Language("uz", "Usbekisch"),
        Language("bo", "Tibetisch"),
        Language("mn", "Mongolisch"),
        Language("ug", "Uigurisch"),
    )

    private val byCode = all.associateBy { it.code }

    /** Shown first in the lists, as in Fluency. */
    val core: List<Language> = listOf("de", "de-CH", "en", "fr", "it", "es", "pt").map { byCode.getValue(it) }

    /** All the others, alphabetically. */
    val more: List<Language> = (all - core.toSet()).sortedWith(compareBy(Collator.getInstance(Locale.GERMAN)) { it.name })

    fun of(code: String): Language? = byCode[code]

    /** "Automatisch" or the language's name; an unknown code as it is. */
    fun name(code: String): String = if (code == AUTO) "Automatisch" else byCode[code]?.name ?: code
}
