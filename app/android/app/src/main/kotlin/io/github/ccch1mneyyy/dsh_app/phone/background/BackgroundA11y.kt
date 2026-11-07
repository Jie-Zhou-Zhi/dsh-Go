package io.github.ccch1mneyyy.dsh_app.phone.background

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.ccch1mneyyy.dsh_app.phone.SensitiveWords
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService

/**
 * The pre-14 element reader: the app's own accessibility service sees the background display's
 * window (confirmed on a ColorOS Android 13 device), so it reads and acts on that window directly.
 * The engine keeps creating the display and launching the app; this only covers what the engine's
 * display-bound UiAutomation cannot do below Android 14.
 *
 * Element ids follow the same 1-based order as the engine's reads, and the response Bundle uses the
 * same [Proto] keys, so [BackgroundDisplay] renders and fingerprints both paths identically.
 */
internal object BackgroundA11y {
    private const val MAX_NODES = 1200

    /** The native node for each emitted element, index i ↔ element id i+1. */
    @Volatile private var nodes: List<AccessibilityNodeInfo> = emptyList()
    @Volatile private var readDisplay: Int = -1

    fun clear() {
        nodes = emptyList()
        readDisplay = -1
    }

    /** A window read as a response-shaped Bundle, or null when there is no readable window here. */
    fun read(displayId: Int, pkg: String): Bundle? {
        val service = PhoneA11yService.instance ?: return null
        val all = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return null
        val window = all.get(displayId).orEmpty().firstOrNull { candidate ->
            candidate.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                runCatching { candidate.root?.packageName?.toString() == pkg }.getOrDefault(false)
        } ?: return null
        val root = runCatching { window.root }.getOrNull() ?: return null
        val screen = Rect().also { window.getBoundsInScreen(it) }
        val out = ArrayList<BgNode>()
        val held = ArrayList<AccessibilityNodeInfo>()
        val state = Walk(out, held, 0, screen)
        try {
            walk(root, state, indent = 0)
        } catch (_: RuntimeException) {
            return null
        }
        if (out.isEmpty()) return null
        nodes = held
        readDisplay = displayId
        return bundle(pkg, out, state.complete, screen)
    }

    /** Long-click and click on the element's node; the engine's injection stays for scrolling. */
    fun click(index: Int, long: Boolean): Boolean {
        val node = nodes.getOrNull(index - 1) ?: return false
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var candidate: AccessibilityNodeInfo? = node
        var depth = 0
        while (candidate != null && depth < 4) {
            if (runCatching { candidate.isEnabled && candidate.isVisibleToUser }.getOrDefault(false) &&
                (if (long) candidate.isLongClickable else candidate.isClickable) &&
                runCatching { candidate.performAction(action) }.getOrDefault(false)) return true
            candidate = runCatching { candidate.parent }.getOrNull()
            depth++
        }
        return false
    }

