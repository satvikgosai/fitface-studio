package dev.fitface.studio.core.delivery

/**
 * Which of the watch's companion apps this phone actually has.
 *
 * There is no single answer to "is the companion app installed", because it does not
 * have a single package name. The store ships it under two ids split by device class —
 * entry-level models (SM-A107M, SM-A115M) are served `watchmanager2` and refused
 * `watchmanager`, mainstream models the reverse — both carrying the same label and the
 * same launchable setup activity. Two further ids exist on real phones: the firmware
 * preload that fronts whichever of the two applies, and the retired host manager that
 * predates them.
 *
 * Checking only the first of those told a reporter whose watch was paired, connected and
 * transferring that their companion app was not installed. So this is a list, matched in
 * order, and the answer is the first id present rather than a boolean over one name.
 *
 * The order is deliberate: the two real apps first because they are the ones that can be
 * launched and are what a reader means by the companion app, then the preload stub, then
 * the retired ids last so they only ever act as a label of last resort.
 *
 * None of this gates anything. The companion app carries no accessory code at all — no
 * `REGISTER_AGENT` receiver, no `AccessoryServicesLocation` — so its presence says nothing
 * about whether the channel can open; the stock plugin is the app that owns the channel.
 * What this resolution is for is naming the app in the UI and finding something to open
 * when the reader is told to go and connect the watch.
 */
internal object CompanionResolution {

    /**
     * Companion-app ids, most preferred first.
     *
     * Every one of these is attested in Samsung's own shipping code rather than guessed:
     * the first is the plugin's `AccessoryConstants.TUHM_PACKAGE_NAME`, the second and
     * last come from the plugin's `ExcludeAppList`, and the stub and the old host manager
     * are `GlobalConst.PACKAGE_NAME_WM_STUB` and
     * `GlobalConst.PACKAGE_NAME_OLD_UNIFIED_HOST_MANAGER`.
     */
    val COMPANION_PACKAGES = listOf(
        "com.samsung.android.app.watchmanager",
        "com.samsung.android.app.watchmanager2",
        "com.samsung.android.app.watchmanagerstub",
        "com.samsung.android.hostmanager.app",
        "com.samsung.android.hostmanager",
    )

    /**
     * The preferred companion id out of [installed], or null when this phone has none.
     *
     * [installed] is the subset of [COMPANION_PACKAGES] that resolved through the package
     * manager, so a caller that cannot see a package — because it is absent, disabled, or
     * filtered — simply leaves it out.
     */
    fun preferred(installed: Set<String>): String? =
        COMPANION_PACKAGES.firstOrNull { it in installed }
}
