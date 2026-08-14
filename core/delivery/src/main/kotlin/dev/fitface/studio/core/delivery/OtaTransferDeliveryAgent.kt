package dev.fitface.studio.core.delivery

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.samsung.android.sdk.accessory.SAMessage
import com.samsung.android.sdk.accessory.SAPeerAgent
import dev.fitface.studio.core.model.DirectInstallPayload
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal interface TransferListener {
    fun onPayloadVerified(payload: DirectInstallPayload, windows: Int)
    fun onWindowAcknowledged(
        acknowledgedBytes: Int,
        totalBytes: Int,
        acknowledgedWindows: Int,
        totalWindows: Int,
    )

    fun onTransferStatus(message: String)
    fun onTransferComplete(payload: DirectInstallPayload)

    /**
     * @param peerLost the cached accessory peer is gone, so re-sending cannot work
     *   and the setup has to rewind to discovery.
     */
    fun onTransferFailed(message: String, peerLost: Boolean = false)
}

class OtaTransferDeliveryAgent(context: Context) :
    DiscoveryAgent("OtaTransferAgent", context, PROFILE) {
    @Volatile
    internal var transferListener: TransferListener? = null

    private val appContext = context.applicationContext
    private val transferRunning = AtomicBoolean(false)
    @Volatile
    private var activeSocket: BluetoothSocket? = null
    @Volatile
    private var transferThread: Thread? = null
    /**
     * Set by [cancelTransfer] and read by every poll of the SPP response wait. Closing
     * the socket is not enough on its own — whether that makes a blocked read throw is
     * up to the stack — and the wait used to check nothing but its own deadline.
     */
    @Volatile
    private var transferAborted = false
    private val message = object : SAMessage(this) {
        override fun onReceive(peerAgent: SAPeerAgent, data: ByteArray) = Unit

        override fun onSent(peerAgent: SAPeerAgent, transactionId: Int) = Unit

        override fun onError(peerAgent: SAPeerAgent?, transactionId: Int, errorCode: Int) {
            transferListener?.onTransferStatus(
                "Accessory message transaction $transactionId reported $errorCode",
            )
        }
    }

    internal fun runPayload(payload: DirectInstallPayload) {
        if (!transferRunning.compareAndSet(false, true)) {
            transferListener?.onTransferFailed("A transfer is already running")
            return
        }
        transferAborted = false
        val peer = discoveredPeer
        if (peer == null) {
            transferRunning.set(false)
            transferListener?.onTransferFailed(
                "The cached OTA peer is gone — the watch has to be connected and discovered " +
                    "again before it can be sent to.",
                peerLost = true,
            )
            return
        }
        val band = when (val lookup = resolveFit3(peer)) {
            is Fit3Lookup.Found -> lookup.device
            is Fit3Lookup.Unavailable -> {
                transferRunning.set(false)
                transferListener?.onTransferFailed(lookup.reason)
                return
            }
        }
        val identity = payload.copyBytes()
        if (identity.size != payload.size || identity.sha256() != payload.sha256) {
            transferRunning.set(false)
            transferListener?.onTransferFailed("Frozen BIN size or SHA-256 changed")
            return
        }
        val windowCount = IdentityTransferProtocol.windowCount(identity.size)
        transferListener?.onPayloadVerified(payload, windowCount)

        // An accessory send that throws means the peer is no longer reachable, not
        // that the payload is wrong: retrying against the same handle repeats it.
        try {
            message.send(peer, buildFileInfoRequest(payload))
        } catch (error: Exception) {
            transferRunning.set(false)
            transferListener?.onTransferFailed(
                error.message ?: "Unable to send Fit3 file metadata",
                peerLost = true,
            )
            return
        }

        Handler(Looper.getMainLooper()).postDelayed(
            {
                try {
                    message.send(peer, BT_OPEN_REQUEST)
                    connectAndTransfer(band, peer, payload, identity)
                } catch (error: Exception) {
                    transferRunning.set(false)
                    sendBtClose(peer)
                    transferListener?.onTransferFailed(
                        error.message ?: "Unable to open the Fit3 transfer channel",
                        peerLost = true,
                    )
                }
            },
            150L,
        )
    }

    private fun connectAndTransfer(
        band: BluetoothDevice,
        peer: SAPeerAgent,
        payload: DirectInstallPayload,
        identity: ByteArray,
    ) {
        transferThread = thread(name = "Fit3Direct-${payload.faceId}") {
            var socket: BluetoothSocket? = null
            var transferComplete = false
            try {
                socket = band.createRfcommSocketToServiceRecord(SPP_UUID)
                activeSocket = socket
                socket.connect()
                transferListener?.onTransferStatus("Direct Fit3 SPP channel acquired")
                runTransferStateMachine(
                    socket.inputStream,
                    socket.outputStream,
                    payload.fileName,
                    identity,
                )
                transferComplete = true
            } catch (error: Exception) {
                transferListener?.onTransferFailed(
                    error.message ?: error.javaClass.simpleName,
                )
            } finally {
                sendBtClose(peer)
                SystemClock.sleep(500L)
                runCatching { socket?.close() }
                activeSocket = null
                transferThread = null
                transferRunning.set(false)
                if (transferComplete) {
                    Handler(Looper.getMainLooper()).postDelayed(
                        { transferListener?.onTransferComplete(payload) },
                        1_000L,
                    )
                }
            }
        }
    }

    internal fun cancelTransfer() {
        transferAborted = true
        runCatching { activeSocket?.close() }
        transferThread?.interrupt()
        activeSocket = null
        transferThread = null
        transferRunning.set(false)
    }

    private fun runTransferStateMachine(
        input: InputStream,
        output: OutputStream,
        fileName: String,
        identity: ByteArray,
    ) {
        writeFixed(output, SPP_NEGOTIATION_REQUEST)
        requireToken(input, COMMAND_TIMEOUT_MS, SPP_NEGOTIATION_RESPONSE)

        writeFixed(
            output,
            IdentityTransferProtocol.descriptor(
                fileName = fileName,
                fileSize = identity.size,
            ),
        )
        requireToken(input, COMMAND_TIMEOUT_MS, SPP_DESCRIPTOR_RESPONSE)

        val windowCount = IdentityTransferProtocol.windowCount(identity.size)
        var acknowledgedBytes = 0
        for (windowIndex in 0 until windowCount) {
            var acknowledged = false
            for (attempt in 0..IdentityTransferProtocol.MAX_WINDOW_RETRIES) {
                val windowLength =
                    IdentityTransferProtocol.writeWindow(output, identity, windowIndex)
                val response = awaitToken(
                        input,
                        WINDOW_TIMEOUT_MS,
                        SPP_WINDOW_ACCEPTED,
                        SPP_WINDOW_RETRY,
                    )
                when {
                    response.contentEquals(SPP_WINDOW_ACCEPTED) -> {
                        acknowledgedBytes += windowLength
                        transferListener?.onWindowAcknowledged(
                            acknowledgedBytes,
                            identity.size,
                            windowIndex + 1,
                            windowCount,
                        )
                        acknowledged = true
                        break
                    }

                    response.contentEquals(SPP_WINDOW_RETRY) -> Unit
                }
            }
            if (!acknowledged) {
                throw IOException("Window ${windowIndex + 1} exceeded its retry limit")
            }
        }
        if (acknowledgedBytes != identity.size) {
            throw IOException(
                "Acknowledged $acknowledgedBytes of ${identity.size} payload bytes",
            )
        }

        writeFixed(output, SPP_RESULT_REQUEST)
        requireToken(input, RESULT_TIMEOUT_MS, SPP_RESULT_RESPONSE)
        transferListener?.onTransferStatus("Fit3 verified the complete BIN")

        SystemClock.sleep(250L)
        writeFixed(output, SPP_CLOSE_REQUEST)
        requireToken(input, COMMAND_TIMEOUT_MS, SPP_CLOSE_RESPONSE)
        transferListener?.onTransferStatus("Fit3 closed the transfer cleanly")
    }

    private fun requireToken(input: InputStream, timeoutMs: Long, expected: ByteArray) {
        awaitToken(input, timeoutMs, expected)
    }

    /**
     * The device-side wiring of [SppResponseWait]. The abort signal is [cancelTransfer]
     * plus the thread's own interrupt flag: this runs on the transfer thread, not in the
     * caller's coroutine, so there is no `isActive` to read here.
     */
    private fun awaitToken(
        input: InputStream,
        timeoutMs: Long,
        vararg accepted: ByteArray,
    ): ByteArray = SppResponseWait.awaitToken(
        input = input,
        timeoutMs = timeoutMs,
        accepted = accepted,
        elapsedMillis = { SystemClock.elapsedRealtime() },
        pause = { millis -> Thread.sleep(millis) },
        aborted = { transferAborted || Thread.currentThread().isInterrupted },
    )

    /**
     * Which bonded watch to open RFCOMM against.
     *
     * This ended in `singleOrNull()`, so a phone that had ever bonded a second matching
     * watch — a replacement unit, someone else's — reported "no watch" with nothing in
     * the UI explaining why. The discovered peer names the accessory the framework is
     * already talking to, which settles it whenever discovery has run; the adapter's
     * connected set is the fallback, and a real tie is reported as a tie instead of as
     * an absence.
     */
    private fun resolveFit3(peer: SAPeerAgent): Fit3Lookup {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Fit3Lookup.Unavailable("This phone exposes no Bluetooth adapter.")
        if (!adapter.isEnabled) {
            return Fit3Lookup.Unavailable("Bluetooth is turned off. Turn it on, then send again.")
        }
        val matches = try {
            adapter.bondedDevices.filter { device ->
                val name = deviceName(device)
                name.contains("Fit3", ignoreCase = true) || name.contains("SM-R390")
            }
        } catch (_: SecurityException) {
            return Fit3Lookup.Unavailable(
                "FitFace Studio no longer has Nearby devices access, so it cannot see the " +
                    "bonded watch. Grant it again, then send.",
            )
        }
        val peerAddress = runCatching { peer.accessory?.address }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.uppercase(Locale.US)
        val connectedAddresses = connectedAddresses()
        val candidates = matches.map { device ->
            val address = runCatching { device.address }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.uppercase(Locale.US)
            Fit3Candidate(
                name = deviceName(device).ifBlank { "an unnamed bonded watch" },
                isDiscoveredPeer = address != null && address == peerAddress,
                connected = address != null && address in connectedAddresses,
            )
        }
        return when (val selection = selectFit3(candidates)) {
            is Fit3Selection.Selected -> Fit3Lookup.Found(matches[selection.index])
            is Fit3Selection.Unavailable -> Fit3Lookup.Unavailable(selection.reason)
        }
    }

    /**
     * As close to "is this watch connected" as an ordinary app gets:
     * `BluetoothDevice.isConnected` is a hidden API and the adapter only reports
     * connections for GATT, so a watch holding a classic link alone can be missing from
     * this. That is why it is the fallback and not the first test.
     */
    private fun connectedAddresses(): Set<String> = runCatching {
        appContext.getSystemService(BluetoothManager::class.java)
            ?.getConnectedDevices(BluetoothProfile.GATT)
            ?.mapNotNull { device -> runCatching { device.address }.getOrNull() }
            ?.mapTo(mutableSetOf()) { address -> address.uppercase(Locale.US) }
    }.getOrNull().orEmpty()

    /** Reading a bonded device's name is permission-guarded and can throw per device. */
    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.name.orEmpty() }.getOrDefault("")

    private fun sendBtClose(peer: SAPeerAgent) {
        runCatching { message.send(peer, BT_CLOSE_REQUEST) }
    }

    private fun buildFileInfoRequest(payload: DirectInstallPayload): ByteArray {
        val name = payload.fileName.toByteArray(StandardCharsets.UTF_8)
        return ByteArrayOutputStream(10 + name.size).apply {
            write(0x01)
            write(0x03)
            write(0x06)
            write(0x04)
            write(name.size)
            write(name)
            write(0x05)
            write(payload.size and 0xff)
            write((payload.size ushr 8) and 0xff)
            write((payload.size ushr 16) and 0xff)
            write((payload.size ushr 24) and 0xff)
        }.toByteArray()
    }

    companion object {
        const val PROFILE: String = "/system/OtaTransferAgent"

        private val BT_OPEN_REQUEST = byteArrayOf(0x02)
        private val BT_CLOSE_REQUEST = byteArrayOf(0x03)
        private val SPP_NEGOTIATION_REQUEST = "30".toByteArray()
        private val SPP_NEGOTIATION_RESPONSE = "300".toByteArray()
        private val SPP_DESCRIPTOR_RESPONSE = "330".toByteArray()
        private val SPP_WINDOW_ACCEPTED = "310".toByteArray()
        private val SPP_WINDOW_RETRY = "311".toByteArray()
        private val SPP_RESULT_REQUEST = "32".toByteArray()
        private val SPP_RESULT_RESPONSE = "320".toByteArray()
        private val SPP_CLOSE_REQUEST = "34".toByteArray()
        private val SPP_CLOSE_RESPONSE = "340".toByteArray()

        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val WINDOW_TIMEOUT_MS = 12_000L
        private const val RESULT_TIMEOUT_MS = 15_000L
        private val SPP_UUID: UUID =
            UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3")
    }
}

