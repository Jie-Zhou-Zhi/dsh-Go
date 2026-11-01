package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The rules that decide whether an unreadable page is a filtered (sensitive) one, and what
// the model is told about it. Pure data: no Android types, so this runs on the JVM.
class SensitiveViewTest {
    private fun node(
        text: String? = null,
        desc: String? = null,
        sensitive: Boolean = false,
        children: List<UiNode> = emptyList(),
    ) = UiNode(text = text, desc = desc, bounds = Box(0, 0, 10, 10), dataSensitive = sensitive, children = children)

    private fun window(root: UiNode?) =
        UiWindow(1, WindowKind.APP, "com.example", null, Box(0, 0, 1080, 2400), root)

    @Test
    fun `data-sensitive nodes mean a filtered page on android 14`() {
        val windows = listOf(window(node(children = listOf(node(text = "…", sensitive = true)))))
        assertTrue(SensitiveView.looksFiltered(34, windows))
    }

    @Test
    fun `the flag does not exist before android 14`() {
        val windows = listOf(window(node(sensitive = true)))
        assertFalse(SensitiveView.looksFiltered(33, windows))
    }

    @Test
    fun `an ordinary page is neither filtered nor unreadable`() {
        val windows = listOf(
            window(
                node(
                    text = "设置",
                    children = listOf(node(text = "WLAN"), node(text = "蓝牙"), node(text = "显示"), node(text = "电池"), node(text = "存储")),
                ),
            ),
        )
        assertFalse(SensitiveView.looksFiltered(34, windows))
        assertFalse(SensitiveView.unreadable(windows))
        assertEquals(null, SensitiveView.note(false, windows))
    }

    @Test
    fun `an unreadable page is named sensitive first, not blamed on games`() {
        val windows = listOf(window(node(text = "一")))
        assertTrue(SensitiveView.unreadable(windows))
        assertEquals(SensitiveView.NOTE_UNREADABLE, SensitiveView.note(false, windows))
        assertEquals(SensitiveView.NOTE_FILTERED, SensitiveView.note(true, windows))
    }

    @Test
    fun `a window without a root is unreadable`() {
        val windows = listOf(window(null))
        assertTrue(SensitiveView.unreadable(windows))
        assertEquals(SensitiveView.NOTE_UNREADABLE, SensitiveView.note(false, windows))
    }

    @Test
    fun `every refusal says what to do instead`() {
        assertTrue(SensitiveView.REFUSED_TAP.contains("handoff"))
        assertTrue(SensitiveView.REFUSED_SWIPE.contains("handoff"))
        assertEquals("动作已提交，但当前无法验证结果。", SensitiveView.CANNOT_VERIFY)
    }
}
