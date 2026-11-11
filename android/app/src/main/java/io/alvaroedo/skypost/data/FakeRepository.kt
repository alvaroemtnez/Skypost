package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.radio.CustomRadio
import io.alvaroedo.skypost.radio.RadioModel
import io.alvaroedo.skypost.core.Card
import io.alvaroedo.skypost.core.KeryxCore
import io.alvaroedo.skypost.core.ParsedUri
import io.alvaroedo.skypost.model.AdminCommand
import io.alvaroedo.skypost.model.AdminEntry
import io.alvaroedo.skypost.model.Party
import io.alvaroedo.skypost.model.AdminOutcome
import io.alvaroedo.skypost.model.AdminReply
import io.alvaroedo.skypost.model.AdminTarget
import io.alvaroedo.skypost.model.ClientEntry
import io.alvaroedo.skypost.model.PharosSwitch
import io.alvaroedo.skypost.model.RequestEntry
import io.alvaroedo.skypost.model.Who
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.Recent
import io.alvaroedo.skypost.model.DeletedChats
import io.alvaroedo.skypost.model.Blocked
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.model.Contact
import io.alvaroedo.skypost.model.Delivery
import io.alvaroedo.skypost.model.Discovered
import io.alvaroedo.skypost.model.GeoFix
import io.alvaroedo.skypost.model.Group
import io.alvaroedo.skypost.model.Identity
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.PharosAccess
import io.alvaroedo.skypost.model.Registration
import io.alvaroedo.skypost.model.Scanned
import io.alvaroedo.skypost.model.Settings
import io.alvaroedo.skypost.model.VerifyResult
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
    private val _recents = MutableStateFlow<List<Recent>>(emptyList())
    private val _blocked = MutableStateFlow<List<Blocked>>(emptyList())
    private val _adminLog = MutableStateFlow<List<AdminEntry>>(emptyList())
    private var nextId = 1L

    init {
        store?.load()?.let(::restore)
        if (store != null) {
            scope.launch {
                merge(_identity, _chats, _contacts, _groups, _pharos, _settings, messages, _recents, _blocked, _adminLog)
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
        recents = _recents.value,
        blocked = _blocked.value,
        adminEntries = _adminLog.value,
    )

    /** Messages still waiting for an ACK when the app stopped are lost. */
    private fun restore(s: DemoSnapshot) {
        val pending = { m: ChatMessage -> if (m.delivery == Delivery.PENDING) m.copy(delivery = Delivery.FAILED) else m }
        _identity.value = s.identity?.let { it.copy(safetyNumber = core.safetyNumber(it.card.key)) }
        _chats.value = s.chats.map { c -> c.copy(last = c.last?.let(pending)) }
        // Safety codes come from the core: recompute (the fake's format changed once).
        _contacts.value = s.contacts.map { it.copy(safetyNumber = core.safetyNumber(it.card.key)) }
        _groups.value = s.groups
        _pharos.value = s.pharos.map { it.copy(safetyNumber = core.safetyNumber(it.card.key)) }
        _settings.value = s.settings
        messages.value = s.messages.mapValues { (_, l) -> l.map(pending) }
        _recents.value = s.recents
        _blocked.value = s.blocked
        _adminLog.value = s.adminEntries
        // Messages and admin log entries share the counter.
        nextId = maxOf(s.messages.values.flatten().maxOfOrNull { it.id } ?: 0, s.adminEntries.maxOfOrNull { it.id } ?: 0) + 1
    }

    override val identity: StateFlow<Identity?> = _identity.asStateFlow()
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    override val pharos: StateFlow<List<Pharos>> = _pharos.asStateFlow()
    override val settings: StateFlow<Settings> = _settings.asStateFlow()
    override val recents: StateFlow<List<Recent>> = _recents.asStateFlow()
    override val blocked: StateFlow<List<Blocked>> = _blocked.asStateFlow()

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

    override fun framesForPhrase(chatId: String): Int =
        core.framesForPhrase(bridged = chat(chatId)?.kind?.bridged == true)

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
            }
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

    private fun sendOut(chatId: String, text: String, sos: Boolean = false, phrase: Int? = null, position: GeoFix? = null) {
        val c = chat(chatId)?.takeUnless { it.blocked } ?: return
        val msg = ChatMessage(
            phrase = phrase,
            position = position,
            id = nextId++,
            chatId = chatId,
            outgoing = true,
            text = text,
            atMillis = now(),
            auth = if ((c.encrypted || _settings.value.encryptAll) && !sos) Auth.ENCRYPTED else Auth.SIGNED,
            sos = sos,
        )
        post(chatId, msg)
        val reachable = !c.kind.bridged || activePharos()?.takeUnless { it.blocked } != null
        scope.launch {
            delay(ackDelayMs)
            setDelivery(chatId, msg.id, if (reachable) Delivery.ACKED else Delivery.FAILED)
        }
    }

    override fun send(chatId: String, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) sendOut(chatId, t)
    }

    override fun sendPhrase(chatId: String, id: Int) {
        core.phrases().getOrNull(id)?.let { sendOut(chatId, it, phrase = id) }
    }

    // The real one sends Message::StatusOk / Position with a 6-byte position.
    override fun sendStatusOk(chatId: String, fix: GeoFix?) = sendOut(chatId, core.phrases()[0], phrase = 0, position = fix)

    override fun sendPosition(chatId: String, fix: GeoFix) = sendOut(chatId, "", position = fix)

    override fun setEncrypted(chatId: String, on: Boolean) {
        _chats.update { list -> list.map { if (it.id == chatId) it.copy(encrypted = on) else it } }
    }

    override fun markRead(chatId: String) {
        _chats.update { list -> list.map { if (it.id == chatId) it.copy(unread = 0, markedUnread = false) else it } }
    }

    private fun refreshLast(chatId: String) {
        val last = messages.value[chatId].orEmpty().lastOrNull()
        _chats.update { list -> list.map { if (it.id == chatId) it.copy(last = last) else it } }
    }

    override fun retry(chatId: String, messageId: Long) {
        val c = chat(chatId)?.takeUnless { it.blocked } ?: return
        val m = messages.value[chatId].orEmpty().firstOrNull { it.id == messageId && it.outgoing } ?: return
        if (m.delivery != Delivery.FAILED) return
        setDelivery(chatId, messageId, Delivery.PENDING)
        val reachable = !c.kind.bridged || activePharos()?.takeUnless { it.blocked } != null
        scope.launch {
            delay(ackDelayMs)
            setDelivery(chatId, messageId, if (reachable) Delivery.ACKED else Delivery.FAILED)
        }
    }

    override fun deleteMessages(chatId: String, ids: Set<Long>): List<ChatMessage> {
        val gone = messages.value[chatId].orEmpty().filter { it.id in ids }
        messages.update { all -> all + (chatId to all[chatId].orEmpty().filterNot { it.id in ids }) }
        refreshLast(chatId)
        return gone
    }

    override fun restoreMessages(chatId: String, messages: List<ChatMessage>) {
        this.messages.update { all ->
            all + (chatId to (all[chatId].orEmpty() + messages).distinctBy { it.id }.sortedBy { it.atMillis })
        }
        refreshLast(chatId)
    }

    override fun setArchived(ids: Set<String>, on: Boolean) {
        _chats.update { list -> list.map { if (it.id in ids) it.copy(archived = on, pinnedAt = it.pinnedAt.takeUnless { on }) else it } }
    }

    override fun setMarkedUnread(ids: Set<String>, on: Boolean) {
        _chats.update { list ->
            list.map { if (it.id in ids) it.copy(markedUnread = on, unread = if (on) it.unread else 0) else it }
        }
    }

    override fun setPinned(id: String, on: Boolean): Boolean {
        if (on && _chats.value.count { it.pinnedAt != null && it.id != id } >= Chat.MAX_PINNED) return false
        _chats.update { list ->
            list.map { if (it.id == id) it.copy(pinnedAt = if (on) now() else null, archived = it.archived && !on) else it }
        }
        return true
    }

    override fun deleteChats(ids: Set<String>): DeletedChats {
        val gone = DeletedChats(_chats.value.filter { it.id in ids }, messages.value.filterKeys { it in ids })
        _chats.update { list -> list.filterNot { it.id in ids } }
        messages.update { it - ids }
        return gone
    }

    override fun restoreChats(deleted: DeletedChats) {
        _chats.update { list -> list + deleted.chats.filter { c -> list.none { it.id == c.id } } }
        messages.update { it + deleted.messages }
    }

    /** Block key of a chat: its contact, group, or bridged address. */
    private fun blockKey(c: Chat) = when (c.kind) {
        ChatKind.DIRECT -> "contact:${c.ref}"
        else -> c.id
    }

    private fun setBlocked(key: String, title: String, kind: ChatKind?, on: Boolean) {
        _blocked.update { list -> list.filterNot { it.key == key } + if (on) listOf(Blocked(key, title, kind)) else emptyList() }
        applyBlocks()
    }

    /** Copy the block list onto chats, contacts, groups and Pharos. */
    private fun applyBlocks() {
        val keys = _blocked.value.map { it.key }.toSet()
        _chats.update { list -> list.map { it.copy(blocked = blockKey(it) in keys) } }
        _contacts.update { list -> list.map { it.copy(blocked = "contact:${it.card.key}" in keys) } }
        _groups.update { list -> list.map { it.copy(blocked = "group:${it.id}" in keys) } }
        _pharos.update { list ->
            list.map { p ->
                val b = "pharos:${p.card.key}" in keys
                p.copy(blocked = b, active = p.active && !b)
            }
        }
    }

    override fun setChatBlocked(chatId: String, on: Boolean) {
        val c = chat(chatId) ?: return
        setBlocked(blockKey(c), c.title, c.kind, on)
    }

    override fun setContactBlocked(key: String, on: Boolean) {
        val c = _contacts.value.firstOrNull { it.card.key == key } ?: return
        setBlocked("contact:$key", c.card.alias, ChatKind.DIRECT, on)
    }

    override fun setContactVerified(key: String, on: Boolean) {
        _contacts.update { list -> list.map { if (it.card.key == key) it.copy(verified = on) else it } }
    }

    override fun verifyByScan(key: String, text: String): VerifyResult {
        val card = when (val s = parse(text)) {
            is Scanned.Person -> s.card
            is Scanned.PharosCard -> s.card
            else -> return VerifyResult.NOT_A_CODE
        }
        if (card.key != key) return VerifyResult.OTHER_KEY
        if (card.isPharos) setPharosVerified(key, true) else setContactVerified(key, true)
        return VerifyResult.VERIFIED
    }

    override fun setGroupBlocked(id: String, on: Boolean) {
        val g = _groups.value.firstOrNull { it.id == id } ?: return
        setBlocked("group:$id", g.name, ChatKind.GROUP, on)
    }

    override fun setRecentBlocked(recent: Recent, on: Boolean) =
        setBlocked(recent.chatId, recent.shown, recent.kind, on)

    override fun setPharosBlocked(key: String, on: Boolean) {
        val p = _pharos.value.firstOrNull { it.card.key == key } ?: return
        setBlocked("pharos:$key", p.card.alias, null, on)
    }

    override fun unblock(key: String) {
        _blocked.update { list -> list.filterNot { it.key == key } }
        applyBlocks()
    }

    override fun forgetRecent(recent: Recent) {
        _recents.update { list -> list.filterNot { it == recent } }
    }

    override fun removeContact(key: String) {
        _contacts.update { list -> list.filterNot { it.card.key == key } }
    }

    override fun leaveGroup(id: String) {
        _groups.update { list -> list.filterNot { it.id == id } }
    }

    private fun remember(kind: ChatKind, address: String, name: String?) {
        val r = Recent(kind, address, name)
        _recents.update { list -> listOf(r) + list.filterNot { it.kind == kind && it.address == address } }
    }

    override suspend fun pingChat(chatId: String): Int? {
        val c = chat(chatId) ?: return null
        if (c.blocked || c.kind == ChatKind.GROUP) return null
        delay(1_500)
        return when (c.kind) {
            ChatKind.DIRECT -> -17
            else -> if (ping()) activePharos()?.snrDb else null
        }
    }

    override fun sosTargets(): List<Chat> {
        val chosen = _settings.value.sosChats
        return _chats.value.filter { if (chosen == null) it.kind == ChatKind.GROUP else it.id in chosen }
    }

    override fun sos(fix: GeoFix?): Int {
        val targets = sosTargets()
        targets.forEach { sendOut(it.id, "SOS", sos = true, position = fix) }
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
        if (chat(chat.id) == null) {
            _chats.update { it + chat }
            applyBlocks()
        }
        return chat.id
    }

    override fun import(scanned: Scanned, inPerson: Boolean): String? = when (scanned) {
        is Scanned.Person -> {
            if (_contacts.value.none { it.card.key == scanned.card.key }) {
                _contacts.update { it + Contact(scanned.card, verified = inPerson, safetyNumber = scanned.safetyNumber) }
                applyBlocks()
            } else if (inPerson) {
                setContactVerified(scanned.card.key, true)
            }
            chatWith(scanned.card.key)
        }
        is Scanned.PharosCard -> {
            addPharos(scanned.card, verified = inPerson, snr = null, bridges = ChatKind.entries.filter { it.bridged }.toSet())
            if (inPerson) setPharosVerified(scanned.card.key, true)
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
                remember(ChatKind.MESHCORE, scanned.key, scanned.name)
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

    override fun uriOf(card: Card): String = core.identityUri(card)

    override fun createGroup(name: String): String {
        val uri = core.newGroupUri(name)
        return import(Scanned.GroupInvite(name, uri), inPerson = true)!!
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
        remember(kind, address, name)
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
        val refugio = Card("R3FUG10BACH1MANA2Z4M3WLN5RCX6T7YB2EV4GJ5PS3U2O6DKF7Q", "refugio", "EA2RFG", isPharos = true)
        return listOf(
            Discovered(casa, snrDb = -14, trusted = casa.key in known, bridges = setOf(ChatKind.SIGNAL, ChatKind.WHATSAPP)),
            Discovered(
                club,
                snrDb = -21,
                trusted = club.key in known,
                bridges = setOf(ChatKind.MESHCORE),
                access = PharosAccess(open = false, code = true),
            ),
            Discovered(
                refugio,
                snrDb = -19,
                trusted = refugio.key in known,
                bridges = setOf(ChatKind.MESHCORE),
                access = PharosAccess(open = false, requests = true),
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
    private fun registrationFor(access: PharosAccess, code: String?) = when {
        access.open -> Registration.REGISTERED
        access.code && !code.isNullOrBlank() -> Registration.REGISTERED
        access.requests -> Registration.PENDING
        access.code -> Registration.NEEDS_CODE
        else -> Registration.REFUSED
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
            list.map {
                if (it.card.key == key) {
                    it.copy(registration = if (code.isNullOrBlank()) Registration.PENDING else Registration.REGISTERED)
                } else {
                    it
                }
            }
        }
    }

    override fun setActivePharos(key: String) {
        _pharos.update { list -> list.map { it.copy(active = it.card.key == key && !it.blocked) } }
    }

    override fun setPharosVerified(key: String, on: Boolean) {
        _pharos.update { list -> list.map { if (it.card.key == key) it.copy(verified = on) else it } }
    }

    override fun removePharos(key: String) {
        _pharos.update { list ->
            val rest = list.filter { it.card.key != key }
            if (rest.none { it.active } && rest.isNotEmpty()) rest.mapIndexed { i, p -> p.copy(active = i == 0) } else rest
        }
    }

    override fun adminLog(pharosKey: String): Flow<List<AdminEntry>> =
        _adminLog.map { log -> log.filter { it.pharosKey == pharosKey } }

    private fun isAdmin(pharosKey: String) = _pharos.value.any { it.card.key == pharosKey && it.admin }

    /** What the demo Pharos knows, to answer admin commands like `pharos::admin` would. */
    private val fakeSwitches = mutableMapOf<String, Set<PharosSwitch>>()
    private val fakeRequests = mutableMapOf<String, MutableList<RequestEntry>>()

    // The demo includes someone else calling themselves "bea": the app must
    // tell them apart from the real Bea by key.
    private fun requestsOf(key: String) = fakeRequests.getOrPut(key) {
        mutableListOf(
            RequestEntry(3, Who.RADIO, "tio", core.userId("TIO-DEMO-KEY")),
            RequestEntry(4, Who.WHATSAPP, "Tía Carmen +34633333333"),
            RequestEntry(5, Who.MESHCORE, "Refugio Bachimaña", "9F3C2A1B7D604E58"),
            RequestEntry(6, Who.RADIO, "bea", core.userId("NOT-THE-REAL-BEA")),
        ).apply {
            // And the real Bea, one of our contacts, asking too.
            _contacts.value.firstOrNull { it.card.alias == "bea" }?.let {
                add(RequestEntry(7, Who.RADIO, "bea", core.userId(it.card.key)))
            }
        }
    }

    override fun userId(key: String): String = core.userId(key)

    private val fakeClients = mutableMapOf<String, MutableList<ClientEntry>>()
    private val fakeAdmins = mutableMapOf<String, MutableList<Party>>()
    private val fakeBlocked = mutableMapOf<String, MutableList<Party>>()

    private fun me() = _identity.value?.card

    private fun clientsOf(key: String) = fakeClients.getOrPut(key) {
        val bea = _contacts.value.firstOrNull { it.card.alias == "bea" }?.card?.key?.let(core::userId) ?: core.userId("bea")
        mutableListOf(
            ClientEntry(Registration.REGISTERED, 41, "bea", bea),
            ClientEntry(Registration.PENDING, 12, "tio", core.userId("TIO-DEMO-KEY")),
            ClientEntry(Registration.REGISTERED, 0, me()?.alias.orEmpty(), me()?.key?.let(core::userId).orEmpty()),
        )
    }

    private fun adminsOf(key: String) = fakeAdmins.getOrPut(key) {
        mutableListOf(
            Party(AdminTarget.User(me()?.key?.let(core::userId).orEmpty(), "@" + me()?.alias.orEmpty()), "@" + me()?.alias.orEmpty()),
            Party(AdminTarget.Contact(ChatKind.SIGNAL, "34600000000"), "+34600000000"),
        )
    }

    private fun blockedOf(key: String) = fakeBlocked.getOrPut(key) {
        mutableListOf(
            Party(AdminTarget.User(core.userId("SPAMMER"), "@ruido"), "@ruido"),
            Party(AdminTarget.Contact(ChatKind.WHATSAPP, "34699999999"), "+34699999999"),
        )
    }

    private fun AdminTarget.matches(other: AdminTarget) = when {
        this is AdminTarget.User && other is AdminTarget.User -> id == other.id
        this is AdminTarget.MeshCore && other is AdminTarget.MeshCore -> id == other.id
        else -> this == other
    }

    private fun switchesOf(key: String) =
        fakeSwitches.getOrPut(key) { setOf(PharosSwitch.REQUESTS, PharosSwitch.LOGIN) }

    /** Logs [command] and fills in the Pharos's reply after the ACK delay. */
    private fun adminRoundTrip(pharosKey: String, command: AdminCommand, reply: () -> AdminReply): Long? {
        if (_pharos.value.none { it.card.key == pharosKey && !it.blocked }) return null
        val entry = AdminEntry(nextId++, pharosKey, command, now())
        _adminLog.update { (it + entry).takeLast(ADMIN_LOG_MAX) }
        scope.launch {
            delay(ackDelayMs)
            val r = reply()
            _adminLog.update { log -> log.map { if (it.id == entry.id) it.copy(reply = r) else it } }
        }
        return entry.id
    }

    override fun revealsPersonalData(command: AdminCommand) = core.revealsPersonalData(command)

    // The real one sends Message::Admin and decodes Message::AdminReply.
    override fun sendAdmin(pharosKey: String, command: AdminCommand): Long? = adminRoundTrip(pharosKey, command) {
        val ok = AdminReply.Done(AdminOutcome.OK)
        val me = "@" + _identity.value?.card?.alias.orEmpty()
        if (!isAdmin(pharosKey)) return@adminRoundTrip AdminReply.Done(AdminOutcome.NOT_ADMIN)
        when (command) {
            AdminCommand.Status -> AdminReply.Status(switchesOf(pharosKey), 3, requestsOf(pharosKey).size, 1, 2, 4)
            AdminCommand.Requests -> AdminReply.Requests(requestsOf(pharosKey).toList())
            is AdminCommand.Accept, is AdminCommand.Refuse -> {
                val num = (command as? AdminCommand.Accept)?.num ?: (command as AdminCommand.Refuse).num
                val r = requestsOf(pharosKey).firstOrNull { it.num == num }
                if (r == null) {
                    AdminReply.Done(AdminOutcome.UNKNOWN, "#$num")
                } else {
                    requestsOf(pharosKey).remove(r)
                    AdminReply.Done(AdminOutcome.OK, listOfNotNull(r.name, r.id?.let { "${it.take(4)} ${it.substring(4, 8)}" }).joinToString(" · "))
                }
            }
            AdminCommand.Clients -> AdminReply.Clients(clientsOf(pharosKey).toList())
            AdminCommand.Admins -> AdminReply.Admins(adminsOf(pharosKey).toList())
            AdminCommand.Blocked -> AdminReply.Blocked(blockedOf(pharosKey).toList())
            is AdminCommand.Remove -> {
                val t = command.target as? AdminTarget.User
                val gone = clientsOf(pharosKey).removeAll { t != null && it.id == t.id }
                if (gone || command.target is AdminTarget.Contact) ok else AdminReply.Done(AdminOutcome.UNKNOWN)
            }
            is AdminCommand.Block -> {
                val list = blockedOf(pharosKey)
                if (command.on) {
                    if (list.none { it.target.matches(command.target) }) {
                        list += Party(command.target, (command.target as? AdminTarget.User)?.name ?: "?")
                    }
                } else {
                    list.removeAll { it.target.matches(command.target) }
                }
                ok
            }
            is AdminCommand.Set -> {
                val s = switchesOf(pharosKey)
                fakeSwitches[pharosKey] = if (command.on) s + command.switch else s - command.switch
                ok
            }
            is AdminCommand.RemoveAdmin ->
                if (adminsOf(pharosKey).size == 1 && PharosSwitch.LOGIN !in switchesOf(pharosKey)) {
                    AdminReply.Done(AdminOutcome.LAST_ADMIN, me)
                } else {
                    adminsOf(pharosKey).removeAll { it.target.matches(command.target) }
                    ok
                }
            is AdminCommand.AddAdmin -> {
                val t = command.target
                if (adminsOf(pharosKey).none { it.target.matches(t) }) {
                    adminsOf(pharosKey) += Party(t, (t as? AdminTarget.User)?.name ?: (t as? AdminTarget.MeshCore)?.name ?: "?")
                }
                ok
            }
            else -> ok
        }
    }

    override fun adminLogin(pharosKey: String, password: String): Boolean {
        if (!_settings.value.encryptAll || password.isBlank()) return false
        adminRoundTrip(pharosKey, AdminCommand.Login) {
            if (password.length < 8) {
                AdminReply.Done(AdminOutcome.WRONG_PASSWORD)
            } else {
                _pharos.update { list -> list.map { if (it.card.key == pharosKey) it.copy(admin = true) else it } }
                AdminReply.Done(AdminOutcome.OK)
            }
        }
        return true
    }

    override suspend fun ping(): Boolean {
        val p = activePharos()?.takeUnless { it.blocked } ?: return false
        delay(1_500)
        _pharos.update { list ->
            list.map { if (it.card.key == p.card.key) it.copy(lastHeardMillis = now(), snrDb = -16) else it }
        }
        return true
    }

    override fun setPingInterval(minutes: Int) = _settings.update { it.copy(pingIntervalMin = minutes) }

    override fun setRadio(model: RadioModel?, custom: CustomRadio) =
        _settings.update { it.copy(radio = model, customRadio = custom.copy(name = custom.name.trim().take(CustomRadio.MAX_NAME))) }

    // Stand-in for keryx::backup (phase 2: sealed with the passphrase, keys
    // included). This one is NOT encrypted: it only checks the passphrase,
    // so restores behave like the real thing for testing. No message history,
    // as the restore warning says.
    override suspend fun exportBackup(passphrase: String): ByteArray {
        delay(300)
        val snap = snapshot().copy(
            messages = emptyMap(),
            chats = _chats.value.map { it.copy(last = null, unread = 0) },
            adminEntries = emptyList(),
        )
        val file = FakeBackup(FAKE_BACKUP_FORMAT, passwordCheck(passphrase), snap)
        return backupJson.encodeToString(FakeBackup.serializer(), file).toByteArray()
    }

    override suspend fun restoreBackup(data: ByteArray, passphrase: String): Boolean {
        delay(300)
        val file = runCatching { backupJson.decodeFromString(FakeBackup.serializer(), data.decodeToString()) }.getOrNull()
        if (file == null || file.format != FAKE_BACKUP_FORMAT || file.check != passwordCheck(passphrase)) return false
        restore(file.snapshot)
        return true
    }

    private fun passwordCheck(passphrase: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(("skypost-fake-backup/" + passphrase).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ADMIN_LOG_MAX = 50
        const val FAKE_BACKUP_FORMAT = "SKYPOSTB1-FAKE"
        val backupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
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
        // The owner's own Pharos: admin from its config.
        _pharos.update { list -> list.map { it.copy(lastHeardMillis = t - 4 * min, admin = true) } }
        val familia = createGroup("Familia")
        val direct = chatWith(bea.key)
        val mama = openBridged(ChatKind.SIGNAL, "34611111111", "Mamá")!!
        val papa = openBridged(ChatKind.WHATSAPP, "34622222222", "Papá")!!
        val refugio = import(Scanned.MeshCore("Refugio Góriz", "a1b2c3d4e5f6".repeat(5) + "a1b2"), inPerson = true)!!
        fun rx(chat: String, text: String, ago: Long, auth: Auth = Auth.SIGNED, sender: String? = null) =
            post(chat, ChatMessage(nextId++, chat, false, text, t - ago * min, auth, sender = sender))
        fun tx(chat: String, text: String, ago: Long, auth: Auth = Auth.SIGNED) =
            post(chat, ChatMessage(nextId++, chat, true, text, t - ago * min, auth, Delivery.ACKED))
        fun txPhrase(chat: String, id: Int, ago: Long) =
            post(chat, ChatMessage(nextId++, chat, true, core.phrases()[id], t - ago * min, Auth.SIGNED, Delivery.ACKED, phrase = id))
        txPhrase(familia, 7, 190)
        rx(familia, "¡Genial! ¿Qué tal el tiempo?", 150, sender = "bea")
        tx(refugio, "¿Queda sitio esta noche para dos?", 140)
        rx(refugio, "Sí, os guardamos dos plazas", 120)
        txPhrase(papa, 5, 95)
        tx(direct, "¿Me oyes bien en 40 m?", 70)
        rx(direct, "Te copio a −18 dB, perfecto", 65, Auth.ENCRYPTED)
        txPhrase(mama, 1, 30)
        rx(mama, "Me alegro. Avisad al llegar", 12)
    }
}

/** The stand-in backup file: a passphrase check and the demo state, in clear. */
@kotlinx.serialization.Serializable
private data class FakeBackup(val format: String, val check: String, val snapshot: DemoSnapshot)
