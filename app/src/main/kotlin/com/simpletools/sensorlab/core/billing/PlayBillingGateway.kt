package com.simpletools.sensorlab.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Production [BillingGateway] on Google Play Billing Library (no server, no account). */
class PlayBillingGateway(context: Context) : BillingGateway {

    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    private val connectLock = Mutex()
    @Volatile private var details: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            val update = when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> PurchaseUpdate.Purchases(purchases.orEmpty().map { it.toOwned() })
                BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Cancelled
                else -> PurchaseUpdate.Failed(result.responseCode)
            }
            _updates.tryEmit(update)
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun productDetails(): ProductDetails? {
        details?.let { return it }
        if (!connect()) return null
        return suspendCancellableCoroutine { cont ->
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
            client.queryProductDetailsAsync(params) { result, queryResult ->
                val found = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryResult.productDetailsList.firstOrNull { it.productId == PRO_PRODUCT_ID }
                } else null
                details = found
                if (cont.isActive) cont.resume(found)
            }
        }
    }

    override suspend fun productPrice(): String? =
        productDetails()?.oneTimePurchaseOfferDetails?.formattedPrice

    override suspend fun queryOwned(): OwnedQuery {
        if (!connect()) return OwnedQuery.Unavailable
        return suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                val answer = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    OwnedQuery.Success(purchases.map { it.toOwned() })
                } else OwnedQuery.Unavailable
                if (cont.isActive) cont.resume(answer)
            }
        }
    }

    override suspend fun acknowledge(token: String): Boolean {
        if (!connect()) return false
        return suspendCancellableCoroutine { cont ->
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
            client.acknowledgePurchase(params) { result ->
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }
    }

    override suspend fun launchPurchase(activity: Activity?): Boolean {
        if (activity == null) return false
        val d = productDetails() ?: return false
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()),
            )
            .build()
        return client.launchBillingFlow(activity, flow).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun Purchase.toOwned() = OwnedPurchase(
        productId = products.firstOrNull().orEmpty(),
        token = purchaseToken,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNSPECIFIED
        },
        acknowledged = isAcknowledged,
    )
}