/**
 * The wait for one SPP response token, with the clock, the pause and the abort signal
 * handed in.
 *
 * It used to be a `Thread.sleep` loop inside the agent that checked nothing but its own
 * deadline, so a transfer nobody was watching any more kept a thread parked until the
 * longest timeout in the protocol — `RESULT_TIMEOUT_MS`, 15 s — ran out. Coroutine
 * cancellation cannot interrupt a sleeping thread, so the caller has to say when it has
 * given up and the loop has to ask, once per poll. Injecting the clock and the pause is
 * what makes that assertable at all: on the device they are `SystemClock` and
 * `Thread.sleep`, in a test they are a counter.
 */
internal object SppResponseWait {
    const val MAX_RESPONSE_BYTES = 128
    const val POLL_MS = 5L

    const val TIMED_OUT = "Timed out waiting for Fit3 protocol response"
    const val INPUT_CLOSED = "SPP input closed"
    const val ABORTED = "The Fit3 transfer was cancelled before the watch answered"

    fun awaitToken(
        input: InputStream,
        timeoutMs: Long,
        accepted: Array<out ByteArray>,
        elapsedMillis: () -> Long,
        pause: (Long) -> Unit,
        aborted: () -> Boolean,
    ): ByteArray {
        val deadline = elapsedMillis() + timeoutMs
        val response = ByteArray(MAX_RESPONSE_BYTES)
        var received = 0
        while (elapsedMillis() < deadline && received < response.size) {
            if (aborted()) throw IOException(ABORTED)
            val available = input.available()
            if (available <= 0) {
                pause(POLL_MS)
                continue
            }
            val read = input.read(
                response,
                received,
                minOf(available, response.size - received),
            )
            if (read < 0) throw IOException(INPUT_CLOSED)
            received += read
            accepted.forEach { token ->
                if (response.containsToken(received, token)) return token
            }
        }
        throw IOException(TIMED_OUT)
    }
}

