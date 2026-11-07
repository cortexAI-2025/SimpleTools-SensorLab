package com.simpletools.sensorlab

import com.simpletools.sensorlab.core.billing.CachedLicense
import com.simpletools.sensorlab.core.billing.LicenseManager
import com.simpletools.sensorlab.core.billing.OwnedPurchase
import com.simpletools.sensorlab.core.billing.OwnedQuery
import com.simpletools.sensorlab.core.billing.PRO_PRODUCT_ID
import com.simpletools.sensorlab.core.billing.ProMessage
import com.simpletools.sensorlab.core.billing.PurchaseState
import com.simpletools.sensorlab.core.billing.PurchaseUpdate
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Licence rules, tested against a simulated Google Play. No device, no network. */
class LicenseManagerTest {

    private fun purchase(ack: Boolean = true, state: PurchaseState = PurchaseState.PURCHASED, product: String = PRO_PRODUCT_ID) =
        OwnedPurchase(product, "tok-1", state, ack)

    /** Builds the manager and lets the cache and Play answer, as the app does at start-up. */
    private suspend fun kotlinx.coroutines.test.TestScope.manager(
        billing: FakeBillingGateway,
        store: InMemoryLicenseStore = InMemoryLicenseStore(),
    ): LicenseManager {
        // Unconfined on the test clock: collectors run as soon as a value arrives, so the test reads a settled state.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        // The manager asks Play once, at construction (as in the app); nothing else is needed.
        val m = LicenseManager(billing, store, scope, nowMs = { 42L })
        m.awaitReady()
        return m
    }

    @Test fun purchasedProductGrantsProAndIsCachedWithItsToken() = runTest {
        val billing = FakeBillingGateway(owned = OwnedQuery.Success(listOf(purchase(ack = true))))
        val store = InMemoryLicenseStore()
        val m = manager(billing, store)
        assertTrue(m.state.value.isPro)
        assertEquals(CachedLicense(true, "tok-1", 42L), store.current)
        assertTrue("already acknowledged: nothing to acknowledge", billing.acknowledged.isEmpty())
    }

    @Test fun unacknowledgedPurchaseIsAcknowledgedBeforeBeingTrusted() = runTest {
        val billing = FakeBillingGateway(owned = OwnedQuery.Success(listOf(purchase(ack = false))))
        val m = manager(billing)
        assertTrue(m.state.value.isPro)
        assertEquals(listOf("tok-1"), billing.acknowledged)
        assertNull(m.state.value.message)
    }

    @Test fun acknowledgeFailureKeepsProAndReportsIt() = runTest {
        val billing = FakeBillingGateway(owned = OwnedQuery.Success(listOf(purchase(ack = false))), acknowledgeOk = false)
        val m = manager(billing)
        assertTrue(m.state.value.isPro)
        assertEquals(ProMessage.PURCHASE_ACKNOWLEDGE_FAILED, m.state.value.message)
    }

    @Test fun pendingPurchaseNeverGrantsPro() = runTest {
        val billing = FakeBillingGateway(owned = OwnedQuery.Success(listOf(purchase(state = PurchaseState.PENDING))))
        val m = manager(billing)
        assertFalse(m.state.value.isPro)
        assertTrue(m.state.value.pending)
        assertEquals(ProMessage.PURCHASE_PENDING, m.state.value.message)
    }

    @Test fun purchaseOfAnotherProductDoesNotUnlockPro() = runTest {
        val billing = FakeBillingGateway(owned = OwnedQuery.Success(listOf(purchase(product = "other_product"))))
        assertFalse(manager(billing).state.value.isPro)
    }

    @Test fun refundOrOtherAccountRevokesTheCachedLicenseWhenPlayAnswers() = runTest {
        val billing = FakeBillingGateway(owned = OwnedQuery.Success(emptyList()))
        val store = InMemoryLicenseStore(initialPro = true)
        val m = manager(billing, store)
        assertFalse(m.state.value.isPro)
        assertFalse(store.current.pro)
    }

