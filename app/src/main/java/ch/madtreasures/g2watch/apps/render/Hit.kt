package ch.madtreasures.g2watch.apps.render

/**
 * The focusable target under the pointer at ([x], [y]) in viewport coordinates (the app area as the
 * wearer sees it), with the page scrolled down by [scroll]. Null over empty space and plain content.
 */
fun PageLayout.hit(x: Int, y: Int, scroll: Int): Focusable? {
    if (x !in 0 until width || y !in 0 until height) return null
    val py = y + scroll
    return focusables.firstOrNull { it.rect.contains(x, py) }
}
