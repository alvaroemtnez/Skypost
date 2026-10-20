package io.alvaroedo.skypost.ui.pharos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.AdminCommand
import io.alvaroedo.skypost.model.AdminEntry
import io.alvaroedo.skypost.model.AdminOutcome
import io.alvaroedo.skypost.model.AdminReply
import io.alvaroedo.skypost.model.AdminTarget
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Contact
import io.alvaroedo.skypost.model.Who
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.PharosSwitch
import io.alvaroedo.skypost.model.RequestEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PharosAdminUi(
    val pharos: Pharos? = null,
    /** Emergency encryption for everything: the password may only go out then. */
    val encryptAll: Boolean = false,
    /** Newest first. */
    val log: List<AdminEntry> = emptyList(),
    /** The switches as last heard (status, notices, our own changes); null before any. */
    val switches: Set<PharosSwitch>? = null,
    /** Pending requests from the last list, minus those decided since; null before any. */
    val requests: List<RequestEntry>? = null,
    /** A request list was asked for and has not arrived. */
    val requestsLoading: Boolean = false,
    /** Who a command can name: our contacts and MeshCore chats, plus the Pharos's lists. */
    val people: List<Person> = emptyList(),
    /** Our contacts by radio id, and their aliases, to tell people apart. */
    val contactIds: Map<String, Contact> = emptyMap(),
    val contactAliases: Set<String> = emptySet(),
)

/**
 * Someone a command can name. [verified]: one of our verified contacts.
 * [impostor]: a radio user whose name matches one of our contacts but whose
 * key does not.
 */
data class Person(
    val target: AdminTarget,
    val who: Who,
    val name: String,
    val id: String?,
    val verified: Boolean = false,
    val impostor: Boolean = false,
)

/**
 * Remote management of one Pharos (decision 0021). Commands go on air as
 * compact `Admin` messages; replies come back structured and the screen shows
 * them in the app's language.
 */
class PharosAdminViewModel(private val repo: Repository, private val key: String) : ViewModel() {
    val ui: StateFlow<PharosAdminUi> = combine(
        repo.pharos,
        repo.settings,
        repo.adminLog(key),
        repo.contacts,
        repo.chats,
    ) { list, settings, log, contacts, chats ->
        val byId = contacts.associateBy { repo.userId(it.card.key) }
        val aliases = contacts.map { it.card.alias.lowercase() }.toSet()
        PharosAdminUi(
            pharos = list.firstOrNull { it.card.key == key },
            encryptAll = settings.encryptAll,
            log = log.asReversed(),
            switches = switchesFrom(log),
            requests = requestsFrom(log),
            requestsLoading = log.lastOrNull { it.command == AdminCommand.Requests }?.reply == null &&
                log.any { it.command == AdminCommand.Requests },
            people = peopleFrom(log, byId, aliases, chats),
            contactIds = byId,
            contactAliases = aliases,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PharosAdminUi())

    /** The log entry id, to follow the reply in a popup; null if not sent. */
    fun send(command: AdminCommand): Long? = repo.sendAdmin(key, command)

    /** It goes out in plain and puts a name or number on air: ask first. */
    fun needsWarning(command: AdminCommand): Boolean = !ui.value.encryptAll && repo.revealsPersonalData(command)

    /** False when it was not sent (encryption off). */
    fun login(password: String): Boolean = repo.adminLogin(key, password.trim())

    /**
     * A typed Signal or WhatsApp contact: a phone, or a Signal username.
     * Radio users and MeshCore contacts are picked from [PharosAdminUi.people],
     * never typed. Null if it is not usable.
     */
    fun target(kind: ChatKind?, input: String): AdminTarget? {
        val t = input.trim()
        return when (kind) {
            ChatKind.SIGNAL if USERNAME.matches(t) -> AdminTarget.Contact(ChatKind.SIGNAL, t)
            ChatKind.SIGNAL, ChatKind.WHATSAPP ->
                repo.normalizePhone(t).takeIf { it.length in 8..15 }?.let { AdminTarget.Contact(kind, it) }
            else -> null
        }
    }

    companion object {
        /**
         * How long a popup waits for the Pharos before saying so. Over the
         * radio a command and its reply take a few frames each, plus retries
         * on slower lanes; the reply still lands in the log if it comes later.
         */
        const val REPLY_TIMEOUT_MS = 180_000L

        /** How someone from a Pharos list compares with our contacts. */
        fun look(who: Who, id: String?, name: String, byId: Map<String, Contact>, aliases: Set<String>): Pair<Boolean, Boolean> {
            if (who != Who.RADIO || id == null) return false to false
            val mine = byId[id]
            val impostor = mine == null && name.removePrefix("@").lowercase() in aliases
            return (mine?.verified == true) to impostor
        }

        fun peopleFrom(log: List<AdminEntry>, byId: Map<String, Contact>, aliases: Set<String>, chats: List<Chat>): List<Person> {
            val out = LinkedHashMap<String, Person>()
            fun add(who: Who, id: String, name: String) {
                if (id in out) return
                val target = if (who == Who.RADIO) AdminTarget.User(id, name) else AdminTarget.MeshCore(id, name)
                val (verified, impostor) = look(who, id, name, byId, aliases)
                out[id] = Person(target, who, name, id, verified, impostor)
            }
            byId.forEach { (id, c) -> add(Who.RADIO, id, "@" + c.card.alias) }
            chats.filter { it.kind == ChatKind.MESHCORE }.forEach {
                add(Who.MESHCORE, it.id.substringAfter(':').take(16).uppercase(), it.title)
            }
            for (e in log.asReversed()) {
                when (val r = e.reply) {
                    is AdminReply.Clients -> r.list.forEach { add(Who.RADIO, it.id, "@" + it.alias) }
                    is AdminReply.Admins -> r.list.forEach { a -> a.id?.let { add(a.who, it, a.name) } }
                    is AdminReply.Requests -> r.list.forEach { q ->
                        q.id?.let { add(q.who, it, if (q.who == Who.RADIO) "@" + q.name else q.name) }
                    }
                    else -> {}
                }
            }
            return out.values.toList()
        }

        /** Signal usernames: letters, digits, `_`, then `.` and at least two digits. */
        private val USERNAME = Regex("^[A-Za-z_][A-Za-z0-9_]{2,31}\\.[0-9]{2,9}$")

        /** Oldest first: status and notices set them, our own changes apply unless refused. */
        fun switchesFrom(log: List<AdminEntry>): Set<PharosSwitch>? {
            var s: Set<PharosSwitch>? = null
            for (e in log) {
                when (val r = e.reply) {
                    is AdminReply.Status -> s = r.switches
                    is AdminReply.SwitchesChanged -> s = r.switches
                    else -> {
                        val c = e.command as? AdminCommand.Set ?: continue
                        if (r != null && (r as? AdminReply.Done)?.outcome != AdminOutcome.OK) continue
                        val base = s ?: continue
                        s = if (c.on) base + c.switch else base - c.switch
                    }
                }
            }
            return s
        }

        fun requestsFrom(log: List<AdminEntry>): List<RequestEntry>? {
            val i = log.indexOfLast { it.reply is AdminReply.Requests }
            if (i < 0) return null
            val decided = log.drop(i + 1).mapNotNull {
                when (val c = it.command) {
                    is AdminCommand.Accept -> c.num
                    is AdminCommand.Refuse -> c.num
                    else -> null
                }
            }.toSet()
            return (log[i].reply as AdminReply.Requests).list.filterNot { it.num in decided }
        }
    }
}
