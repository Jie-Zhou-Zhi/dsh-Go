package io.github.ccch1mneyyy.dsh_app.phone.background

// Pure decision logic for 不打扰模式: display sizing, the hard-gate on which
// screens/windows must be handed back, the sensitive-action test, and what each
// Android version can actually do. No Android types, so it is unit-tested on the JVM
// and shared by the engine and the driver.

object BackgroundPolicy {
    /** Full support: a trusted virtual display with its own focus (TRUSTED + OWN_FOCUS are 14+). */
    const val FULL_SDK = 34

    /**
     * Lowest Android worth attempting: PUBLIC/SUPPORTS_TOUCH virtual displays exist far earlier,
     * but trusted displays and a display-bound UiAutomation do not, so 13 is an experiment until a
     * device says otherwise. Nothing here claims 13 works; [Profile] reports each part separately.
     */
    const val EXPERIMENTAL_SDK = 33

    /** How far one part of the mode gets on a given Android version. */
    enum class Support { NONE, EXPERIMENTAL, FULL }

    /** Per-version capability split: the UI and the model both say which part is missing. */
    data class Profile(
        val sdk: Int,
        val display: Support,
        val launch: Support,
        val inject: Support,
        val tree: Support,
        val ime: Support,
    ) {
        /** True only when every part is fully supported; experimental never counts as supported. */
        val supported: Boolean
            get() = display == Support.FULL && launch == Support.FULL &&
                inject == Support.FULL && tree == Support.FULL
    }

    fun profile(sdk: Int): Profile = when {
        sdk >= FULL_SDK -> Profile(sdk, Support.FULL, Support.FULL, Support.FULL, Support.FULL, Support.FULL)
        // displayImePolicy is Android 11+; the rest stays experimental until a device proves it.
        sdk >= EXPERIMENTAL_SDK -> Profile(
            sdk, Support.EXPERIMENTAL, Support.EXPERIMENTAL, Support.EXPERIMENTAL,
            Support.EXPERIMENTAL, Support.FULL,
        )
        else -> Profile(sdk, Support.NONE, Support.NONE, Support.NONE, Support.NONE, Support.NONE)
    }

    fun supported(sdk: Int): Boolean = profile(sdk).supported

    /** Whether a probe may run here at all: the gate is what turns an experiment into facts. */
    fun canProbe(sdk: Int): Boolean = sdk >= EXPERIMENTAL_SDK

    /**
     * The virtual-display flags to request on this Android version, by name.
     *
     * Only fields that exist are ever asked for: folding a whole list and failing on the first
     * absent name is how the 14-only flags used to reject every older device outright. The
     * caller resolves these reflectively and still fails loudly when a required one is missing.
     */
    fun flagNames(sdk: Int): List<String> =
        BASE_FLAGS + if (sdk >= FULL_SDK) TRUSTED_FLAGS else emptyList()

    /** Flags every supported display needs: content stays on the display, touch, cleanup. */
    private val BASE_FLAGS = listOf(
        "PUBLIC", "OWN_CONTENT_ONLY", "SUPPORTS_TOUCH", "DESTROY_CONTENT_ON_REMOVAL",
    )

    /** 14+ only: trusted (apps may be launched on it), own input focus, no focus theft. */
    private val TRUSTED_FLAGS = listOf("TRUSTED", "OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED")

    /**
     * Phone-shaped size: the requested short side, rounded to an even number, with the long side
     * from the real aspect ratio (not Phase's fixed 720×1280, which letterboxes 20:9 phones).
     * Returns {width, height} in the display's natural (portrait) orientation.
     */
    fun displaySize(shortSide: Int, aspectW: Int, aspectH: Int): IntArray {
        val short = (shortSide / 2 * 2).coerceIn(360, 1080)
        val w = minOf(aspectW, aspectH).coerceAtLeast(1)
        val h = maxOf(aspectW, aspectH).coerceAtLeast(1)
        var long = (short.toLong() * h / w).toInt()
        long = (long / 2 * 2).coerceIn(short, short * 3)
        return intArrayOf(short, long)
    }

    /** True when this window must be handed back to the user rather than driven. */
    fun mustHandBack(secure: Boolean, locked: Boolean): Boolean = secure || locked

    /** Whether an action on an element needs confirmation, from its flags + label. */
    fun needsConfirm(flags: Int, label: String, submit: Boolean, firstInApp: Boolean): Boolean {
        if (firstInApp) return true
        if (submit) return true
        if (flags and Proto.FLAG_SENSITIVE != 0) return true
        return BackgroundTree.isSensitiveLabel(label)
    }
}
