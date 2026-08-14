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
and *either* revoking its Nearby permission *or* disconnecting the watch in the
companion app does it. Only the first is observable to this app, so the second is
a user acknowledgement rather than a detected state.

Discovery without the plugin connected must land in the recoverable
`NEEDS_WATCH_CONNECTION`, never in `FAILED`. That covers the explicit
`device_not_connected` outcome and a silent discovery watchdog timeout, which
means the same thing.

A committed edit invalidates a finished transfer, so `payloadChanged()` re-arms
`COMPLETE` and `FAILED` back to `READY` while keeping the cached peers. Without
it the Install page offers nothing but "Back to canvas" after the first install.

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
clearing each agent's cached `SAPeerAgent` and cancelling any transfer in flight.
It is not `reset()`: nothing the phone told us about itself is re-probed.

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
permission-isolated only after peer handles are cached.

Stop if the watch disconnects, the companion app becomes unstable, or protocol
state diverges. Restore the plugin's Nearby permission before recovery.

## Unverified

Delivery itself is device-proven: faces sent from this app render on an SM-R390,
and the 4 MiB container ceiling was measured the same way. **Timeout recovery is
the one path no watch has exercised** — what a timeout *means* is pinned in
`TimeoutRecoveryTest`, but nothing has yet been made to time out on purpose.

Acceptance is never guaranteed by structure alone. A structurally valid BIN can
still be rejected by different firmware, unavailable storage, battery state, or
watch-side policy, and the app cannot tell those apart from a successful install
except by looking at the watch.
