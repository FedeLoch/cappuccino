package art.cappuccino.paint

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

/** The one product: the Flat white companion, bought once to support Cappuccino. */
const val FLAT_WHITE_PRODUCT = "flat_white"

private const val OWNED_KEY = "flatWhiteOwned"

/**
 * Talks to Google Play about the Flat white. Payment happens in the Play Store app, so Cappuccino
 * itself never goes online. Whether you own it is remembered, so it stays unlocked offline, and is
 * checked with Play each time the studio opens, so it comes back on a new device too.
 */
class FlatWhiteStore(context: Context, private val changed: () -> Unit) {
    private val preferences = context.getSharedPreferences("studio", Context.MODE_PRIVATE)
    private var details: ProductDetails? = null
    private val client = BillingClient.newBuilder(context)
        .setListener { result, purchases -> if (result.responseCode == BillingClient.BillingResponseCode.OK) take(purchases.orEmpty()) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    var owned = preferences.getBoolean(OWNED_KEY, false)
        private set

    /** The price in your currency, as Play shows it, once Play has answered. */
    val price: String? get() = details?.oneTimePurchaseOfferDetails?.formattedPrice

    /** False when Play isn't there to sell it, such as on a device without the Play Store. */
    var available = false
        private set

    fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                available = true
                checkPurchases()
                loadPrice()
            }

            override fun onBillingServiceDisconnected() {
                available = false
            }
        })
    }

    fun close() = client.endConnection()

    /** Opens Play's own purchase sheet. Returns false when it can't. */
    fun buy(activity: Activity): Boolean {
        val product = details ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun checkPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            // Play's answer wins over what's remembered, so a refund locks it again.
            val bought = purchases.any { it.isFlatWhite() && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            take(purchases)
            if (!bought) remember(false)
        }
    }

    private fun loadPrice() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(FLAT_WHITE_PRODUCT)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            details = found.productDetailsList.firstOrNull()
            changed()
        }
    }

    private fun take(purchases: List<Purchase>) {
        purchases.filter { it.isFlatWhite() && it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { purchase ->
            // Play refunds a purchase that isn't acknowledged within three days.
            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                client.acknowledgePurchase(params) { }
            }
            remember(true)
        }
    }

    private fun Purchase.isFlatWhite() = FLAT_WHITE_PRODUCT in products

    private fun remember(value: Boolean) {
        if (owned == value) return
        owned = value
        preferences.edit().putBoolean(OWNED_KEY, value).apply()
        changed()
    }
}
