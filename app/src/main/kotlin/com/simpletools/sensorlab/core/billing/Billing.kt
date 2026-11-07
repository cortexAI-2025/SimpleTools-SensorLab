package com.simpletools.sensorlab.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/** Google Play one-time product that unlocks PRO for life. Must match the Play Console product id. */
const val PRO_PRODUCT_ID = "simpletools_sensorlab_pro_lifetime"

enum class PurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/** A purchase as reported by Google Play (query result or purchase update). */
data class OwnedPurchase(
    val productId: String,
    val token: String,
    val state: PurchaseState,
    val acknowledged: Boolean,
)

sealed interface OwnedQuery {
    data class Success(val purchases: List<OwnedPurchase>) : OwnedQuery
    /** Play could not answer (no Play Store, no network, service error): the cached licence stays in force. */
    data object Unavailable : OwnedQuery
}

sealed interface PurchaseUpdate {
    data class Purchases(val purchases: List<OwnedPurchase>) : PurchaseUpdate
    data object Cancelled : PurchaseUpdate
    data class Failed(val responseCode: Int) : PurchaseUpdate
}

/** Narrow port over Google Play Billing so the licence logic can be tested without Play. */
interface BillingGateway {
    /** Results of purchase flows launched by [launchPurchase]. */
    val updates: Flow<PurchaseUpdate>

    /** Localised price as formatted by Google Play, or null when Play does not provide it. */
    suspend fun productPrice(): String?

    suspend fun queryOwned(): OwnedQuery

    suspend fun acknowledge(token: String): Boolean

    /** Starts the Play purchase sheet. Returns false when the flow could not be started. */
    suspend fun launchPurchase(activity: Activity?): Boolean
}
