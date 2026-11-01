package io.github.ccch1mneyyy.dsh_app.phone.a11y

// Pages whose content the system keeps from us, and what the model is told about them.
//
// Since Android 14 a page can be marked data-sensitive: a service that is not an
// accessibility *tool* still gets the nodes, but no text (and screenshots come back
// blocked or black). Before this the driver read such a page as an empty tree and told
// the model 「本页文字信息不足（游戏、自绘界面）」 — a wrong guess that also invited blind
// taps on a page where a mis-aimed tap is expensive (payment, password, verification).
//
// So: name the likely cause, and refuse coordinate taps / screen swipes on the pages we
// cannot read. Plain Kotlin so the rules run in a JVM test.

object SensitiveView {
    /** Nodes the app/platform marked data-sensitive (API 34+). */
    fun marked(windows: List<UiWindow>): Int = windows.sumOf { w -> w.root?.let { markedOf(it) } ?: 0 }

    private fun markedOf(node: UiNode): Int =
        (if (node.dataSensitive) 1 else 0) + node.children.sumOf { markedOf(it) }

    /** Nodes carrying text or a description; below [ScreenFormat.POOR_TREE] the page reads as empty. */
    fun textNodes(windows: List<UiWindow>): Int = windows.sumOf { w -> w.root?.let { textOf(it) } ?: 0 }

    private fun textOf(node: UiNode): Int =
        (if (!node.text.isNullOrBlank() || !node.desc.isNullOrBlank()) 1 else 0) + node.children.sumOf { textOf(it) }

    /**
     * The page labels its content as sensitive: Android 14+ filtering, not a canvas app.
     * [apiLevel] because the flag only exists from 34 on.
     */
    fun looksFiltered(apiLevel: Int, windows: List<UiWindow>): Boolean = apiLevel >= 34 && marked(windows) > 0

    /** Nothing readable in front of us: a canvas app, a game — or a page filtered by the system. */
    fun unreadable(windows: List<UiWindow>): Boolean =
        windows.all { it.root == null } || textNodes(windows) < ScreenFormat.POOR_TREE

    /**
     * The note that replaces [ScreenFormat.POOR_TREE_NOTE] on an unreadable page; null when the
     * page reads fine and needs no note.
     */
    fun note(filtered: Boolean, windows: List<UiWindow>): String? = when {
        filtered -> NOTE_FILTERED
        unreadable(windows) -> NOTE_UNREADABLE
        else -> null
    }

    // l10n:keep agent screen result
    const val NOTE_FILTERED = "提示：这一页是系统标为敏感的内容（密码、支付、验证一类的页面，Android 14 起会被过滤），DSH 读不到文字，也不做盲点盲滑。截图一般也拿不到。需要的话请用 handoff 交给用户。"

    // l10n:keep agent screen result
    const val NOTE_UNREADABLE = "提示：本页读不到文字：可能受敏感视图策略限制（Android 14 起，密码、支付、验证这类页面会被系统过滤），也可能是游戏、自绘界面或小程序。能看图的模型可以 screen 加 image 看标号截图；敏感页面不会有截图。读不到内容的页面上 DSH 不做盲点盲滑。"

    // l10n:keep agent screen result
    const val REFUSED_TAP = "这一页读不到内容（可能受敏感视图策略限制），坐标点击等于盲点，DSH 不点。请用 handoff 交给用户，或让用户自己点。"

    // l10n:keep agent screen result
    const val REFUSED_SWIPE = "这一页读不到内容（可能受敏感视图策略限制），滑动等于盲滑，DSH 不滑。请用 handoff 交给用户。"

    /** An action went through but neither side can be compared: say so instead of 「没有变化」. */
    // l10n:keep agent screen result
    const val CANNOT_VERIFY = "动作已提交，但当前无法验证结果。"

    /** The screenshot in this observation came back blocked or black. */
    // l10n:keep agent screen result
    const val SCREENSHOT_BLOCKED = "这一页的截图被系统挡住（禁止截图的安全窗口），没有图可用，坐标点击也不可用。"
}
