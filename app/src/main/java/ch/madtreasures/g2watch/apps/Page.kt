package ch.madtreasures.g2watch.apps

/**
 * One screen of an app (02 §4.1): a name for the header and blocks stacked from top to bottom.
 * There is no free positioning. With [statusBar] the page gets the app area of 576 × 260 below
 * the header, without it the whole visible band of 576 × 288.
 */
data class Page(
    val id: String,
    val name: String,
    val statusBar: Boolean = true,
    /** What the page should do, from the Baukasten; never shown. */
    val notes: String = "",
    val blocks: List<Block> = emptyList(),
) {
    fun block(id: String): Block? = blocks.firstOrNull { it.id == id }
}

enum class Align(val json: String) {
    LEFT("left"),
    CENTER("center"),
    ;

    companion object {
        fun of(value: String?, default: Align): Align = entries.firstOrNull { it.json == value } ?: default
    }
}

enum class HeadingSize(val json: String) {
    NORMAL("normal"),

    /** 36 px instead of 28 px; the Baukasten calls it "groß". */
    GROSS("gross"),
    ;

    companion object {
        fun of(value: String?): HeadingSize = entries.firstOrNull { it.json == value } ?: NORMAL
    }
}

enum class ListStyle(val json: String) {
    BULLETS("bullets"),

    /** Every row has a box the wearer can tick; each row is focusable. */
    CHECKS("checks"),
    NUMBERS("numbers"),
    ;

    companion object {
        fun of(value: String?): ListStyle = entries.firstOrNull { it.json == value } ?: BULLETS
    }
}

data class ListItem(val text: String, val done: Boolean = false)

/**
 * A block of a page (02 §4.2). Ids are unique in the whole project: pages and blocks share one
 * namespace, so commands can name a block without its page.
 */
sealed interface Block {
    val id: String

    /** The `type` in the JSON format. */
    val type: String

    /** Whether the pointer and the temple swipes can put the focus on this block. */
    val focusable: Boolean get() = false

    data class Heading(
        override val id: String,
        val text: String,
        val align: Align = Align.LEFT,
        val size: HeadingSize = HeadingSize.NORMAL,
    ) : Block {
        override val type: String get() = "heading"
    }

    data class Text(
        override val id: String,
        val text: String,
        val align: Align = Align.LEFT,
    ) : Block {
        override val type: String get() = "text"
    }

    /**
     * A capsule button. With a page [target] the host shows that page at once and then tells the
     * app (`navigate`); with [BACK] it goes back; without a target it only sends `click`.
     */
    data class Button(
        override val id: String,
        val text: String,
        val target: String? = null,
        /** What the button does, in words from the Baukasten; never shown. */
        val action: String = "",
        /** Host pages only (the launcher's "läuft"), right-aligned and dimmed; not part of the format. */
        val badge: String? = null,
    ) : Block {
        override val type: String get() = "button"
        override val focusable: Boolean get() = true
    }

    data class List(
        override val id: String,
        val style: ListStyle = ListStyle.BULLETS,
        val items: kotlin.collections.List<ListItem> = emptyList(),
    ) : Block {
        override val type: String get() = "list"
        override val focusable: Boolean get() = style == ListStyle.CHECKS && items.isNotEmpty()
    }

    data class Toggle(
        override val id: String,
        val text: String,
        val on: Boolean = false,
    ) : Block {
        override val type: String get() = "toggle"
        override val focusable: Boolean get() = true
    }

    data class Value(
        override val id: String,
        val text: String,
        val value: String = "",
    ) : Block {
        override val type: String get() = "value"
    }

    data class Progress(
        override val id: String,
        val text: String,
        /** 0–100. */
        val value: Int = 0,
    ) : Block {
        override val type: String get() = "progress"
    }

    data class Divider(override val id: String) : Block {
        override val type: String get() = "divider"
    }

    /**
     * A picture, rounded to the 16 levels of the glasses. [src] is a `data:image/png;base64,…` URL
     * (≤ 48 KiB of text), `asset:<file>` from the app package, or null (black until pixels arrive).
     * [w] × [h] is at most 544 × 260; with [bleed] on a page without status bar up to 576 × 288
     * without margins.
     */
    data class Image(
        override val id: String,
        val src: String? = null,
        val w: Int = MAX_W,
        val h: Int = 120,
        val align: Align = Align.CENTER,
        val bleed: Boolean = false,
    ) : Block {
        override val type: String get() = "image"

        companion object {
            const val MAX_W = 544
            const val MAX_H = 260
            const val BLEED_W = 576
            const val BLEED_H = 288

            /** Text length limit of a `data:` URL, so it fits into one protocol message. */
            const val MAX_DATA_URL = 48 * 1024
        }
    }

    companion object {
        /** Button target that goes back like a double tap. */
        const val BACK = "@back"

        val TYPES = listOf("heading", "text", "button", "list", "toggle", "value", "progress", "divider", "image")

        /** Ids of pages and blocks. */
        val ID_PATTERN = Regex("[A-Za-z0-9_.-]{1,40}")
    }
}
