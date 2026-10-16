package io.github.xiaochuan45.autoledger.capture

import android.content.Context
import io.github.xiaochuan45.autoledger.core.CaptureInput
import io.github.xiaochuan45.autoledger.core.CapturePolicy
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.PaymentParser
import io.github.xiaochuan45.autoledger.data.LedgerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Short-lived, bounded work only; raw inputs never enter preferences, logs or database. */
internal class CaptureDispatcher(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = CapturePreferences(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicInteger(0)

    fun submit(input: CaptureInput) {
        val channel = PaymentChannel.fromPackage(input.packageName) ?: return
        if (!preferences.isCaptureEnabled(channel) || !CapturePolicy.accepts(input)) return
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            CaptureDiagnostics.failed(CaptureFailure.QUEUE_FULL)
            return
        }
        CaptureDiagnostics.observed(channel, input.source, input.observedAt)
        val job = scope.launch {
            try {
                if (!preferences.isCaptureEnabled(channel)) return@launch
                val payment = PaymentParser.parse(input) ?: return@launch
                if (!preferences.isCaptureEnabled(channel)) return@launch
                LedgerRepository.get(appContext).ingest(payment)
                CaptureDiagnostics.parsed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Exceptions may contain merchant/page text: retain only this fixed category.
                CaptureDiagnostics.failed(CaptureFailure.PARSE_OR_STORAGE)
            }
        }
        job.invokeOnCompletion { inFlight.decrementAndGet() }
    }

    fun cancelPending() { scope.coroutineContext.cancelChildren() }
    fun close() { scope.cancel() }

    private companion object { const val MAX_IN_FLIGHT = 8 }
}

/** Never expose partial evidence: an omitted suffix may reverse a payment's apparent status. */
internal class TransientTextBuffer {
    private val fragments = linkedSetOf<String>()
    private var totalCharacters = 0
    var incomplete: Boolean = false
        private set

    fun add(text: CharSequence?) {
        if (text == null || text.isEmpty() || incomplete) return
        // Check before copying. Even a single long fragment invalidates the entire sample.
        if (text.length > MAX_CHARACTERS - totalCharacters || fragments.size >= MAX_FRAGMENTS) {
            markIncomplete()
            return
        }
        val fragment = text.toString().trim()
        if (fragment.isNotEmpty() && fragments.add(fragment)) totalCharacters += fragment.length
    }

    fun markIncomplete() {
        incomplete = true
        fragments.clear()
        totalCharacters = 0
    }

    fun toList(): List<String> = if (incomplete) emptyList() else fragments.toList()

    companion object {
        const val MAX_FRAGMENTS = 24
        const val MAX_CHARACTERS = 4_000
    }
}

internal fun hasSensitiveText(text: CharSequence?): Boolean {
    if (text == null) return false
    val bounded = text.subSequence(0, minOf(text.length, TransientTextBuffer.MAX_CHARACTERS)).toString()
    return listOf("验证码", "密码", "安全验证", "verification code", "password", "one-time code")
        .any { bounded.contains(it, ignoreCase = true) }
}
