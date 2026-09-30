package ch.madtreasures.g2watch.apps

/**
 * One element of a page (02 §4.2). Blocks stack from top to bottom in the order given; there is no
 * free positioning. Every [id] is unique in the whole app: pages and blocks share one namespace,
 * exactly as in the G2 Baukasten, so apps address blocks by id alone.
 */
sealed interface Block {
    val id: String

    /** 28 px semi-bold (36 px when [size] is [HeadingSize.GROSS]), brightest level. */
    data class Heading(
        override val id: String,
        val text: String,
        val align: Align = Align.LEFT,
        val size: HeadingSize = HeadingSize.NORMAL,
    ) : Block

    /** 22 px, wrapping; `\n` starts a new line. */
    data class Text(override val id: String, val text: String, val align: Align = Align.LEFT) : Block

    /**
     * A capsule the wearer clicks. With a page [target] the host shows that page at once and then
     * tells the app ([AppEvent.Navigate]); with [BACK] it goes back; without one the app gets
     * [AppEvent.Click]. [action] only describes what the button does (from the Baukasten).
     */
    data class Button(
        override val id: String,
        val text: String,
        val target: String? = null,
        val action: String = "",
    ) : Block

    /** Rows of 30 px; with [ListStyle.CHECKS] every row is a checkbox the wearer can tick. */
    data class List(
        override val id: String,
        val items: kotlin.collections.List<ListItem>,
        val style: ListStyle = ListStyle.BULLETS,
    ) : Block

    /** A 36 px row with a switch on the right. */
    data class Toggle(override val id: String, val text: String, val on: Boolean = false) : Block

    /** A 36 px row: label left, dimmed value right. */
    data class Value(override val id: String, val text: String, val value: String) : Block

    /** A 16 px label over an 8 px bar; [value] is 0–100. */
    data class Progress(override val id: String, val text: String, val value: Int) : Block

    /** A 2 px line with 6 px of space above and below. */
    data class Divider(override val id: String) : Block

    /**
     * A picture, rounded to 16 grey levels. [src] is `data:image/png;base64,…`, `asset:<file>` (a file in
     * the app's asset folder) or null (black until pixels arrive). Normally at most 544 × 260; with
     * [bleed] on a full-screen page up to 576 × 288 without margins.
     */
    data class Image(
        override val id: String,
        val src: String?,
        val w: Int,
        val h: Int,
        val align: Align = Align.CENTER,
        val bleed: Boolean = false,
    ) : Block

    companion object {
        /** Button target that means "back" instead of a page. */
        const val BACK = "@back"

        /** Allowed ids of pages and blocks. */
        val ID = Regex("[A-Za-z0-9_.-]{1,40}")

        /** Largest image inside the page margins, and without them on a full-screen page. */
        const val IMAGE_MAX_W = 544
        const val IMAGE_MAX_H = 260
        const val BLEED_MAX_W = 576
        const val BLEED_MAX_H = 288

        /** A `data:` URL may be this long (it has to fit into one protocol message). */
        const val DATA_URL_MAX = 48 * 1024
    }
}

data class ListItem(val text: String, val done: Boolean = false)
