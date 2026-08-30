# Direct install

How the validated BIN reaches a paired watch, and what the app deliberately does
not do to get there.

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
5. Send `/user/wf/<canonical filename>` in descriptor `330`.
6. Stream 39,600-byte windows in 960-byte chunks with CRC32.
7. Accept `310`, retry bounded `311`, require `320`.
8. Close with `32/320` and require `34/340`.
9. Send the final watch-face install request.

No install command is sent unless the complete payload reaches the verified close
state.

## The channel handover users get stuck on

Discovery needs the stock plugin's channel; the transfer needs it released. Those
are opposite requirements, in that order, and it is the single most confusing part
of the flow.

Peers stay cached once discovered, so the plugin only has to let go afterwards —
and how it lets go depends on the phone. On Android 12 and later, revoking the
plugin's **Nearby devices** access frees the channel, and this app reads that back
through the package manager, so step 4 completes on its own. Below API 31 those
permissions are not runtime permissions at all: there is no per-app switch to read
or to revoke, `pluginNearbyGranted` is null on every such phone, and step 4 can
only ever be the user's acknowledgement. What that acknowledgement stands for is
[freezing the plugin](#android-11-and-earlier-freezing-the-plugin), below.

**Disconnecting the watch in the companion app does not free the channel.** It was
offered as the easy alternative on both routes and has been withdrawn: neither it
nor a manual force stop released the channel on tested hardware. Every string that
named it is gone.

The wording splits on API 31 in exactly two places — `hasPluginNearbySwitch` in
`EditorScreen.kt` for the checklist, `Fit3DirectInstaller.restorePlugin` for the
state messages — and nothing else may name a Nearby switch, a freezing tool or
`adb` outside them. A reader who does not know which Android version they are on
cannot be asked to work out which half of a sentence applies to them, and telling
them to revoke a permission their phone does not offer is the same failure as
telling a modern phone to go and install Shizuku.

Discovery without the plugin connected must land in the recoverable
`NEEDS_WATCH_CONNECTION`, never in `FAILED`. That covers the explicit
`device_not_connected` outcome and a silent discovery watchdog timeout, which
means the same thing.

A committed edit invalidates a finished transfer, so `payloadChanged()` re-arms
`COMPLETE` and `FAILED` back to `READY` while keeping the cached peers. Without
it the Install page offers nothing but "Back to canvas" after the first install.

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

The handover is one-way in the UI unless something rewinds it, and that is the
trap: a peer handle does not outlive the connection it was found on, so a
transfer that fails *after* the plugin let go can only be retried by reconnecting
the watch, discovering again, and handing the channel over again. `peersCached`
never went false, so `setupComplete` stayed true, so the Install page stayed on
the transfer panel offering a re-send that could not work — the checklist step
the user needed was unreachable.

`DirectInstallState.rewoundToDiscovery()` is the way back. It drops both peer
flags, drops the release acknowledgement, keeps the probed environment and the
granted permissions, and keeps the failure text in `failure` so the checklist can
say why it rewound. `Fit3DirectInstaller.restartDiscovery()` wraps it, also
clearing each agent's cached `SAPeerAgent` and abandoning any transfer or install
in flight. It is not `reset()`: nothing the phone told us about itself is re-probed.

**Abandoned is not stopped, and the difference is the whole of the next section.**
`cancelTransfer()` bumps an attempt token, closes the socket and interrupts the
worker; it does not wait for it. It cannot: the worker is blocking on RFCOMM with
half a second of teardown sleep behind it, and `reset()` is called from the main
thread, so joining would freeze the UI for exactly as long as the failure the
reader is trying to leave. So a rewind routinely leaves a live thread behind, and
everything below is about that thread being unable to do any harm.

Three things reach it:

- **Automatically**, when a failure is unambiguously peer-level — a missing
  cached peer, or an accessory `send` that throws. Those report
  `peerLost = true` and re-sending against the same handle cannot work.
- **The user**, through "Reconnect the watch and discover again" on the failure
  panel, for failures that could go either way (RFCOMM, protocol, timeout).
  "Try again" stays alongside it: a watch can reject a face for reasons that
  have nothing to do with the transport.
- **`install()`'s pre-flight**, which regresses to the step that came undone
  instead of failing. Neither a missing peer nor an unreleased channel is a
  failed transfer.

An abandoned attempt cannot speak. Two mechanisms, because each covers what the
other cannot:

- **The attempt token, in the agents.** `OtaTransferDeliveryAgent` stamps every
  attempt, and every listener call, every poll of the SPP response wait and both
  of its delayed handler callbacks compare against the one they started with.
  This replaced a single `transferAborted` flag that the *next* attempt cleared,
  so an abandoned worker that had not noticed yet quietly un-aborted itself.
  `WatchfaceDeliveryAgent.cancelInstall()` does the same by dropping the pending
  payload, which is what a late accessory `onSent` then finds missing.
- **The phase gate, in the installer.** `DeliveryProgress.accepts` decides whether
  a callback may still change the state at all, inside the atomic update so a
  worker thread cannot read one phase and write against another. A callback may
  only move the machine on from the phase that was waiting for it.

The watchdog uses both: `armWatchdog` calls `abandonInFlight()` before it writes
the timeout, because giving up has to stop the work and not merely describe it.
Three things went wrong when it did not, and all three were reachable on
arithmetic rather than on bad luck — `MAX_WINDOW_RETRIES` is 3 over a `0..3` loop,
so one window can spend four `WINDOW_TIMEOUT_MS` waits, 48 s against a 20 s
watchdog:

- an acknowledged window dragged `FAILED` back to `TRANSFERRING`, and the transfer
  went on to report success;
- a late `onSent` turned an install timeout into `COMPLETE` — a face the watch
  never got, reported as installed;
- and tapping **Reconnect the watch and discover again** made the abandoned worker
  throw within milliseconds, so its failure landed just *after* the rewind and put
  the page straight back into `FAILED`. That left `reset()` — the whole four-step
  setup — as the only way out of a transfer that had timed out.

**That same arithmetic then pointed the other way.** Once the timeout actually
stops the work, a budget the protocol can legitimately exceed no longer produces a
cosmetic lie — it destroys a transfer the watch has accepted. The token the
watchdog bumps discards the queued `onTransferComplete`, so the install command is
never sent and a verified install reports as a timeout. Three stretches of a
*healthy* transfer are longer than 20 s: the opening handshake (8 s negotiation,
8 s descriptor, 12 s first window, none of which reported anything), one window
re-sent four times at 12 s, and the tail after the last window — 15 s of BIN
verification, a 250 ms pause, an 8 s close handshake, 500 ms of teardown and a 1 s
completion post, 24.75 s in all.

So the watchdog is a **silence threshold, not a duration**: every wait in
`runTransferStateMachine` now ends in a report, `onTransferStatus` re-arms, and the
budget is kept as `TRANSFER_PROGRESS_GAPS` beside the pure decision that reads it.
Raising the constant instead would have been the wrong repair — `SppResponseWait`
already bounds every individual wait, so what this watchdog guards is the gaps
between them, and stretching it to cover 48 s only delays noticing a watch that
really has stopped answering.

Two rules keep the rewound state honest:

- **Starting discovery voids an earlier release acknowledgement.** Discovery only
  works while the plugin holds the watch, so running it proves the channel is
  held again. `discovering()` clears the flag; without that, a second install
  would start against a channel the plugin never let go of the second time.
- **The handover step is not done before there are peers.** Revoking the
  plugin's Nearby access *before* discovery satisfies `pluginChannelReleased`
  but is not progress, and step 4 used to read as complete while the step it
  depends on was still pending.

While discovery is the outstanding step the checklist also offers direct
shortcuts into the companion app and the plugin's app settings. Step 4 owns that
second shortcut, and step 4 is blocked while step 3 is undone — which is exactly
where a rewound setup lands, with the plugin's access still switched off.

Those two are shortcuts into the phone's settings, and they are labelled as such.
Discovery itself is started from step 3's own row and nowhere else: a duplicate
"Discover the peers" button used to sit beneath the checklist, so the page offered
the same action twice with nothing to distinguish the copies.

## What the phone has, and why it is not a gate

The checklist's first step reports what was found. It does not decide anything, and three
findings are why.

**The companion app has no single package name.** It is distributed under
`com.samsung.android.app.watchmanager` for mainstream models and
`com.samsung.android.app.watchmanager2` for the entry-level ones, and the two are
complementary — the store serves `watchmanager2` to an SM-A107M or SM-A115M and refuses
`watchmanager` for exactly those models, with the same version, the same label and the same
launchable setup activity in both. `com.samsung.android.app.watchmanagerstub` is the
firmware preload that fronts whichever applies, and `com.samsung.android.hostmanager.app`
is the retired predecessor. `CompanionResolution` matches all of them in order.

**The companion app carries no accessory code.** Neither `watchmanager` build declares a
`com.samsung.accessory.action.REGISTER_AGENT` receiver or `AccessoryServicesLocation`
meta-data; it is a launcher and setup shell that installs per-device plugins. The stock
plugin is the app that owns the channel — it declares those receivers, carries the whole
host-manager stack and its own setup wizard, and on a non-Samsung phone it even carries the
accessory framework as an install payload for the companion app to install. So a present
companion app is no evidence that a channel can open, and an absent one is no evidence that
it cannot.

**Only the attempt knows.** `CompanionEnvironment` therefore exposes an
`EnvironmentAdvisory` and nothing that gates: discovery is the arbiter. An agent that will
not initialize lands in the recoverable `NEEDS_PLUGIN`, and it arrives through the discovery
listener's `agent_error` outcome rather than `requestAgent`'s own callback. The plugin has
no launchable activity, so `openCompanionApp` walks the companion list for something
launchable and the page falls back to the plugin's app-info screen.

`com.samsung.accessory` must stay declared in `<queries>` regardless of any of this: the
SDK reaches the framework by name, so without the declaration it reports
`LIBRARY_NOT_INSTALLED` on a phone that has it.

## Accessory SDK dependencies

Accessory SDK 2.6.4 references three types supplied by `sdk-v1.0.0.jar`:

- `SsdkInterface`
- `SsdkUnsupportedException`
- `SsdkVendorCheck`

Both JARs live in `libs/` and are consumed only by `:core:delivery`. Removing the
base JAR recreates constructor error 2563, usually wrapped in
`NoClassDefFoundError`; a JVM ABI test guards the required surface. Neither is
committed — see [`libs/README.md`](../libs/README.md).

## Permissions and security posture

The merged manifest requests legacy or modern Bluetooth permissions as
appropriate, notification and connected-device foreground-service permissions,
and the normal `ACCESSORY_FRAMEWORK` permission. Internet access is used only for
catalogue previews, catalogue requests and selected package downloads.

Package downloads are bounded to 32 MiB, must exactly match the catalogue's
declared size, and must resolve to HTTPS store hosts. XML parsing disables
external entities, and a downloaded package still passes the format layer's
face-ID and container validation before an editing session opens.

The app does **not** request `CONTROL_WEARABLE_STATUS`, bind HostManager, patch
the stock plugin, replace reserved IDs, use Shizuku or root, or intercept another
app's network traffic. The stock plugin is kept installed and is
permission-isolated only after peer handles are cached. That holds for the
Android 11 route too: freezing the plugin is the reader driving a separate tool of
their own, and nothing in this app talks to Shizuku, requires it, or looks for it.

Stop if the watch disconnects, the companion app becomes unstable, or protocol
state diverges. Put the plugin back before recovery — its Nearby access, or the
app itself if it was frozen.

## Unverified

Delivery itself is device-proven: faces sent from this app render on an SM-R390,
and the 4 MiB container ceiling was measured the same way. **Timeout recovery is
the one path no watch has exercised** — what a timeout *means* is pinned in
`TimeoutRecoveryTest`, but nothing has yet been made to time out on purpose.

Acceptance is never guaranteed by structure alone. A structurally valid BIN can
still be rejected by different firmware, unavailable storage, battery state, or
watch-side policy, and the app cannot tell those apart from a successful install
except by looking at the watch.