    /** Focus then set the whole value; a submit press follows only when asked. */
    fun setText(index: Int, text: String, submit: Boolean): Boolean {
        val node = nodes.getOrNull(index - 1) ?: return false
        if (!runCatching { node.isEditable }.getOrDefault(false)) return false
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val set = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })
        }.getOrDefault(false)
        if (!set) return false
        if (submit) runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PRESS_ENTER) }
        return true
    }

    fun displayId(): Int = readDisplay

    private class Walk(
        val out: ArrayList<BgNode>,
        val held: ArrayList<AccessibilityNodeInfo>,
        var visited: Int,
        val screen: Rect,
    ) {
        var complete = true
    }

    private fun walk(node: AccessibilityNodeInfo?, state: Walk, indent: Int) {
        if (node == null) return
        if (state.visited >= MAX_NODES) { state.complete = false; return }
        state.visited++
        val actionable = node.isClickable || node.isLongClickable || node.isEditable ||
            node.isCheckable || node.isScrollable
        val text = label(node)
        var childIndent = indent
        if ((actionable || text.isNotEmpty()) && node.isVisibleToUser) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            state.out.add(element(node, text, rect, indent, state.screen))
            state.held.add(AccessibilityNodeInfo.obtain(node))
            if (node.isScrollable) childIndent = indent + 1
        }
        for (i in 0 until node.childCount) {
            if (state.visited >= MAX_NODES) { state.complete = false; break }
            walk(node.getChild(i), state, childIndent)
        }
    }

    private fun element(node: AccessibilityNodeInfo, text: String, rect: Rect, indent: Int, screen: Rect): BgNode {
        val cls = node.className?.toString().orEmpty()
        val password = node.isPassword
        val hint = node.hintText?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val secret = BackgroundTree.isSensitiveLabel(text) ||
            BackgroundTree.isSensitiveLabel(description) || BackgroundTree.isSensitiveLabel(hint)
        val filled = !node.isShowingHintText && !node.text.isNullOrBlank()
        val shown = BackgroundTree.label(text, description.ifBlank { hint }, filled, password, secret)
        val states = ArrayList<String>()
        // l10n:keep privileged screen protocol
        if (node.isFocused) states.add("已聚焦")
        // l10n:keep privileged screen protocol
        if (node.isSelected) states.add("已选")
        // l10n:keep privileged screen protocol
        if (node.isCheckable) states.add(if (node.isChecked) "已勾选" else "未勾选")
        // l10n:keep privileged screen protocol
        if (!node.isEnabled) states.add("不可用")
        // l10n:keep privileged screen protocol
        if (node.isScrollable) states.add("可滚动")
        // l10n:keep privileged screen protocol
        if (password) states.add("密码·不可代填")
        var flags = 0
        if (node.isPassword) flags = flags or Proto.FLAG_PASSWORD
        if (secret) flags = flags or Proto.FLAG_SECRET
        if (node.isEditable) flags = flags or Proto.FLAG_EDITABLE
        if (node.isClickable) flags = flags or Proto.FLAG_CLICKABLE
        if (node.isLongClickable) flags = flags or Proto.FLAG_LONGCLICK
        if (node.isScrollable) flags = flags or Proto.FLAG_SCROLLABLE
        if (node.isFocused) flags = flags or Proto.FLAG_FOCUSED
        val viewId = node.viewIdResourceName
        if (SensitiveWords.confirmHit(shown, viewId) != null) flags = flags or Proto.FLAG_SENSITIVE
        val element = BgNode(role(node, cls), shown, states, flags, indent, rect,
            node.packageName?.toString(), viewId)
        val cy = (rect.top + rect.bottom) / 2 - screen.top
        element.region = when {
            screen.height() <= 0 -> 1
            cy < screen.height() / 3 -> 0
            cy < screen.height() * 2 / 3 -> 1
            else -> 2
        }
        return element
    }

    private fun role(node: AccessibilityNodeInfo, cls: String): String = when {
        // l10n:keep privileged screen protocol
        node.isEditable -> "输入框"
        // l10n:keep privileged screen protocol
        cls.contains("Switch") -> "开关"
        // l10n:keep privileged screen protocol
        node.isCheckable -> "勾选框"
        // l10n:keep privileged screen protocol
        cls.contains("Button") || node.isClickable && node.text != null -> "按钮"
        // l10n:keep privileged screen protocol
        cls.contains("ImageView") && node.isClickable -> "图片"
        // l10n:keep privileged screen protocol
        cls.contains("RecyclerView") || cls.contains("ListView") -> "列表"
        // l10n:keep privileged screen protocol
        node.isClickable -> "按钮"
        // l10n:keep privileged screen protocol
        else -> "文本"
    }

    private fun label(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        // Password fields never echo what was typed; the hint says what they are.
        if (node.isPassword) return node.hintText?.toString().orEmpty()
        if (text.isNotEmpty()) return text
        return node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
            ?: node.hintText?.toString().orEmpty()
    }

    private fun bundle(pkg: String, out: List<BgNode>, complete: Boolean, screen: Rect): Bundle {
        val n = out.size
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putInt(Proto.COUNT, n)
            putString(Proto.FG_PKG, pkg)
            putBoolean(Proto.PORTRAIT, screen.height() >= screen.width())
            putBoolean(Proto.POOR, !complete)
            putStringArray(Proto.A_ROLE, out.map { it.role }.toTypedArray())
            putStringArray(Proto.A_LABEL, out.map { it.label }.toTypedArray())
            putStringArray(Proto.A_VIEW_ID, out.map { it.viewId }.toTypedArray())
            putStringArray(Proto.A_STATES, out.map { it.states.joinToString("\u0001") }.toTypedArray())
            putIntArray(Proto.A_FLAGS, out.map { it.flags }.toIntArray())
            putIntArray(Proto.A_REGION, out.map { it.region }.toIntArray())
            putIntArray(Proto.A_INDENT, out.map { it.indent }.toIntArray())
        }
    }
}
