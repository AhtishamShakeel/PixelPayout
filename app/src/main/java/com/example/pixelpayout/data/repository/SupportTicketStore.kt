package com.example.pixelpayout.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * The signed-in user's support tickets, live.
 *
 * A process-level object like [OfferwallCatalogStore]: the unread dot on
 * Profile and the Help pages all read the same list. The listener is the
 * user's own tickets only (firestore.rules), and with one active ticket at a
 * time that is a handful of documents at most.
 *
 * Every write goes through a callable - see functions/src/economy/
 * supportTickets.ts. The single direct write is clearing `userUnread`, which
 * the rules allow and nothing else.
 */
object SupportTicketStore {

    private const val TAG = "SupportTickets"
    private const val COLLECTION = "supportTickets"
    private const val MESSAGES = "messages"

    data class Ticket(
        val id: String,
        val category: String,
        val subject: String,
        val status: String,
        val orderLabel: String,
        val lastMessagePreview: String,
        val lastMessageFrom: String,
        val lastMessageAtMillis: Long,
        val userUnread: Boolean,
        /** User messages since support last replied; capped server-side. */
        val userMessagesSinceReply: Int = 0
    ) {
        val isActive: Boolean get() = status != STATUS_RESOLVED

        /** Two in a row and the ticket waits for support - see the server. */
        val awaitingSupport: Boolean
            get() = isActive && userMessagesSinceReply >= MESSAGES_IN_A_ROW
    }

    data class Message(
        val id: String,
        val fromSupport: Boolean,
        val text: String,
        val createdAtMillis: Long?
    )

    /** One of the caller's orders, for linking a redemption ticket. */
    data class OrderChoice(val id: String, val label: String)

    sealed class Result {
        data class Ok(val ticketId: String? = null) : Result()
        /** Only one active ticket at a time; [ticketId] is the existing one. */
        data class ActiveTicketExists(val ticketId: String?) : Result()
        data object DailyLimit : Result()
        data object Closed : Result()
        /** Two messages in a row already; wait for support's reply. */
        data object AwaitingSupport : Result()
        data class Error(val message: String?) : Result()
    }

    const val STATUS_OPEN = "open"
    const val STATUS_ANSWERED = "answered"
    const val STATUS_RESOLVED = "resolved"
    /** Mirrors SUPPORT_MESSAGES_IN_A_ROW on the server. */
    const val MESSAGES_IN_A_ROW = 2

    private val _tickets = MutableLiveData<List<Ticket>>(emptyList())
    val tickets: LiveData<List<Ticket>> = _tickets

    private var registration: ListenerRegistration? = null
    private var listeningUid: String? = null

    /** Starts listening for [uid]'s tickets; restarts if the account changed. */
    fun start(uid: String) {
        if (listeningUid == uid && registration != null) return
        registration?.remove()
        listeningUid = uid
        _tickets.postValue(emptyList())

        registration = FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e(TAG, "Ticket listener failed: ${error?.message}")
                    return@addSnapshotListener
                }
                _tickets.postValue(
                    snapshot.documents.map { doc ->
                        Ticket(
                            id = doc.id,
                            category = doc.getString("category").orEmpty(),
                            subject = doc.getString("subject").orEmpty(),
                            status = doc.getString("status").orEmpty(),
                            orderLabel = doc.getString("orderLabel").orEmpty(),
                            lastMessagePreview = doc.getString("lastMessagePreview").orEmpty(),
                            lastMessageFrom = doc.getString("lastMessageFrom").orEmpty(),
                            // Pending server timestamps read as "now", so a
                            // ticket just written sorts to the top.
                            lastMessageAtMillis = doc.getTimestamp(
                                "lastMessageAt",
                                com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                            )?.toDate()?.time ?: 0L,
                            userUnread = doc.getBoolean("userUnread") == true,
                            userMessagesSinceReply =
                                doc.getLong("userMessagesSinceReply")?.toInt() ?: 0
                        )
                    }.sortedByDescending { it.lastMessageAtMillis }
                )
            }
    }

    fun ticket(id: String): Ticket? = _tickets.value?.firstOrNull { it.id == id }

    /** Listens to one ticket's thread, oldest first. Remove it when done. */
    fun listenToMessages(ticketId: String, onMessages: (List<Message>) -> Unit): ListenerRegistration =
        FirebaseFirestore.getInstance()
            .collection(COLLECTION).document(ticketId)
            .collection(MESSAGES)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e(TAG, "Message listener failed: ${error?.message}")
                    return@addSnapshotListener
                }
                onMessages(snapshot.documents.map { doc ->
                    Message(
                        id = doc.id,
                        fromSupport = doc.getString("from") == "admin",
                        text = doc.getString("text").orEmpty(),
                        createdAtMillis = doc.getTimestamp(
                            "createdAt",
                            com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )?.toDate()?.time
                    )
                })
            }

    /** Clears the unread flag once the user has seen support's reply. */
    fun markRead(ticketId: String) {
        if (ticket(ticketId)?.userUnread != true) return
        FirebaseFirestore.getInstance().collection(COLLECTION).document(ticketId)
            .update("userUnread", false)
            .addOnFailureListener { Log.w(TAG, "markRead failed: ${it.message}") }
    }

    /** The caller's recent orders, newest first, for the ticket form. */
    suspend fun recentOrders(uid: String): List<OrderChoice> = try {
        FirebaseFirestore.getInstance().collection("redemptions")
            .whereEqualTo("uid", uid)
            .get().await()
            .documents
            .sortedByDescending { it.getTimestamp("createdAt")?.toDate()?.time ?: 0L }
            .take(10)
            .map { doc ->
                val amount = doc.getString("packAmount")?.takeIf { it.isNotBlank() }
                    ?: doc.getString("optionTitle").orEmpty()
                OrderChoice(doc.id, "$amount · ${doc.id.takeLast(8).uppercase()}")
            }
    } catch (e: Exception) {
        Log.w(TAG, "Orders for ticket form failed: ${e.message}")
        emptyList()
    }

    suspend fun create(category: String, message: String, orderId: String?, appVersion: String): Result =
        call(
            "createSupportTicket",
            buildMap {
                put("category", category)
                put("message", message)
                put("appVersion", appVersion)
                if (!orderId.isNullOrBlank()) put("orderId", orderId)
            }
        )

    suspend fun reply(ticketId: String, message: String): Result =
        call("replySupportTicket", mapOf("ticketId" to ticketId, "message" to message))

    suspend fun close(ticketId: String): Result =
        call("closeSupportTicket", mapOf("ticketId" to ticketId))

    private suspend fun call(name: String, data: Map<String, Any>): Result {
        return try {
            val response = FirebaseFunctions.getInstance()
                .getHttpsCallable(name)
                .withTimeout(20, TimeUnit.SECONDS)
                .call(data)
                .await()
            Result.Ok((response.data as? Map<*, *>)?.get("ticketId") as? String)
        } catch (e: FirebaseFunctionsException) {
            when {
                e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> Result.DailyLimit
                e.message == "active_ticket_exists" ->
                    Result.ActiveTicketExists((e.details as? Map<*, *>)?.get("ticketId") as? String)
                e.message == "ticket_closed" -> Result.Closed
                e.message == "awaiting_support" -> Result.AwaitingSupport
                else -> Result.Error(e.message)
            }
        } catch (e: Exception) {
            Result.Error(e.message)
        }
    }
}
