package com.media.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ============================================================================
//  BILLING — one-time "remove ads" purchase
//
//  Non-consumable INAPP product. Three things here are not optional and are
//  where most implementations go wrong:
//
//  1. ACKNOWLEDGE within three days. Google auto-refunds anything unacknowledged,
//     so a purchase that is never acknowledged silently reverses itself and the
//     user loses what they paid for.
//  2. RESTORE on every launch via queryPurchasesAsync. Reinstall, new device,
//     cleared data — without this the entitlement simply disappears.
//  3. Never consume it. Consuming makes it purchasable again and revokes the
//     entitlement.
//
//  The cached flag in DataStore exists so ads are suppressed on the very first
//  frame, before Play has connected. Billing is still the source of truth: if
//  a query comes back without the purchase (refund, chargeback), the cache is
//  cleared to match.
// ============================================================================

object Billing {

    /** Must match the product id created in the Play Console. */
    const val REMOVE_ADS_ID = "remove_ads"

    private val _adFree = MutableStateFlow(false)
    val adFree: StateFlow<Boolean> = _adFree.asStateFlow()

    private val _product = MutableStateFlow<ProductDetails?>(null)
    /** Null until Play responds; the Settings row shows a price from this. */
    val product: StateFlow<ProductDetails?> = _product.asStateFlow()

    private var client: BillingClient? = null

    /** Seeds from the cached flag so the first frame is already correct. */
    fun seed(cached: Boolean) {
        if (cached) _adFree.value = true
    }

    fun start(context: Context, onEntitlementChanged: (Boolean) -> Unit) {
        if (client?.isReady == true) return

        val listener = PurchasesUpdatedListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                purchases.forEach { handle(it, onEntitlementChanged) }
            }
            // USER_CANCELED and ITEM_ALREADY_OWNED need no action here — the
            // latter is picked up by the restore query below.
        }

        val c = BillingClient.newBuilder(context)
            .setListener(listener)
            // PBL 9 recommendation: the library re-establishes the connection
            // itself when an API call is made while disconnected, so
            // onBillingServiceDisconnected stays a no-op rather than retrying.
            .enableAutoServiceReconnection()
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        client = c

        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryProduct(c)
                restore(c, onEntitlementChanged)
            }
            override fun onBillingServiceDisconnected() { /* retried on next start() */ }
        })
    }

    private fun queryProduct(c: BillingClient) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(REMOVE_ADS_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()
        // PBL 8+ changed this callback: the second argument is now a
        // QueryProductDetailsResult rather than a bare List<ProductDetails>.
        c.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _product.value = queryResult.productDetailsList.firstOrNull()
            }
        }
    }

    /** Source of truth. Also CLEARS the entitlement if Play no longer reports it. */
    private fun restore(c: BillingClient, onEntitlementChanged: (Boolean) -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP).build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val owned = purchases.any {
                it.products.contains(REMOVE_ADS_ID) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            purchases.forEach { handle(it, onEntitlementChanged) }
            if (!owned && _adFree.value) {
                // Refunded or revoked — stop honouring the cached flag.
                _adFree.value = false
                onEntitlementChanged(false)
            }
        }
    }

    private fun handle(purchase: Purchase, onEntitlementChanged: (Boolean) -> Unit) {
        if (!purchase.products.contains(REMOVE_ADS_ID)) return
        // PENDING means payment is still processing (cash, slow card). Grant
        // nothing yet — it resolves on a later launch.
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken).build()
            client?.acknowledgePurchase(params) { /* retried next launch on failure */ }
        }
        if (!_adFree.value) {
            _adFree.value = true
            onEntitlementChanged(true)
        }
    }

    fun purchase(activity: Activity) {
        val c = client ?: return
        val details = _product.value ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            ).build()
        c.launchBillingFlow(activity, params)
    }
}
