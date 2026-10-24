package dev.useunion

/**
 * A feature declared in code. The key is written once and the role follows from the method:
 *
 * ```kotlin
 * val checkout = Union.feature("checkout")
 * checkout.screen("Checkout")            // discovery: saw the Checkout screen
 * checkout.start("checkout_started")
 * checkout.use("shipping_selected")
 * checkout.success("order_placed", mapOf("total" to 49.9))
 * checkout.failure("payment_failed")
 * ```
 *
 * On the server a declaration is a definition, not a suggestion: production and pre-release traffic
 * creates the feature, the panel refines it. An invalid key is logged once when the handle is made;
 * events sent through it are then dropped, never sent without their feature.
 */
public class Feature internal constructor(public val key: String) {
    public fun discovery(name: String, properties: Map<String, Any> = emptyMap()): Unit = track(name, properties, FeatureRole.Discovery)
    public fun start(name: String, properties: Map<String, Any> = emptyMap()): Unit = track(name, properties, FeatureRole.Start)
    public fun use(name: String, properties: Map<String, Any> = emptyMap()): Unit = track(name, properties, FeatureRole.Use)
    public fun success(name: String, properties: Map<String, Any> = emptyMap()): Unit = track(name, properties, FeatureRole.Success)
    public fun failure(name: String, properties: Map<String, Any> = emptyMap()): Unit = track(name, properties, FeatureRole.Failure)

    /** `$screen_view` for this feature: the usual discovery step. Same as `Union.screen(name, feature = key)`. */
    public fun screen(name: String, properties: Map<String, Any> = emptyMap()) {
        Union.screen(name, properties, feature = key)
    }

    private fun track(name: String, properties: Map<String, Any>, role: FeatureRole) {
        Union.track(name, properties, feature = key, role = role)
    }

    override fun equals(other: Any?): Boolean = other is Feature && other.key == key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "Feature($key)"
}
