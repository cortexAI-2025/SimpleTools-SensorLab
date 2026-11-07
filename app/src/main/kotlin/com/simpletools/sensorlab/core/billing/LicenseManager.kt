package com.simpletools.sensorlab.core.billing

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the UI should say after a licence action. */
enum class ProMessage {
    PURCHASED, PURCHASE_CANCELLED, PURCHASE_FAILED, PURCHASE_PENDING, PURCHASE_ACKNOWLEDGE_FAILED,
    BILLING_UNAVAILABLE, RESTORED, NOTHING_TO_RESTORE,
}

data class ProState(
    val isPro: Boolean = false,
    /** Price formatted by Google Play; null while unknown or when Play does not provide it. */
    val price: String? = null,
    val pending: Boolean = false,
    val busy: Boolean = false,
    val message: ProMessage? = null,
    /** Play response code attached to a PURCHASE_FAILED message. */
    val code: Int = 0,
    val lastVerifiedMs: Long = 0L,
)

/**
 * Single source of truth for PRO access.
 *
 * Rules:
 *  - PRO is granted only when Google Play reports a PURCHASED state for [PRO_PRODUCT_ID]; the
 *    purchase is acknowledged, then cached so the app works offline.
 *  - A PENDING purchase never grants PRO.
 *  - When Play answers and reports no purchase (refund, other account), the cache is revoked.
 *  - When Play cannot answer, the cached state is kept: offline use is never blocked.
 */
class LicenseManager(
    private val billing: BillingGateway,
    private val store: LicenseStore,
    scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ProState())
    val state: StateFlow<ProState> = _state.asStateFlow()

    private val ready = CompletableDeferred<Unit>()
    /** One Play synchronisation at a time: no double acknowledgement, no interleaved cache writes. */
    private val syncLock = Mutex()

    private class SyncResult(val answered: Boolean, val problem: ProMessage?)

    init {
        scope.launch {
            store.cached.collect { c ->
                _state.update { it.copy(isPro = c.pro, lastVerifiedMs = c.verifiedAtMs) }
                if (!ready.isCompleted) ready.complete(Unit)
            }
        }
        scope.launch { billing.updates.collect { handleUpdate(it) } }
        scope.launch { refresh() }
    }

    /** Suspends until the cached licence has been read from disk (tests and first frame). */
    suspend fun awaitReady() = ready.await()

    suspend fun loadPrice() {
        val price = runCatching { billing.productPrice() }.getOrNull()
        _state.update { it.copy(price = price) }
    }

    /** Asks Play for the current purchases. Returns false when Play could not answer. */
    suspend fun refresh(): Boolean {
        val r = sync()
        _state.update { it.copy(message = r.problem) }
        return r.answered
    }

    private suspend fun sync(): SyncResult = syncLock.withLock {
        when (val q = billing.queryOwned()) {
            OwnedQuery.Unavailable -> SyncResult(answered = false, problem = null)
            is OwnedQuery.Success -> SyncResult(answered = true, problem = apply(q.purchases))
        }
    }

    suspend fun purchase(activity: Activity?) {
        _state.update { it.copy(busy = true, message = null) }
        val started = try {
            billing.launchPurchase(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!started) _state.update { it.copy(busy = false, message = ProMessage.BILLING_UNAVAILABLE) }
    }

    suspend fun restore() {
        _state.update { it.copy(busy = true, message = null) }
        val r = sync()
        _state.update {
            val message = when {
                !r.answered -> ProMessage.BILLING_UNAVAILABLE
                r.problem != null -> r.problem
                it.isPro -> ProMessage.RESTORED
                else -> ProMessage.NOTHING_TO_RESTORE
            }
            it.copy(busy = false, message = message)
        }
    }

    private suspend fun handleUpdate(update: PurchaseUpdate) {
        when (update) {
            is PurchaseUpdate.Purchases -> {
                val problem = syncLock.withLock { apply(update.purchases) }
                _state.update {
                    it.copy(
                        busy = false,
                        message = problem ?: if (it.isPro) ProMessage.PURCHASED else null,
                    )
                }
            }
            PurchaseUpdate.Cancelled -> _state.update { it.copy(busy = false, message = ProMessage.PURCHASE_CANCELLED) }
            is PurchaseUpdate.Failed -> _state.update {
                it.copy(busy = false, message = ProMessage.PURCHASE_FAILED, code = update.responseCode)
            }
        }
    }

    /** Applies Play's answer to the licence. Returns a message only when something needs the user's attention. */
    private suspend fun apply(purchases: List<OwnedPurchase>): ProMessage? {
        val mine = purchases.filter { it.productId == PRO_PRODUCT_ID }
        val purchased = mine.firstOrNull { it.state == PurchaseState.PURCHASED }
        if (purchased != null) {
            val acknowledgeFailed = !purchased.acknowledged && !billing.acknowledge(purchased.token)
            val now = nowMs()
            store.save(CachedLicense(pro = true, token = purchased.token, verifiedAtMs = now))
            // Decide on the state now, not when the cache collector gets to it: callers read it right after.
            _state.update { it.copy(isPro = true, pending = false, lastVerifiedMs = now) }
            return if (acknowledgeFailed) ProMessage.PURCHASE_ACKNOWLEDGE_FAILED else null
        }
        val pending = mine.any { it.state == PurchaseState.PENDING }
        _state.update { it.copy(pending = pending) }
        if (pending) return ProMessage.PURCHASE_PENDING
        val now = nowMs()
        store.save(CachedLicense(pro = false, token = null, verifiedAtMs = now))
        _state.update { it.copy(isPro = false, lastVerifiedMs = now) }
        return null
    }
}
