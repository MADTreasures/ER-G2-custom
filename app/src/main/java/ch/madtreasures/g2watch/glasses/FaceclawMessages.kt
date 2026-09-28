package ch.madtreasures.g2watch.glasses

/**
 * Faceclaw's flows report failures as English exception texts, some written for a phone ("if the
 * phone shows a pairing request"). The watch shows a German sentence with a watch instruction
 * instead; the original text goes to the protocol only.
 */
object FaceclawMessages {
    fun german(raw: String?): String {
        val text = raw.orEmpty().lowercase()
        val reason = when {
            text.isBlank() -> "Unbekannter Fehler"
            "cancel" in text -> "Abgebrochen"
            "authenticat" in text || "pairing" in text || "bond" in text ->
                "Die Kopplung mit der Brille wurde nicht bestätigt – falls die Uhr eine Kopplungsanfrage zeigt: bestätigen"
            "lost connection" in text || "not connected" in text || "disconnected" in text || "link dropped" in text ->
                "Die Verbindung zur Brille ist abgerissen"
            "could not reach" in text || "connect" in text || "discover" in text || "subscribe" in text ->
                "Die Brille war nicht erreichbar (eingeschaltet, nah an der Uhr, nicht mit dem Handy verbunden?)"
            "nak" in text || "rejected" in text || "file_check" in text || "verify failed" in text ->
                "Die Brille hat einen Teil der Firmware nicht angenommen"
            "no ack" in text || "timeout" in text || "timed out" in text || "no response" in text || "unanswered" in text ->
                "Die Brille hat nicht rechtzeitig geantwortet"
            "failed after" in text -> "Ein Teil der Firmware ließ sich nicht übertragen"
            "prompt" in text || "page" in text || "prelude" in text -> "Die Brille hat die Anfrage nicht angenommen"
            "silent" in text -> "Die Brille ist im Lautlos-Modus"
            "bluetooth" in text && ("off" in text || "unavailable" in text || "adapter" in text) -> "Bluetooth der Uhr ist aus"
            else -> "Unbekannter Fehler"
        }
        return "$reason (Details im Protokoll)"
    }
}
