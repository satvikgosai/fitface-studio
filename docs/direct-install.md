# Direct install

The validated BIN travels through accessory discovery and Bluetooth RFCOMM/SPP.
The app does not impersonate the stock plugin. [Architecture](architecture.md#invariants)
owns validation and storage boundaries; this reference owns transport and recovery.

## Discovery

The watch reserves stock local component IDs `6` and `30`. FitFace Studio does
not impersonate them: its accessory profiles omit a local `serviceId`, which lets
the framework assign dynamic IDs while discovery targets the two peers by path.

```text
/system/WatchfaceSerevice
/system/OtaTransferAgent
```

(The misspelling is the watch's, not a typo here.)

## The transfer

1. Recheck payload size and SHA-256.
2. Send dynamic metadata through the OTA Accessory peer.
3. Obtain Bluetooth transfer access and open RFCOMM/SPP.
4. Negotiate `30/300`.
5. Send `/user/wf/<canonical filename>` in the descriptor accepted with `330`.
6. Stream 39,600-byte windows in 960-byte chunks with CRC32.
7. Accept `310`, retry bounded `311`, require `320`.
8. Finish with `32/320`; on the success path the watch queues the completed
   `/user/wf` path for extraction and manager registration, then close with
   `34/340`.
9. Send command 4 (`INSTALL_BANDFACE`) to finalize the face/style pair the watch
   has now registered.

No finalization request is sent unless the complete payload reaches the verified
close state. Unpacking and registration are the watch's own work, not something
this command asks for: a successful `32` queues the completed `/user/wf` path
before it answers `320`, so `320` proves the work was queued and not that it has
finished. The final five-byte message is command 4, which is neither the unpack
step nor the current-face selector — command 3 is what selects the current face.

**The result the watch computes for that command does not come back to this app.**
It is addressed to the fixed component the stock plugin owns, so the app's honest
ceiling is "the install request was delivered", which is what the Install page
says. The watch distinguishes success, low battery, a wrong path, a wrong binary
format, storage full and a full favourites list; none of those reach the phone
here, which is exactly why a face that transfers cleanly and never appears has to
be diagnosed from the watch rather than from the app.

## Channel handover

Discovery needs the stock plugin connected; transfer needs its channel released.
Cache both peers before release. Android 12+ revocation of the plugin's **Nearby
devices** permission is read through the package manager. Below API 31,
`pluginNearbyGranted` is null and step 4 needs explicit acknowledgement of freezing
(as described below). Companion disconnect and manual force-stop did **not** release
the channel on tested hardware.

Keep API-specific wording in `hasPluginNearbySwitch` (`EditorScreen.kt`) and
`Fit3DirectInstaller.restorePlugin`; other user-facing strings must not independently
prescribe Nearby access, freezing tools or adb. Discovery without a connected plugin,
including a silent watchdog expiry, is recoverable `NEEDS_WATCH_CONNECTION`, not
`FAILED`. Agent initialization failure is `NEEDS_PLUGIN` via the discovery listener's
`agent_error`, not `requestAgent`'s callback.

Starting discovery clears earlier release acknowledgement. Step 4 requires cached
peers even if permission was revoked prematurely. Companion/app-settings shortcuts
remain available during discovery recovery; discovery itself has one action in step 3.
After an edit, `payloadChanged()` returns `COMPLETE`/`FAILED` to `READY`, retaining
usable peers. Restore the plugin and reconnect after every send attempt.

### Android 11 and earlier: freezing the plugin

Android 12 introduced the runtime `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`
permissions that Settings shows as **Nearby devices**. Older phones hold the
install-time `BLUETOOTH`/`BLUETOOTH_ADMIN` pair instead, which cannot be revoked
per app, so there is nothing to switch off for step 4 and the plugin has to be
stopped outright. The four steps themselves do not change; this is only what the
reader does between step 3 and step 4.

**This route is unverified.** No face has been sent to a watch this way. A manual
force stop and a companion-app disconnect were both tried on hardware and neither
released the channel, so freezing the plugin is a reason to *attempt* the
transfer, not evidence that the channel is free. Know how to bring the plugin back
before freezing it.

Once, to set the tools up:

1. Install [Shizuku](https://shizuku.rikka.app/) and
   [Hail](https://github.com/aistra0528/Hail).
2. Start Shizuku. On **Android 11** that is its wireless-debugging flow: turn on
   Developer options, USB debugging and Wireless debugging, pair Shizuku with the
   system pairing code, then start it. On **Android 10 and earlier** it needs a
   computer with
   [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools):
   turn on USB debugging, connect the unlocked phone, approve its prompt, confirm
   with `adb devices`, then run the command Shizuku's own start screen gives — for
   v11.2.0 and later that is

   ```text
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
   ```

   Either way Shizuku stops at reboot and has to be started again.
3. In Hail, set the working mode to **Shizuku (adb)** and the freeze action to
   **Disable**, then add the watch plugin (`com.samsung.wearable.fit3plugin`).
   Not **Suspend**: Hail's own documentation says suspending stops the user
   interacting with an app and does *not* stop it running in the background, which
   is the half that matters here. Hail also unfreezes only through the working mode
   that froze the app, so the mode has to be the same on the way back.

Then, for each attempt:

1. Start Shizuku, unfreeze the plugin in Hail, and connect the watch in the
   companion app.
2. Complete steps 1-3 in FitFace Studio and wait until both peers are cached.
3. Switch to Hail and freeze the plugin — only the plugin, and without unpairing
   the watch.
4. Return to FitFace Studio, confirm step 4 by hand, and send.
5. However it ends, unfreeze the plugin in Hail, then reconnect the watch in the
   companion app.

With a computer already attached, `adb` does the same thing without Hail. The
second command is not optional; the phone has no watch plugin until it is run:

```text
adb shell pm disable-user --user 0 com.samsung.wearable.fit3plugin
adb shell pm enable --user 0 com.samsung.wearable.fit3plugin
```

Do not disable the accessory framework or the companion app, and do not uninstall
or unpair anything — the framework is what serves the channel the transfer is
about to use. Shizuku's [setup guide](https://shizuku.rikka.app/guide/setup/) is
the authority on which start method applies to which Android version and on the
restart-after-reboot limitation, and Hail's
[README](https://github.com/aistra0528/Hail) on which freeze actions each working
mode supports.

## Recovering after the handover

A peer handle belongs to the connection that created it. `rewoundToDiscovery()`
drops peers and release acknowledgement, retains environment/permissions and failure
text. `restartDiscovery()` also clears each agent's cache and abandons in-flight
transfer/install. This is not a full `reset()` or another environment probe.

| Trigger | Response |
| --- | --- |
| Missing cached peer or accessory send throws (`peerLost`) | Automatically rewind to discovery |
| Ambiguous RFCOMM/protocol/timeout failure | Offer “Reconnect the watch and discover again” alongside “Try again” |
| Install preflight finds a missing peer or unreleased channel | Regress to the missing setup step, not a failed transfer |

### Cancellation and progress

`cancelTransfer()` advances an attempt token, closes the socket and interrupts the
worker without joining it on the main thread. Blocking I/O and teardown can keep that
worker alive; abandonment must make it unable to send or publish afterward.

- `OtaTransferDeliveryAgent` compares the attempt token in listeners, SPP polls and
  delayed handlers. Never reuse a boolean the next attempt can clear.
- `WatchfaceDeliveryAgent.cancelInstall()` clears pending payload so a late `onSent`
  cannot finalize it.
- `DeliveryProgress.accepts` runs **inside** the installer's atomic state update;
  a callback may advance only the phase waiting for it.
- The watchdog calls `abandonInFlight()` before recording timeout. Otherwise late
  progress/completion can resurrect a failed transfer or overwrite the rewind.

### Silence watchdog

The 20-second watchdog measures silence, not total transfer duration. Each bounded
wait in `runTransferStateMachine` reports progress and `onTransferStatus` re-arms it;
status callbacks must not replace the separate VERIFYING watchdog.

| Valid protocol stretch | Wait budget without intermediate reports |
| --- | --- |
| Opening negotiation, descriptor, first window | 8 + 8 + 12 seconds |
| One window with `MAX_WINDOW_RETRIES=3`, inclusive `0..3` | 4 × 12 = 48 seconds |
| Verification, pause, close, teardown, completion post | 15 + 0.25 + 8 + 0.5 + 1 = 24.75 seconds |

Report after **each wait**, rather than enlarging the watchdog to cover these totals.
`SppResponseWait` already bounds individual waits. Keep `TRANSFER_PROGRESS_GAPS`
with pure decisions outside SDK companions: reading non-constant companion state can
load unverifiable accessory bytecode in JVM tests. `TransferWatchdogBudgetTest` checks
the arithmetic; it cannot instantiate `SAAgentV2` and prove each report is emitted.

## Environment is advisory

`CompanionResolution` considers mainstream `com.samsung.android.app.watchmanager`,
entry-level `com.samsung.android.app.watchmanager2` (observed SM-A107M/SM-A115M),
preload `com.samsung.android.app.watchmanagerstub`, and retired
`com.samsung.android.hostmanager.app`. Complementary distribution means a single
package-name gate can reject a working phone.

The companion is a setup shell, not the accessory provider: neither active build
declares `REGISTER_AGENT` or `AccessoryServicesLocation`. The stock plugin owns the
channel and host stack; on non-vendor phones it also carries the framework installer.
Probe `com.samsung.accessory.action.REGISTER_AGENT` capability, not a fixed package
list. `EnvironmentAdvisory` must never gate discovery; the attempt determines support.

The plugin's activities are unexported, so opening the companion walks launchable
candidates then falls back to plugin app-info. Retain `com.samsung.accessory` in
`<queries>`: the SDK itself looks it up by name and otherwise reports
`LIBRARY_NOT_INSTALLED`. Avoid `SA.initialize` merely for probing; it sends usage data.

## Accessory SDK dependencies

Both proprietary JARs are required by `:core:delivery`; fetch, hashes and rights are
in [libs/README.md](../libs/README.md). The base JAR supplies `SsdkInterface`,
`SsdkUnsupportedException` and `SsdkVendorCheck`. Removing it causes reflected agent
constructor error 2563 / nested `NoClassDefFoundError`; the ABI test pins that surface.

## Permissions and security posture

The merged manifest requests legacy or modern Bluetooth permissions as appropriate,
notifications, connected-device foreground service and normal `ACCESSORY_FRAMEWORK`.
Internet serves catalogue/previews, selected packages and app updates; bounds and
allowlists are documented in [Architecture](architecture.md#invariants).

The app requests no `CONTROL_WEARABLE_STATUS`, binds no HostManager, patches no
plugin, replaces no reserved ID, and does not integrate Shizuku/root or intercept
another app's traffic. Legacy freezing is performed by the user in a separate tool.
Keep the plugin installed; release only after peers are cached. If the watch disconnects,
the companion becomes unstable or protocol state diverges, restore the plugin before
recovery. Never disable the accessory framework or unpair as part of handover.

## Unverified

Bluetooth delivery is device-proven on an SM-R390. Timeout recovery and deliberately
slow-but-healthy transfers have not been staged on a watch. `TimeoutRecoveryTest`
checks decisions and `TransferWatchdogBudgetTest` checks budgets; manually maintained
progress gaps are not proof of runtime callback placement. The Android 11-and-earlier
freezing route remains unverified too.

Structural validity does not establish firmware acceptance, battery/storage policy
or favourites capacity. Final watch results target the stock component, so the UI
reports **Request sent** and asks the user to inspect the watch, not “installed”.
