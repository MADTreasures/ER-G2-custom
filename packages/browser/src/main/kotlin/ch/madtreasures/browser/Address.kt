package ch.madtreasures.browser

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/** What the wearer typed or said on the watch, as a web address. */
object Address {
    /** Searches go to DuckDuckGo's text page: no consent dialog, little to load, easy to read on the glasses. */
    const val SEARCH = "https://lite.duckduckgo.com/lite/?q="

    private val HOST = Regex("""[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)*\.\p{L}{2,}(:\d{1,5})?([/?#].*)?""")

    /**
     * An address as typed (`https://…`), a host name (`srf.ch`, also spoken: `srf punkt ch`) with
     * `https://` in front, anything else a search; null for nothing.
     */
    fun of(input: String?): String? {
        val text = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = text.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) return text
        val spoken = text.replace(Regex("""\s+(punkt|dot)\s+""", RegexOption.IGNORE_CASE), ".")
        if (' ' !in spoken && HOST.matches(spoken)) return "https://$spoken"
        return SEARCH + URLEncoder.encode(text, "UTF-8")
    }

    /** The host of [url] without `www.`, for headers and lists; the address itself if it has none. */
    fun host(url: String): String {
        val host = try {
            URI(url).host
        } catch (e: URISyntaxException) {
            null
        }
        return host?.removePrefix("www.") ?: url
    }
}
