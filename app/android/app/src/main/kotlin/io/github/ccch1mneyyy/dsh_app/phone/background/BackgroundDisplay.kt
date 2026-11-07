package io.github.ccch1mneyyy.dsh_app.phone.background

import android.os.Build
import android.os.Bundle
import io.github.ccch1mneyyy.dsh_app.phone.ActionResult
import io.github.ccch1mneyyy.dsh_app.phone.Availability
import io.github.ccch1mneyyy.dsh_app.phone.CollectResult
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneDriver
import io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry
import io.github.ccch1mneyyy.dsh_app.phone.PhoneScreen
import io.github.ccch1mneyyy.dsh_app.phone.ScreenSnapshot
import io.github.ccch1mneyyy.dsh_app.phone.TargetInfo

/**
 * BACKGROUND [PhoneDriver] (screen ids `b<n>`): reads and drives the trusted virtual display
 * through whichever privileged channel is live (Shizuku or self-ADB). Registered in
 * [io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry].backgroundDriver only while a task uses it.
 * All calls are serialised by the bridge; this class never retries a dispatched action.
 */
class BackgroundDisplay(
    private val channelProvider: () -> PrivilegedChannel?,
    private val releaseChannelProvider: () -> PrivilegedChannel?,
    /** Whether this device may run the mode at all (the supported floor, or an explicit 13 opt-in). */
    private val androidOk: () -> Boolean = { BackgroundPolicy.supported(Build.VERSION.SDK_INT) },
) : PhoneDriver {

    override val screen: PhoneScreen = PhoneScreen.BACKGROUND

    @Volatile private var currentPackage: String? = null
    /** The engine's display id for this task, once a launch told us; -1 until then. */
    @Volatile private var displayId: Int? = null
    private var counter = 0
    private var lastScreenId: String? = null
    private var lastElements: List<Element> = emptyList()
    private var previousLabels: Set<String> = emptySet()

    override fun availability(): Availability {
        if (!androidOk())
            // l10n:keep agent screen protocol
            return Availability(false, "不打扰模式需要 Android 14 或更高版本")
        val channel = channelProvider()
        if (channel == null || !channel.alive())
            // l10n:keep agent screen protocol
            return Availability(false, "不打扰模式的特权通道未就绪")
        return Availability(true)
    }

    private fun channel(): PrivilegedChannel =
        // l10n:keep agent screen protocol
        channelProvider() ?: throw IllegalStateException("背景屏通道未就绪")

    override fun open(packageName: String): ActionResult {
        val request = Bundle().apply {
            putString(Proto.OP, Proto.OP_LAUNCH)
            putString(Proto.OWNER, "task")
            putString(Proto.PKG, packageName)
            putInt(Proto.SHORT_SIDE, 720)
            putInt(Proto.WANT_IMAGE, 0)
        }
        currentPackage = packageName
        val response = channel().execute(request)
        displayId = response.getInt(Proto.DISPLAY_ID, -1).takeIf { it > 0 } ?: displayId
        // l10n:keep agent screen protocol
        return toResult(response, "已在后台屏打开")
    }

    override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
        val pkg = currentPackage ?: return idleSnapshot()
        val request = Bundle().apply {
            putString(Proto.OP, Proto.OP_OBSERVE)
            putString(Proto.OWNER, "task")
            putString(Proto.PKG, pkg)
            putBoolean(Proto.DETAIL, detail)
            putBoolean(Proto.WANT_IMAGE, image)
        }
        val response = channel().execute(request)
        val code = response.getString(Proto.ERROR)
        // Below Android 14 the engine cannot read the tree at all; the app's own accessibility
        // service can, and it is the only reader available there.
        if (!response.getBoolean(Proto.OK) && code != null && code in TREE_FAILURES) {
            a11ySnapshot()?.let { return it }
        }
        requireObservation(
            response.getBoolean(Proto.OK), code,
            // A failed tree read is where "can this Android version read at all" must be answered.
            if (code != null && code in TREE_FAILURES) {
                listOfNotNull(
                    response.getString(Proto.ERROR_DETAIL),
                    "ime=${response.getInt(Proto.IME_POLICY, -1)}",
                    probe(),
                ).joinToString("; ").ifEmpty { null }
            } else response.getString(Proto.ERROR_DETAIL),
        )
        return snapshot(response, markNew = false)
    }

    /** One read through the app's accessibility service, or null when it has nothing to show. */
    private fun a11ySnapshot(): ScreenSnapshot? {
        val pkg = currentPackage ?: return null
        val id = displayId ?: BackgroundA11y.displayId().takeIf { it > 0 } ?: return null
        val read = runCatching { BackgroundA11y.read(id, pkg) }.getOrNull() ?: return null
        return snapshot(read, markNew = false)
    }

    /** The element's window left the background display: stop, never act on the person's screen. */
    private fun movedOffBackground(): ActionResult {
        BackgroundA11y.clear()
        lastElements = emptyList()
        lastScreenId = null
        // l10n:keep agent screen protocol
        return ActionResult(false, Reasons.of("displayTargetChanged"))
    }

    /** Whether the element is still on the display this task owns. */
    private fun onBackground(elementId: Int): Boolean {
        val id = displayId ?: return false
        return runCatching { BackgroundA11y.onDisplay(id, elementId) }.getOrDefault(false)
    }

    /** The accessibility read is the action here; the snapshot after it is the evidence. */
    private fun a11yResult(message: String): ActionResult {
        val snap = runCatching { observe(detail = false, image = false) }.getOrNull()
        return ActionResult(true, message, snap)
    }

    /** What the app's own accessibility service can see of the background display, or null. */
    private fun probe(): String? = runCatching { A11yDisplayProbe.describe(displayId, currentPackage) }.getOrNull()

    /** Failures that mean "the element tree could not be read", whatever the underlying cause. */
    private val TREE_FAILURES = setOf("treeUnavailable", "treeNeedsAndroid14", "textConnectionUnavailable")

    /** A failed read is not an empty snapshot; old element numbers are no longer actionable. */
    internal fun requireObservation(ok: Boolean, code: String?, detail: String?) {
        if (ok) return
        lastScreenId = null
        lastElements = emptyList()
        previousLabels = emptySet()
        throw IllegalStateException(Reasons.of(code, detail))
    }

    override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
        val seen = LinkedHashSet<String>()
        var pages = 0
        var stop = "listEnd"
        var last: ScreenSnapshot? = null
        while (pages < maxPages.coerceIn(1, 30)) {
            val snap = observe(detail = false, image = false)
            last = snap
            val before = seen.size
            for (e in lastElements) if (e.label.isNotEmpty()) seen.add(e.label)
            pages++
            if (until != null && seen.any { it.contains(until) }) { stop = "untilMatched"; break }
            if (seen.size == before) { stop = "listEnd"; break }
            if (pages >= maxPages) { stop = "pageLimit"; break }
            perform(PhoneAction.Scroll(screenId, listElementId, PhoneAction.Direction.DOWN))
        }
        return CollectResult(seen.toList(), pages, stop, last)
    }

    override fun target(screenId: String, elementId: Int): TargetInfo? {
        if (screenId != lastScreenId) return null
        val e = lastElements.getOrNull(elementId - 1) ?: return null
        return TargetInfo(
            screenId = screenId,
            elementId = elementId,
            role = e.role,
            label = e.label,
            packageName = e.packageName,
            appLabel = null,
            isPassword = e.isPassword,
            isSecretField = e.isSecretField,
            isEditable = e.isEditable,
            viewId = e.viewId,
        )
    }

    override fun perform(action: PhoneAction): ActionResult {
        // l10n:keep agent screen protocol
        val pkg = currentPackage ?: return ActionResult(false, "后台屏还没有打开应用")
        if (action is PhoneAction.Key && action.key != PhoneAction.GlobalKey.BACK) {
            // l10n:keep agent screen protocol
            return ActionResult(false, "后台屏不支持该系统键，未执行；请用返回或打开指定应用")
        }
        val request = Bundle().apply {
            putString(Proto.OWNER, "task")
            putString(Proto.PKG, pkg)
        }
        when (action) {
            is PhoneAction.Tap -> {
                // Where the engine has no tree, the service acts on the node it just read.
                if (BackgroundA11y.holds(action.elementId)) {
                    // The system moves the task to the default display as soon as a field wants the
                    // IME; acting on the stale node would drive the app on the person's own screen.
                    if (!onBackground(action.elementId)) return movedOffBackground()
                    // A click on an editor is that focus request: refuse it instead of relocating.
                    if (BackgroundA11y.isEditable(action.elementId)) {
                        // l10n:keep agent screen protocol
                        return ActionResult(false, "后台屏的输入框不能点（会弹到主屏），请直接用 type 写入这个元素")
                    }
                    if (runCatching { BackgroundA11y.click(action.elementId, action.long) }.getOrDefault(false)) {
                        // l10n:keep agent screen protocol
                        return a11yResult("已在后台屏执行")
                    }
                }
                request.putString(Proto.OP, Proto.OP_TAP)
                request.putInt(Proto.ELEMENT, action.elementId)
                request.putBoolean(Proto.LONG, action.long)
                fingerprint(action.elementId, request)
            }
            is PhoneAction.Type -> {
                val target = lastElements.getOrNull(action.elementId - 1)
                val secret = target != null && (target.isPassword || target.isSecretField) &&
                    !PhoneRegistry.unlocks().secretFields
                if (!secret && BackgroundA11y.holds(action.elementId) && !onBackground(action.elementId)) {
                    return movedOffBackground()
                }
                if (!secret && runCatching {
                        BackgroundA11y.setText(action.elementId, action.text, action.submit)
                    }.getOrDefault(false)) {
                    // l10n:keep agent screen protocol
                    return a11yResult("已在后台屏执行")
                }
                request.putString(Proto.OP, Proto.OP_TEXT)
                request.putInt(Proto.ELEMENT, action.elementId)
                fingerprint(action.elementId, request)
                request.putString(Proto.TEXT, action.text)
                request.putBoolean(Proto.SUBMIT, action.submit)
                // 解除限制 › 密码和验证码: the privileged side refuses such inputs unless told so.
                request.putBoolean(Proto.ALLOW_SECRET, PhoneRegistry.unlocks().secretFields)
            }
            is PhoneAction.Scroll -> {
                request.putString(Proto.OP, Proto.OP_SCROLL)
                request.putString(Proto.DIR, action.direction.name.lowercase())
            }
            is PhoneAction.Key -> {
                request.putString(Proto.OP, Proto.OP_KEY)
                request.putString(Proto.DIR, "back")
            }
            // l10n:keep agent screen protocol
            is PhoneAction.TapXY -> return ActionResult(false, "后台屏按元素编号操作，不用坐标")
        }
        // l10n:keep agent screen protocol
        return toResult(channel().execute(request), "已在后台屏执行")
    }

    override fun foregroundPackage(): String? = currentPackage

    fun release() {
        runCatching {
            // Cleanup may use an existing session, but must not connect or start a new process.
            releaseChannelProvider()?.execute(Bundle().apply {
                putString(Proto.OP, Proto.OP_RELEASE); putString(Proto.OWNER, "task")
            })
        }
        BackgroundA11y.clear()
        currentPackage = null
        lastScreenId = null
        lastElements = emptyList()
        previousLabels = emptySet()
    }

    private fun fingerprint(elementId: Int, request: Bundle) {
        val e = lastElements.getOrNull(elementId - 1) ?: return
        request.putString(Proto.EXPECT_ROLE, e.role)
        request.putString(Proto.EXPECT_LABEL, e.label)
        e.viewId?.let { request.putString(Proto.EXPECT_VIEW_ID, it) }
    }

    private fun toResult(response: Bundle, okMessage: String): ActionResult {
        val ok = response.getBoolean(Proto.OK)
        val message = if (ok) okMessage else Reasons.of(response.getString(Proto.ERROR), response.getString(Proto.ERROR_DETAIL))
        val snap = if (response.containsKey(Proto.COUNT) || response.getBoolean(Proto.SECURE))
            snapshot(response, markNew = true) else null
        return ActionResult(ok, message, snap)
    }

    private fun snapshot(response: Bundle, markNew: Boolean): ScreenSnapshot {
        val id = "b${++counter}"
        val pkg = response.getString(Proto.FG_PKG) ?: currentPackage
        val appLabel = response.getString(Proto.APP_LABEL)
        if (response.getBoolean(Proto.SECURE)) {
            lastScreenId = id; lastElements = emptyList(); previousLabels = emptySet()
            // l10n:keep agent screen protocol
            val text = "屏幕 $id · 后台屏 · 受保护页面（支付或密码），已交还用户"
            return ScreenSnapshot(id, text, appLabel, pkg, secure = true)
        }
        if (response.getBoolean(Proto.FRAME_NOT_READY)) {
            lastScreenId = id; lastElements = emptyList(); previousLabels = emptySet()
            // l10n:keep agent screen protocol
            val text = "屏幕 $id · 后台屏\n" + Reasons.of("frameNotReady")
            return ScreenSnapshot(id, text, appLabel, pkg, secure = false, poorTree = true)
        }
        val elements = buildElements(response, markNew)
        lastElements = elements
        lastScreenId = id
        val poor = response.getBoolean(Proto.POOR)
        val portrait = response.getBoolean(Proto.PORTRAIT, true)
        val keyboardUp = response.getBoolean(Proto.KEYBOARD_UP, false)
        var text = BackgroundTree.render(id, appLabel, portrait, keyboardUp, elements, background = true, poorTree = poor)
        if (response.getBoolean(Proto.IMAGE_OMITTED)) {
            // l10n:keep agent screen protocol
            text += "\n（本页含敏感输入或控件树未完整读取，已省略截图；可继续使用已脱敏的文字信息）"
        }
        previousLabels = elements.map { it.label }.filter { it.isNotEmpty() }.toSet()
        val image = if (response.getBoolean(Proto.IMAGE_OMITTED)) null else response.getByteArray("imageBytes")
        return ScreenSnapshot(id, text, appLabel, pkg, secure = false, poorTree = poor,
            image = image, imageMime = if (image == null) null else response.getString("imageMime"))
    }

    private fun buildElements(response: Bundle, markNew: Boolean): List<Element> {
        val n = response.getInt(Proto.COUNT, 0)
        val role = response.getStringArray(Proto.A_ROLE) ?: return emptyList()
        val label = response.getStringArray(Proto.A_LABEL) ?: return emptyList()
        val viewIds = response.getStringArray(Proto.A_VIEW_ID)
        val states = response.getStringArray(Proto.A_STATES) ?: arrayOfNulls(n)
        val flags = response.getIntArray(Proto.A_FLAGS) ?: IntArray(n)
        val region = response.getIntArray(Proto.A_REGION) ?: IntArray(n)
        val indent = response.getIntArray(Proto.A_INDENT) ?: IntArray(n)
        val out = ArrayList<Element>(n)
        for (i in 0 until n) {
            val lbl = label[i].orEmpty()
            val f = flags.getOrElse(i) { 0 }
            out.add(
                Element(
                    id = i + 1,
                    role = role[i].orEmpty(),
                    label = lbl,
                    region = regionOf(region.getOrElse(i) { 1 }),
                    indent = indent.getOrElse(i) { 0 },
                    states = states[i]?.split('\u0001')?.filter { it.isNotEmpty() } ?: emptyList(),
                    sensitive = f and Proto.FLAG_SENSITIVE != 0,
                    isNew = markNew && lbl.isNotEmpty() && lbl !in previousLabels,
                    isPassword = f and Proto.FLAG_PASSWORD != 0,
                    isSecretField = f and Proto.FLAG_SECRET != 0,
                    isEditable = f and Proto.FLAG_EDITABLE != 0,
                    viewId = viewIds?.getOrNull(i),
                ),
            )
        }
        return out
    }

    private fun regionOf(code: Int): Region = when (code) {
        0 -> Region.TOP
        2 -> Region.BOTTOM
        else -> Region.MIDDLE
    }

    private fun idleSnapshot(): ScreenSnapshot {
        val id = "b${++counter}"
        lastScreenId = id; lastElements = emptyList()
        // l10n:keep agent screen protocol
        return ScreenSnapshot(id, "屏幕 $id · 后台屏 · 空闲，用 open 打开应用", null, null)
    }
}
