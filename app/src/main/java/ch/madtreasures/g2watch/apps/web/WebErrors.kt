package ch.madtreasures.g2watch.apps.web

import org.mozilla.geckoview.WebRequestError
import java.util.Base64

/** German words for what went wrong with a page, and the page the glasses show instead. */
object WebErrors {
    /** Why a page did not load, from GeckoView's [WebRequestError] category and code. */
    fun text(category: Int, code: Int): String = when (code) {
        WebRequestError.ERROR_UNKNOWN_HOST -> "Adresse nicht gefunden"
        WebRequestError.ERROR_OFFLINE -> "Kein Internet"
        WebRequestError.ERROR_NET_TIMEOUT -> "Die Seite antwortet nicht"
        WebRequestError.ERROR_CONNECTION_REFUSED -> "Die Seite nimmt keine Verbindung an"
        WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "Verbindung abgebrochen"
        WebRequestError.ERROR_REDIRECT_LOOP -> "Die Seite leitet endlos weiter"
        WebRequestError.ERROR_MALFORMED_URI -> "Ungültige Adresse"
        WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "Diese Art Adresse geht hier nicht"
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_BAD_HSTS_CERT -> "Zertifikat der Seite ungültig"
        WebRequestError.ERROR_SECURITY_SSL -> "Sichere Verbindung nicht möglich"
        WebRequestError.ERROR_HTTPS_ONLY -> "Die Seite hat keine sichere Verbindung"
        WebRequestError.ERROR_CONTENT_CRASHED -> "Die Seite ist abgestürzt"
        WebRequestError.ERROR_UNSAFE_CONTENT_TYPE, WebRequestError.ERROR_CORRUPTED_CONTENT,
        WebRequestError.ERROR_INVALID_CONTENT_ENCODING -> "Die Seite ist beschädigt"
        WebRequestError.ERROR_PORT_BLOCKED -> "Dieser Port ist gesperrt"
        WebRequestError.ERROR_FILE_NOT_FOUND, WebRequestError.ERROR_FILE_ACCESS_DENIED -> "Datei nicht erreichbar"
        WebRequestError.ERROR_PROXY_CONNECTION_REFUSED, WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> "Proxy nicht erreichbar"
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI, WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
        WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI, WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI -> "Als gefährlich gemeldete Seite"
        else -> when (category) {
            WebRequestError.ERROR_CATEGORY_NETWORK -> "Netzwerkfehler"
            WebRequestError.ERROR_CATEGORY_SECURITY -> "Sicherheitsfehler"
            else -> "Die Seite lädt nicht"
        }
    }

    /**
     * The page shown in place of one that did not load: dark, large, German, without scripts. As a
     * `data:` address, so it needs neither the network nor a file.
     */
    fun page(message: String, url: String): String {
        val html = """
            <!doctype html><html lang="de"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{background:#000;color:#fff;margin:0}body{font:20px/1.35 sans-serif;padding:16px}
            p{color:#bbb;font-size:16px;word-break:break-all}</style></head>
            <body><h1 style="font-size:24px;margin:0 0 10px">${escape(message)}</h1><p>${escape(url.take(200))}</p></body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8;base64," + Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
    }

    /** The scheme of an address the browser does not open (a phone number, another app). */
    fun foreignScheme(url: String): String? {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme.takeIf { it.isNotEmpty() && it !in OPEN_SCHEMES }
    }

    /** What a page link may lead to inside the browser. */
    val OPEN_SCHEMES = setOf("http", "https", "about", "data", "blob")

    /** A note for a link the watch does not follow. */
    fun foreignLink(scheme: String): String = when (scheme) {
        "tel" -> "Telefonnummern lassen sich hier nicht anrufen"
        "mailto" -> "E-Mail-Links gehen hier nicht"
        else -> "Dieser Link öffnet eine andere App ($scheme:) und geht hier nicht"
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
