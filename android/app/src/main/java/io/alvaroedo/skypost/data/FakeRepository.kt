package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.core.Card
import io.alvaroedo.skypost.core.KeryxCore
import io.alvaroedo.skypost.core.ParsedUri
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.model.Contact
import io.alvaroedo.skypost.model.Delivery
import io.alvaroedo.skypost.model.Discovered
import io.alvaroedo.skypost.model.Group
import io.alvaroedo.skypost.model.Identity
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.PharosAccess
import io.alvaroedo.skypost.model.Registration
import io.alvaroedo.skypost.model.Scanned
import io.alvaroedo.skypost.model.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stand-in for the real repository: no radio; state kept in [store] so the
 * demo survives restarts.
 * Sent messages are "ACKed" after [ackDelayMs]. With [demo], a new identity
 * starts with sample chats so every screen has something to show.
 */
@OptIn(FlowPreview::class)
class FakeRepository(
    private val core: KeryxCore,
    private val scope: CoroutineScope,
    private val demo: Boolean = true,
    private val ackDelayMs: Long = 2_500,
    private val now: () -> Long = System::currentTimeMillis,
    private val store: DemoStore? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Repository {
    private val _identity = MutableStateFlow<Identity?>(null)
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _pharos = MutableStateFlow<List<Pharos>>(emptyList())
    private val _settings = MutableStateFlow(Settings())
    private val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private var nextId = 1L

    init {
        store?.load()?.let(::restore)
        if (store != null) {
            scope.launch {
                merge(_identity, _chats, _contacts, _groups, _pharos, _settings, messages)
                    .drop(1)
                    .debounce(300)
                    .collect {
                        val snap = snapshot()
                        withContext(io) { store.save(snap) }
                    }
            }
        }
    }

    private fun snapshot() = DemoSnapshot(
        identity = _identity.value,
        chats = _chats.value,
        contacts = _contacts.value,
        groups = _groups.value,
        pharos = _pharos.value,
        settings = _settings.value,
        messages = messages.value,
    )

    /** Messages still waiting for an ACK when the app stopped are lost. */
    private fun restore(s: DemoSnapshot) {
        val pending = { m: ChatMessage -> if (m.delivery == Delivery.PENDING) m.copy(delivery = Delivery.FAILED) else m }
        _identity.value = s.identity
        _chats.value = s.chats.map { c -> c.copy(last = c.last?.let(pending)) }
        _contacts.value = s.contacts
        _groups.value = s.groups
        _pharos.value = s.pharos
        _settings.value = s.settings
        messages.value = s.messages.mapValues { (_, l) -> l.map(pending) }
        nextId = (s.messages.values.flatten().maxOfOrNull { it.id } ?: 0) + 1
    }

    override val identity: StateFlow<Identity?> = _identity.asStateFlow()
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    override val pharos: StateFlow<List<Pharos>> = _pharos.asStateFlow()
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

    override fun messages(chatId: String): Flow<List<ChatMessage>> = messages.map { it[chatId].orEmpty() }

    override fun createIdentity(alias: String, callsign: String?) {
        val card = core.createIdentity(alias, callsign)
        _identity.value = Identity(card, core.identityUri(card), core.safetyNumber(card.key))
        if (demo) seedDemo()
    }

    override fun updateProfile(alias: String, callsign: String?) {
        _identity.update { id ->
            id?.let {
                val card = it.card.copy(alias = alias, callsign = callsign)
                it.copy(card = card, uri = core.identityUri(card))
            }
        }
    }

    override fun validAlias(alias: String) = core.validAlias(alias)

    override fun validCallsign(callsign: String) = core.validCallsign(callsign)

    override fun framesFor(chatId: String, text: String): Int =
        core.framesFor(text, bridged = chat(chatId)?.kind?.bridged == true)

    override fun phrases(): List<String> = core.phrases()

    private fun chat(id: String): Chat? = _chats.value.firstOrNull { it.id == id }

    private fun activePharos(): Pharos? = _pharos.value.firstOrNull { it.active }

    private fun post(chatId: String, msg: ChatMessage) {
        messages.update { it + (chatId to (it[chatId].orEmpty() + msg)) }
        _chats.update { list ->
            list.map { c ->
                if (c.id != chatId) {
                    c
                } else {
                    c.copy(last = msg, unread = if (msg.outgoing) c.unread else c.unread + 1)
                }
            }.sortedByDescending { it.last?.atMillis ?: 0 }
        }
    }

    private fun setDelivery(chatId: String, id: Long, d: Delivery) {
        messages.update { all ->
            all + (chatId to all[chatId].orEmpty().map { if (it.id == id) it.copy(delivery = d) else it })
        }
        _chats.update { list ->
            list.map { c -> if (c.id == chatId && c.last?.id == id) c.copy(last = c.last.copy(delivery = d)) else c }
        }
    }

    private fun sendOut(chatId: String, text: String, sos: Boolean = false) {
        val c = chat(chatId) ?: return
        val msg = ChatMessage(
            id = nextId++,
            chatId = chatId,
            outgoing = true,
            text = text,
            atMillis = now(),
            auth = if ((c.encrypted || _settings.value.encryptAll) && !sos) Auth.ENCRYPTED else Auth.SIGNED,
            sos = sos,
        )
        post(chatId, msg)
        val reachable = !c.kind.bridged || activePharos() != null
        scope.launch {
            delay(ackDelayMs)
            setDelivery(chatId, msg.id, if (reachable) Delivery.ACKED else Delivery.FAILED)
        }
    }

    override fun send(chatId: String, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) sendOut(chatId, t)
    }

    // TODO(phase 2): GNSS position.
    override fun sendStatusOk(chatId: String) = sendOut(chatId, "Estoy bien · 42.63110, 0.65880")

    override fun sendPosition(chatId: String) = sendOut(chatId, "Posición · 42.63110, 0.65880")

    override fun setEncrypted(chatId: String, on: Boolean) {
        _chats.update { list -> list.map { if (it.id == chatId) it.copy(encrypted = on) else it } }
    }

    override fun markRead(chatId: String) {
        _chats.update { list -> list.map { if (it.id == chatId) it.copy(unread = 0) else it } }
    }

    override fun sosTargets(): List<Chat> {
        val chosen = _settings.value.sosChats
        return _chats.value.filter { if (chosen == null) it.kind == ChatKind.GROUP else it.id in chosen }
    }

    override fun sos(): Int {
        val targets = sosTargets()
        targets.forEach { sendOut(it.id, "SOS · 42.63110, 0.65880", sos = true) }
        return targets.size
    }

    override fun setSosChats(ids: Set<String>?) = _settings.update { it.copy(sosChats = ids) }

    override fun setEncryptAll(on: Boolean) = _settings.update { it.copy(encryptAll = on) }

    override fun parse(text: String): Scanned? {
        MeshCoreQr.parse(text)?.let { return it }
        return when (val p = core.parseUri(text)) {
            is ParsedUri.Id ->
                if (p.card.isPharos) {
                    Scanned.PharosCard(p.card, core.safetyNumber(p.card.key))
                } else {
                    Scanned.Person(p.card, core.safetyNumber(p.card.key))
                }
            is ParsedUri.Group -> Scanned.GroupInvite(p.name, p.uri)
            null -> null
        }
    }

    private fun ensureChat(chat: Chat): String {
        if (chat(chat.id) == null) _chats.update { it + chat }
        return chat.id
    }

    override fun import(scanned: Scanned): String? = when (scanned) {
        is Scanned.Person -> {
            if (_contacts.value.none { it.card.key == scanned.card.key }) {
                _contacts.update { it + Contact(scanned.card, verified = true, safetyNumber = scanned.safetyNumber) }
            }
            chatWith(scanned.card.key)
        }
        is Scanned.PharosCard -> {
            addPharos(scanned.card, verified = true, snr = null, bridges = ChatKind.entries.filter { it.bridged }.toSet())
            null
        }
        is Scanned.GroupInvite -> {
            val id = "g" + scanned.uri.hashCode().toUInt()
            if (_groups.value.none { it.id == id }) {
                val me = _identity.value?.card?.alias.orEmpty()
                _groups.update { it + Group(id, scanned.name, scanned.uri, listOf(me)) }
            }
            chatInGroup(id)
        }
        is Scanned.MeshCore -> {
            if (activePharos() == null) {
                null
            } else {
                ensureChat(
                    Chat(
                        id = "meshcore:${scanned.key}",
                        kind = ChatKind.MESHCORE,
                        title = scanned.name,
                        detail = scanned.key.take(12),
                        ref = activePharos()?.card?.key,
                    ),
                )
            }
        }
    }

    override fun createGroup(name: String): String {
        val uri = core.newGroupUri(name)
        return import(Scanned.GroupInvite(name, uri))!!
    }

    override fun chatWith(contactKey: String): String {
        val c = _contacts.value.first { it.card.key == contactKey }
        return ensureChat(Chat("direct:$contactKey", ChatKind.DIRECT, c.card.alias, c.card.callsign, ref = contactKey))
    }

    override fun chatInGroup(groupId: String): String {
        val g = _groups.value.first { it.id == groupId }
        return ensureChat(Chat("group:$groupId", ChatKind.GROUP, g.name, ref = g.uri))
    }

    override fun openBridged(kind: ChatKind, address: String, name: String?): String? {
        val p = activePharos() ?: return null
        val shown = if (address.all { it.isDigit() }) "+$address" else address
        return ensureChat(
            Chat("${kind.name.lowercase()}:$address", kind, name ?: shown, detail = shown, ref = p.card.key),
        )
    }

    override fun normalizePhone(input: String): String = core.normalizePhone(input)

    override suspend fun discover(): List<Discovered> {
        delay(1_500)
        val known = _pharos.value.map { it.card.key }.toSet()
        val casa = Card("H7KDQ2AZ4M3WLN5RCX6T7YB2EV4GJ5PS3U2O6DKF7Q2NZA5HM4BA", "casa", "EA5YYY", isPharos = true)
        val club = Card("Q6M3T2ZL7VX4NK5RB2C6D7YE3HW4JA5PS2U6O7FK3G2MZQ4LN5TA", "radioclub", "EA5RCV", isPharos = true)
        return listOf(
            Discovered(casa, snrDb = -14, trusted = casa.key in known, bridges = setOf(ChatKind.SIGNAL, ChatKind.WHATSAPP)),
            Discovered(
                club,
                snrDb = -21,
                trusted = club.key in known,
                bridges = setOf(ChatKind.MESHCORE),
                access = PharosAccess.CODE,
            ),
        )
    }

    private fun addPharos(
        card: Card,
        verified: Boolean,
        snr: Int?,
        bridges: Set<ChatKind>,
        registration: Registration = Registration.REGISTERED,
    ) {
        if (_pharos.value.any { it.card.key == card.key }) return
        val first = _pharos.value.isEmpty()
        _pharos.update {
            it + Pharos(
                card = card,
                verified = verified,
                active = first,
                lastHeardMillis = snr?.let { now() },
                snrDb = snr,
                registration = registration,
                bridges = bridges,
                safetyNumber = core.safetyNumber(card.key),
            )
        }
    }

    // The real one sends Register (with the access-code proof) and waits for the Notice.
    private fun registrationFor(access: PharosAccess, code: String?) = when (access) {
        PharosAccess.OPEN -> Registration.REGISTERED
        PharosAccess.CODE -> if (code.isNullOrBlank()) Registration.NEEDS_CODE else Registration.REGISTERED
        PharosAccess.CLOSED -> Registration.PENDING
    }

    override fun trust(found: Discovered, code: String?) = addPharos(
        found.card,
        verified = false,
        snr = found.snrDb,
        bridges = found.bridges,
        registration = registrationFor(found.access, code),
    )

    override fun register(key: String, code: String?) {
        _pharos.update { list ->
            list.map { if (it.card.key == key) it.copy(registration = registrationFor(PharosAccess.CODE, code)) else it }
        }
    }

    override fun setActivePharos(key: String) {
        _pharos.update { list -> list.map { it.copy(active = it.card.key == key) } }
    }

    override fun markVerified(key: String) {
        _pharos.update { list -> list.map { if (it.card.key == key) it.copy(verified = true) else it } }
    }

    override fun removePharos(key: String) {
        _pharos.update { list ->
            val rest = list.filter { it.card.key != key }
            if (rest.none { it.active } && rest.isNotEmpty()) rest.mapIndexed { i, p -> p.copy(active = i == 0) } else rest
        }
    }

    override suspend fun ping(): Boolean {
        val p = activePharos() ?: return false
        delay(1_500)
        _pharos.update { list ->
            list.map { if (it.card.key == p.card.key) it.copy(lastHeardMillis = now(), snrDb = -16) else it }
        }
        return true
    }

    override fun setPingInterval(minutes: Int) = _settings.update { it.copy(pingIntervalMin = minutes) }

    override suspend fun exportBackup(passphrase: String): ByteArray {
        delay(300)
        // TODO(phase 2): keryx::backup::seal over the keyring export + settings.
        return "SKYPOSTB1 fake backup".toByteArray()
    }

    override suspend fun restoreBackup(data: ByteArray, passphrase: String): Boolean {
        delay(300)
        if (!data.decodeToString().startsWith("SKYPOSTB1") || passphrase.length < 8) return false
        createIdentity("restaurado", null)
        return true
    }

    private fun seedDemo() {
        val t = now()
        val min = 60_000L
        val bea = Card("B6VGQTWSRBT3S72KNIW64XPYZ2LU45VXAGHD6IVBYTHSM6CXB4QA", "bea", "EA5BBB", isPharos = false)
        _contacts.value = listOf(Contact(bea, verified = true, safetyNumber = core.safetyNumber(bea.key)))
        addPharos(
            Card("H7KDQ2AZ4M3WLN5RCX6T7YB2EV4GJ5PS3U2O6DKF7Q2NZA5HM4BA", "casa", "EA5YYY", isPharos = true),
            verified = true,
            snr = -14,
            bridges = setOf(ChatKind.SIGNAL, ChatKind.WHATSAPP, ChatKind.MESHCORE),
        )
        _pharos.update { list -> list.map { it.copy(lastHeardMillis = t - 4 * min) } }
        val familia = createGroup("Familia")
        val direct = chatWith(bea.key)
        val mama = openBridged(ChatKind.SIGNAL, "34611111111", "Mamá")!!
        val papa = openBridged(ChatKind.WHATSAPP, "34622222222", "Papá")!!
        val refugio = import(Scanned.MeshCore("Refugio Góriz", "a1b2c3d4e5f6".repeat(5) + "a1b2"))!!
        fun rx(chat: String, text: String, ago: Long, auth: Auth = Auth.SIGNED, sender: String? = null) =
            post(chat, ChatMessage(nextId++, chat, false, text, t - ago * min, auth, sender = sender))
        fun tx(chat: String, text: String, ago: Long, auth: Auth = Auth.SIGNED) =
            post(chat, ChatMessage(nextId++, chat, true, text, t - ago * min, auth, Delivery.ACKED))
        tx(familia, "Hemos acampado", 190)
        rx(familia, "¡Genial! ¿Qué tal el tiempo?", 150, sender = "bea")
        tx(refugio, "¿Queda sitio esta noche para dos?", 140)
        rx(refugio, "Sí, os guardamos dos plazas", 120)
        tx(papa, "Mañana bajamos", 95)
        tx(direct, "¿Me oyes bien en 40 m?", 70)
        rx(direct, "Te copio a −18 dB, perfecto", 65, Auth.ENCRYPTED)
        tx(mama, "Todo bien, sin novedad", 30)
        rx(mama, "Me alegro. Avisad al llegar", 12)
    }
}
