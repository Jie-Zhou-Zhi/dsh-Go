package io.github.ccch1mneyyy.dsh_app.phone.background

import android.view.accessibility.AccessibilityWindowInfo
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService

/**
 * Read-only answer to one question: can the app's own accessibility service see the background
 * display at all? Below Android 14 the engine's display-bound UiAutomation does not exist, so this
 * service is the only candidate reader — and whether the system reports a virtual display's windows
 * to it is not something the source can promise.
 *
 * Content-free: display ids, how many application windows sit on the background display, and whether
 * the target package is one of them. No node text, no titles, no screenshots.
 */
internal object A11yDisplayProbe {
    fun describe(displayId: Int?, target: String?): String {
        val service = PhoneA11yService.instance ?: return "a11y=off"
        val all = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return "a11y=unavailable"
        val ids = ArrayList<Int>()
        var windowsOnBackground = 0
        var targetSeen = false
        for (index in 0 until all.size()) {
            val id = all.keyAt(index)
            ids.add(id)
            val windows = all.valueAt(index).orEmpty()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (displayId == null || id != displayId) continue
            windowsOnBackground = windows.size
            if (target != null) {
                targetSeen = windows.any { window ->
                    runCatching { window.root?.packageName?.toString() == target }.getOrDefault(false)
                }
            }
        }
        return "a11y=displays[${ids.joinToString(",")}] bg=$displayId windows=$windowsOnBackground " +
            "target=${if (targetSeen) "yes" else "no"}"
    }
}