/** One bonded watch, reduced to what choosing between them needs. */
internal data class Fit3Candidate(
    val name: String,
    /** The accessory the discovered OTA peer lives on is this device. */
    val isDiscoveredPeer: Boolean = false,
    /** The adapter reports a live connection to it. */
    val connected: Boolean = false,
)

internal sealed interface Fit3Selection {
    /** An index into the list handed to [selectFit3]. */
    data class Selected(val index: Int) : Fit3Selection

    data class Unavailable(val reason: String) : Fit3Selection
}

/** [resolveFit3]'s answer once the candidates map back onto real devices. */
internal sealed interface Fit3Lookup {
    data class Found(val device: BluetoothDevice) : Fit3Lookup

    data class Unavailable(val reason: String) : Fit3Lookup
}

/**
 * Picks the watch to send to, or says why it cannot.
 *
 * A `singleOrNull()` here reported "no watch" the moment a second matching watch had
 * ever been bonded, which is a thing that happens to anyone with a replacement unit —
 * and the message named neither the cause nor a way out. A single bonded match is still
 * used without asking whether it is connected: the RFCOMM connect that follows reports
 * that far better than a guess would.
 */
internal fun selectFit3(candidates: List<Fit3Candidate>): Fit3Selection = when {
    candidates.isEmpty() -> Fit3Selection.Unavailable(
        "No bonded Fit3 (SM-R390) on this phone. Pair the watch in the companion app first.",
    )
    candidates.size == 1 -> Fit3Selection.Selected(0)
    else -> {
        val peers = candidates.indicesWhere { it.isDiscoveredPeer }
        val connected = candidates.indicesWhere { it.connected }
        when {
            peers.size == 1 -> Fit3Selection.Selected(peers.single())
            connected.size == 1 -> Fit3Selection.Selected(connected.single())
            connected.size > 1 -> Fit3Selection.Unavailable(
                "${connected.size} bonded Fit3 watches are connected at once " +
                    "(${connected.joinToString { candidates[it].name }}), so there is no way " +
                    "to tell which one to send to. Disconnect all but the one you want.",
            )
            else -> Fit3Selection.Unavailable(
                "This phone has ${candidates.size} bonded Fit3 watches " +
                    "(${candidates.joinToString { it.name }}) and none of them is connected. " +
                    "Connect the one you want in the companion app, then send again.",
            )
        }
    }
}

private fun <T> List<T>.indicesWhere(predicate: (T) -> Boolean): List<Int> =
    indices.filter { index -> predicate(this[index]) }

private fun writeFixed(output: OutputStream, bytes: ByteArray) {
    output.write(bytes)
    output.flush()
}

private fun ByteArray.containsToken(length: Int, token: ByteArray): Boolean {
    for (start in 0..length - token.size) {
        if (token.indices.all { index -> this[start + index] == token[index] }) return true
    }
    return false
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { value -> "%02x".format(value.toInt() and 0xff) }