    @Test fun offlineKeepsTheCachedLicense() = runTest {
        // Play cannot answer: the licence verified earlier stays in force.
        val billing = FakeBillingGateway(owned = OwnedQuery.Unavailable)
        val store = InMemoryLicenseStore(initialPro = true)
        val m = manager(billing, store)
        assertTrue(m.state.value.isPro)
        assertTrue(store.current.pro)
    }

    @Test fun offlineWithoutCacheStaysFree() = runTest {
        val m = manager(FakeBillingGateway(owned = OwnedQuery.Unavailable))
        assertFalse(m.state.value.isPro)
    }

    @Test fun purchaseFlowSuccessUnlocksAndSaysSo() = runTest {
        val billing = FakeBillingGateway()
        val m = manager(billing)
        billing.emit(PurchaseUpdate.Purchases(listOf(purchase(ack = false))))
        advanceUntilIdle()
        assertTrue(m.state.value.isPro)
        assertEquals(ProMessage.PURCHASED, m.state.value.message)
        assertFalse(m.state.value.busy)
    }

    @Test fun cancelledPurchaseLeavesFreeVersionAndSaysSo() = runTest {
        val billing = FakeBillingGateway()
        val m = manager(billing)
        billing.emit(PurchaseUpdate.Cancelled)
        advanceUntilIdle()
        assertFalse(m.state.value.isPro)
        assertEquals(ProMessage.PURCHASE_CANCELLED, m.state.value.message)
    }

    @Test fun failedPurchaseKeepsPlayCode() = runTest {
        val billing = FakeBillingGateway()
        val m = manager(billing)
        billing.emit(PurchaseUpdate.Failed(6))
        advanceUntilIdle()
        assertFalse(m.state.value.isPro)
        assertEquals(ProMessage.PURCHASE_FAILED, m.state.value.message)
        assertEquals(6, m.state.value.code)
    }

    @Test fun purchaseThatCannotStartReportsUnavailable() = runTest {
        val billing = FakeBillingGateway(launchOk = false)
        val m = manager(billing)
        m.purchase(activity = null)
        advanceUntilIdle()
        assertEquals(ProMessage.BILLING_UNAVAILABLE, m.state.value.message)
        assertFalse(m.state.value.busy)
    }

    @Test fun restoreReportsEachOutcome() = runTest {
        val unavailable = manager(FakeBillingGateway(owned = OwnedQuery.Unavailable))
        unavailable.restore(); advanceUntilIdle()
        assertEquals(ProMessage.BILLING_UNAVAILABLE, unavailable.state.value.message)

        val none = manager(FakeBillingGateway(owned = OwnedQuery.Success(emptyList())))
        none.restore(); advanceUntilIdle()
        assertEquals(ProMessage.NOTHING_TO_RESTORE, none.state.value.message)

        val owned = manager(FakeBillingGateway(owned = OwnedQuery.Success(listOf(purchase()))))
        owned.restore(); advanceUntilIdle()
        assertEquals(ProMessage.RESTORED, owned.state.value.message)
        assertTrue(owned.state.value.isPro)
    }

    @Test fun priceComesFromPlayOnly() = runTest {
        val m = manager(FakeBillingGateway(price = "4,99 €"))
        m.loadPrice(); advanceUntilIdle()
        assertEquals("4,99 €", m.state.value.price)
        val unknown = manager(FakeBillingGateway(price = null))
        unknown.loadPrice(); advanceUntilIdle()
        assertNull(unknown.state.value.price)
    }

    @Test fun cacheIsReadBeforeTheFirstDecision() = runTest {
        val m = manager(FakeBillingGateway(owned = OwnedQuery.Unavailable), InMemoryLicenseStore(initialPro = true))
        m.awaitReady()
        assertTrue(m.state.value.isPro)
    }
}
