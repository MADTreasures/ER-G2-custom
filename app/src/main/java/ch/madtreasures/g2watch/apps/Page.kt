package ch.madtreasures.g2watch.apps

enum class Align(val json: String) {
    LEFT("left"),
    CENTER("center"),
    ;

    companion object {
        fun of(json: String?): Align? = entries.firstOrNull { it.json == json }
    }
}

enum class HeadingSize(val json: String) {
    NORMAL("normal"),
    GROSS("gross"),
    ;

    companion object {
        fun of(json: String?): HeadingSize? = entries.firstOrNull { it.json == json }
    }
}

enum class ListStyle(val json: String) {
    BULLETS("bullets"),
    CHECKS("checks"),
    NUMBERS("numbers"),
    ;

    companion object {
        fun of(json: String?): ListStyle? = entries.firstOrNull { it.json == json }
    }
}

/** A page of an app (02 §4.1). */
data class Page(
    val id: String,
    /** Shown in the header next to the app name. */
    val name: String,
    val blocks: List<Block>,
    /** With the header: app area 576 × 260. Without: full screen 576 × 288. */
    val statusBar: Boolean = true,
    /** What the page is for (from the Baukasten); never shown. */
    val notes: String = "",
) {
    fun block(id: String): Block? = blocks.firstOrNull { it.id == id }
}
