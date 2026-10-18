package io.alvaroedo.skypost.model

/** Delivery ticks shown to the user: sent → heard by base → delivered → read. */
enum class DeliveryState { QUEUED, SENT, HEARD_BY_BASE, DELIVERED, READ, FAILED }

data class OutgoingItem(
    val id: Long,
    val label: String,
    val state: DeliveryState,
)

data class IncomingItem(
    val id: Long,
    val from: String,
    val text: String,
    val receivedAtMillis: Long,
)
