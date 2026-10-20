//! Wires the station (air), the bridge bot and the admin executor together,
//! the way the daemon runs them: frames, bridge messages and ticks in; what to
//! transmit and what to send on the bridges out. Pure logic, so whole flows
//! (an access request from WhatsApp accepted over the radio, say) are tested
//! here. The caller persists [`Station::state`] after every call, before
//! carrying out the outputs.

use crate::access::Who;
use crate::admin::{self, Admin, Caller, Text};
use crate::router::{Action, Directory, Incoming, Router, Undelivered};
use crate::station::{Effect, RxFrame, Station};
use keryx::admin::{Command, Switch, Switches};
use keryx::bridge::Address;
use keryx::{Message, PublicKey};

/// Something for the shell to carry out.
#[derive(Clone, Debug, PartialEq)]
pub enum Out {
    /// On air (`Transmit`), or for the log and bridges (`Sos`, `Inbox`, `Log`).
    Air(Effect),
    /// A bridge message or reaction.
    Bridge(Action),
}

pub struct Hub {
    pub station: Station,
    pub router: Router,
    pub admin: Admin,
}

/// The station as the bot sees it.
struct View<'a> {
    station: &'a Station,
    login: bool,
}

impl Directory for View<'_> {
    fn by_alias(&self, alias: &str) -> Option<PublicKey> {
        self.station.registry().by_alias(alias)
    }

    fn alias_of(&self, key: &PublicKey) -> Option<String> {
        self.station.registry().get(key).map(|c| c.alias.clone())
    }

    fn is_listening(&self, key: &PublicKey, now: u64) -> bool {
        self.station
            .registry()
            .get(key)
            .is_some_and(|c| c.is_listening(now))
    }

    fn switches(&self) -> Switches {
        self.station.access().switches
    }

    fn is_admin(&self, a: &Address) -> bool {
        self.station.access().is_admin(&Who::Contact(a.clone()))
    }

    fn requested(&self, a: &Address) -> bool {
        self.station
            .access()
            .request_of(&Who::Contact(a.clone()))
            .is_some()
    }

    fn login_available(&self) -> bool {
        self.login
    }
}

impl Hub {
    /// The bot's persisted part comes from the station state.
    pub fn new(station: Station, router_cfg: crate::router::RouterCfg, admin: Admin) -> Self {
        let bot = station.state().bot.clone();
        Self {
            station,
            router: Router::new(router_cfg, bot),
            admin,
        }
    }

    /// Copy the bot's persisted part into the station state, to be saved.
    fn sync(&mut self) {
        self.station.state_mut().bot = self.router.state().clone();
    }

    pub fn on_rx(&mut self, f: &RxFrame) -> Vec<Out> {
        let fx = self.station.on_rx(f);
        let out = self.effects(fx, f.now);
        self.sync();
        out
    }

    pub fn on_bridge(&mut self, m: Incoming) -> Vec<Out> {
        let now = m.now;
        let view = View {
            station: &self.station,
            login: self.admin.login_available(&self.station),
        };
        let acts = self.router.on_incoming(&view, m);
        let out = self.actions(acts, now);
        self.sync();
        out
    }

    pub fn on_tick(&mut self, now: u64) -> Vec<Out> {
        let fx = self.station.on_tick(now);
        let mut out = self.effects(fx, now);
        let acts = self.router.on_tick(now);
        out.extend(self.actions(acts, now));
        self.sync();
        out
    }

    fn effects(&mut self, fx: Vec<Effect>, now: u64) -> Vec<Out> {
        let mut out = Vec::new();
        for e in fx {
            match e {
                Effect::Admin {
                    from,
                    encrypted,
                    command,
                } => {
                    let caller = Caller::Radio {
                        key: from,
                        encrypted,
                    };
                    let ran =
                        self.admin
                            .run(&mut self.station, &mut self.router, &caller, command, now);
                    out.extend(self.effects(ran.effects, now));
                    out.extend(self.actions(ran.actions, now));
                }
                Effect::BridgeOut {
                    from,
                    alias,
                    to,
                    message,
                } => {
                    let acts = self.router.on_outgoing(from, &alias, &to, &message, now);
                    out.extend(acts.into_iter().map(Out::Bridge));
                }
                Effect::BridgeText { to, text } => {
                    out.push(Out::Bridge(Action::Reply { to, text }));
                }
                Effect::Delivered { token: Some(t), .. } => {
                    out.extend(self.router.on_delivered(t).into_iter().map(Out::Bridge));
                }
                Effect::Expired { token: Some(t), .. } => {
                    let view = View {
                        station: &self.station,
                        login: self.admin.login_available(&self.station),
                    };
                    let acts = self.router.on_undelivered(&view, t, Undelivered::Expired);
                    out.extend(acts.into_iter().map(Out::Bridge));
                }
                e => out.push(Out::Air(e)),
            }
        }
        out
    }

    fn actions(&mut self, acts: Vec<Action>, now: u64) -> Vec<Out> {
        let mut out = Vec::new();
        for a in acts {
            match a {
                Action::Admin { from, text } => out.extend(self.admin_text(from, text, now)),
                Action::AdminLogin {
                    from,
                    password,
                    then,
                } => {
                    let caller = Caller::Bridge(from.clone());
                    let ran = self.admin.run(
                        &mut self.station,
                        &mut self.router,
                        &caller,
                        Command::Login(password),
                        now,
                    );
                    out.extend(self.effects(ran.effects, now));
                    out.extend(self.actions(ran.actions, now));
                    let admin = self.station.access().is_admin(&Who::Contact(from.clone()));
                    if let Some(then) = then.filter(|_| admin) {
                        out.extend(self.admin_text(from, then, now));
                    }
                }
                Action::RequestAccess { from, name } => {
                    let shown = admin::contact_name(&from, name.as_deref());
                    let fx = self.station.request_contact(&from, &shown, now);
                    out.extend(self.effects(fx, now));
                }
                Action::Withdraw { from } => {
                    self.station.access_mut().drop_request(&Who::Contact(from));
                }
                Action::Queue {
                    client,
                    from,
                    name,
                    text,
                    token,
                } => {
                    let r = self.station.queue_bridged(
                        &client,
                        &from,
                        name,
                        Message::from_text(&text),
                        Some(token),
                        now,
                    );
                    match r {
                        Ok(fx) => out.extend(self.effects(fx, now)),
                        Err(_) => {
                            let view = View {
                                station: &self.station,
                                login: self.admin.login_available(&self.station),
                            };
                            let acts =
                                self.router
                                    .on_undelivered(&view, token, Undelivered::QueueFull);
                            out.extend(acts.into_iter().map(Out::Bridge));
                        }
                    }
                }
                a @ (Action::Reply { .. } | Action::React { .. }) => out.push(Out::Bridge(a)),
            }
        }
        out
    }

    /// A bridge admin's text: the help or a command.
    fn admin_text(&mut self, from: Address, text: Text, now: u64) -> Vec<Out> {
        let cmd = match text {
            Text::Help => {
                let help = if Router::terse(&from) {
                    admin::HELP_SHORT
                } else {
                    admin::HELP
                };
                return vec![Out::Bridge(Action::Reply {
                    to: from,
                    text: help.into(),
                })];
            }
            Text::Command(c) => c,
        };
        let caller = Caller::Bridge(from);
        let ran = self
            .admin
            .run(&mut self.station, &mut self.router, &caller, cmd, now);
        let mut out = self.effects(ran.effects, now);
        out.extend(self.actions(ran.actions, now));
        out
    }

    pub fn switch(&self, s: Switch) -> bool {
        self.station.access().on(s)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::admin::hex;
    use crate::router::RouterCfg;
    use crate::station::{State, StationCfg};
    use keryx::JS8_FRAME_PAYLOAD_BYTES as MTU;
    use keryx::admin::{Outcome, Reply, RequestEntry, how, user_id, who};
    use keryx::lane::Lane;
    use keryx::segment::{reassemble, segment};
    use keryx::{Identity, Keyring, LinkRef, PeerKind, Received, message};

    const T: u64 = 1_800_000_000;
    const PASSWORD: &str = "faro viejo de la costa";

    fn papa() -> Address {
        Address::WhatsappPhone("34622222222".into())
    }

    fn op() -> Address {
        Address::SignalPhone("34600000000".into())
    }

    /// Closed with requests (defaults), a field admin "ana" and a Signal admin.
    fn hub() -> (Hub, Keyring) {
        let k = Keyring::new(
            Identity::from_secret([9; 32]),
            "casa",
            Some("EA5YYY"),
            PeerKind::Pharos,
        );
        let mut station = Station::new(State::new(k), StationCfg::default());
        let mut ana = Keyring::new(
            Identity::from_secret([1; 32]),
            "ana",
            None,
            PeerKind::Person,
        );
        ana.add_peer(station.keyring().card().clone(), true)
            .unwrap();
        station.allow(ana.card().clone()).unwrap();
        let a = station.access_mut();
        a.add_admin(Who::Radio(ana.public()), how::SSH);
        a.add_admin(Who::Contact(op()), how::SSH);
        let mut h = Hub::new(
            station,
            RouterCfg::default(),
            Admin::new(Some(PASSWORD.into())),
        );
        // Ana listens for half an hour.
        let poll = send(&mut ana, &h, Message::Poll { listen_min: 30 }, false);
        h.on_rx(&rx(&poll, T));
        (h, ana)
    }

    fn rx(wire: &[u8], now: u64) -> RxFrame {
        RxFrame {
            data: reassemble(&segment(wire, MTU)),
            snr_db: -14,
            dt_ds: 2,
            lane: Lane::Js8Normal,
            now,
        }
    }

    fn send(k: &mut Keyring, h: &Hub, m: Message, enc: bool) -> Vec<u8> {
        message::encode(k, &m, &LinkRef::Peer(h.station.keyring().public()), enc)
            .unwrap()
            .wire
    }

    /// What `k` reads in the transmissions (ACKs skipped), ACKing each one
    /// back so the next queued message goes out.
    fn heard(h: &mut Hub, k: &mut Keyring, out: &[Out], now: u64) -> Vec<Message> {
        let mut got = Vec::new();
        let mut wires: Vec<Vec<u8>> = out
            .iter()
            .filter_map(|o| match o {
                Out::Air(Effect::Transmit { wire, .. }) => Some(wire.clone()),
                _ => None,
            })
            .collect();
        let mut t = now;
        while let Some(w) = wires.pop() {
            if let Ok(Received::Message(d)) = message::decode(k, &w) {
                got.push(d.message);
                let ack = k
                    .ack(&d.link, &d.sender, d.header.counter, -10, 0, false)
                    .unwrap();
                t += 1;
                for o in h.on_rx(&rx(&ack, t)) {
                    if let Out::Air(Effect::Transmit { wire, .. }) = o {
                        wires.push(wire);
                    }
                }
            }
        }
        got
    }

    fn bridge(from: Address, text: &str, now: u64) -> Incoming {
        Incoming {
            from,
            text: Some(text.into()),
            in_group: false,
            msg_ref: format!("r{now}"),
            name: Some("Papá".into()),
            now,
        }
    }

    fn texts_to(out: &[Out], to: &Address) -> String {
        out.iter()
            .filter_map(|o| match o {
                Out::Bridge(Action::Reply { to: t, text }) if t == to => Some(text.as_str()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join(" | ")
    }

    #[test]
    fn whatsapp_request_accepted_over_the_radio() {
        let (mut h, mut ana) = hub();
        let out = h.on_bridge(bridge(papa(), "¿Habéis llegado?", T + 10));
        assert!(texts_to(&out, &papa()).contains("He enviado tu solicitud"));
        assert!(texts_to(&out, &op()).contains("New access request (1 pending)"));
        assert_eq!(
            heard(&mut h, &mut ana, &out, T + 10),
            vec![Message::AdminReply(Reply::NewRequests {
                networks: keryx::admin::network_bit(who::WHATSAPP),
                pending: 1
            })]
        );
        let list = send(&mut ana, &h, Message::Admin(Command::Requests), false);
        let out = h.on_rx(&rx(&list, T + 60));
        assert_eq!(
            heard(&mut h, &mut ana, &out, T + 60),
            vec![Message::AdminReply(Reply::Requests(vec![RequestEntry {
                num: 0,
                who: who::WHATSAPP,
                id: None,
                name: "Papá +34622222222".into()
            }]))]
        );
        let accept = send(&mut ana, &h, Message::Admin(Command::Accept(0)), false);
        let out = h.on_rx(&rx(&accept, T + 120));
        assert!(texts_to(&out, &papa()).contains("aceptada"));
        assert!(texts_to(&out, &papa()).contains("¿Para quién es"));
        assert_eq!(
            heard(&mut h, &mut ana, &out, T + 120),
            vec![Message::AdminReply(Reply::Done {
                opcode: Command::Accept(0).opcode(),
                outcome: Outcome::Ok,
                subject: Some("Papá +34622222222".into())
            })]
        );
        // The held message goes to ana once papá names her.
        let out = h.on_bridge(bridge(papa(), "ana", T + 180));
        assert!(
            heard(&mut h, &mut ana, &out, T + 180)
                .iter()
                .any(|m| m.render().contains("¿Habéis llegado?"))
        );
        // A late "accept 0" no longer points anywhere.
        let accept = send(&mut ana, &h, Message::Admin(Command::Accept(0)), false);
        let out = h.on_rx(&rx(&accept, T + 240));
        assert!(matches!(
            heard(&mut h, &mut ana, &out, T + 240).as_slice(),
            [Message::AdminReply(Reply::Done {
                outcome: Outcome::Unknown,
                ..
            })]
        ));
    }

    #[test]
    fn bridge_password_login_then_the_command() {
        let (mut h, mut ana) = hub();
        let out = h.on_bridge(bridge(papa(), "/admin status", T + 10));
        assert!(texts_to(&out, &papa()).contains("send me its admin password"));
        let out = h.on_bridge(bridge(papa(), PASSWORD, T + 20));
        let t = texts_to(&out, &papa());
        assert!(
            t.contains("DELETE THE MESSAGE WITH THE PASSWORD NOW"),
            "{t}"
        );
        assert!(t.contains("Registered:"), "then the status: {t}");
        assert!(texts_to(&out, &op()).contains("is now an admin"));
        assert!(
            heard(&mut h, &mut ana, &out, T + 20)
                .iter()
                .any(|m| matches!(
                    m,
                    Message::AdminReply(Reply::AdminChanged { added: true, .. })
                ))
        );
        assert!(h.station.access().is_admin(&Who::Contact(papa())));
    }

    #[test]
    fn wrong_passwords_lock_out_for_an_hour() {
        let (mut h, _) = hub();
        for i in 0..5 {
            let out = h.on_bridge(bridge(papa(), "/admin login nope", T + i));
            assert!(texts_to(&out, &papa()).contains("password"), "{out:?}");
        }
        let out = h.on_bridge(bridge(papa(), &format!("/admin login {PASSWORD}"), T + 10));
        assert!(texts_to(&out, &papa()).is_empty(), "locked: silence");
        let out = h.on_bridge(bridge(
            papa(),
            &format!("/admin login {PASSWORD}"),
            T + 3700,
        ));
        assert!(texts_to(&out, &papa()).contains("you are an admin now"));
    }

    #[test]
    fn radio_login_only_counts_encrypted() {
        let (mut h, _) = hub();
        let mut bea = Keyring::new(
            Identity::from_secret([3; 32]),
            "bea",
            None,
            PeerKind::Person,
        );
        bea.add_peer(h.station.keyring().card().clone(), true)
            .unwrap();
        h.station.allow(bea.card().clone()).unwrap();
        let login = |k: &mut Keyring, h: &Hub, enc| {
            send(k, h, Message::Admin(Command::Login(PASSWORD.into())), enc)
        };
        let w = login(&mut bea, &h, false);
        let out = h.on_rx(&rx(&w, T + 10));
        assert!(texts_to(&out, &op()).contains("UNENCRYPTED"));
        assert!(!h.station.access().is_admin(&Who::Radio(bea.public())));
        let w = login(&mut bea, &h, true);
        h.on_rx(&rx(&w, T + 20));
        assert!(h.station.access().is_admin(&Who::Radio(bea.public())));
    }

    #[test]
    fn switches_by_text_are_announced_and_the_last_admin_stays() {
        let (mut h, mut ana) = hub();
        let out = h.on_bridge(bridge(op(), "/admin set open on", T + 10));
        assert_eq!(texts_to(&out, &op()), "Done.");
        assert!(h.switch(Switch::Open));
        assert!(
            heard(&mut h, &mut ana, &out, T + 10)
                .iter()
                .any(|m| matches!(m, Message::AdminReply(Reply::SwitchesChanged(_))))
        );
        // Password login off: the last admin cannot go.
        h.on_bridge(bridge(op(), "/admin set login off", T + 20));
        h.on_bridge(bridge(
            op(),
            &format!("/admin revoke {}", hex(&user_id(&ana.public()))),
            T + 30,
        ));
        let out = h.on_bridge(bridge(op(), "/admin revoke +34600000000", T + 40));
        assert!(texts_to(&out, &op()).contains("last admin"), "{out:?}");
        // Stealth: no announcements.
        h.on_bridge(bridge(
            op(),
            &format!("/admin grant {}", hex(&user_id(&ana.public()))),
            T + 50,
        ));
        h.on_bridge(bridge(op(), "/admin set stealth on", T + 60));
        let out = h.on_bridge(bridge(op(), "/admin set requests off", T + 70));
        assert!(heard(&mut h, &mut ana, &out, T + 70).is_empty());
    }

    #[test]
    fn emergency_switch_works_in_plain_and_meshcore_by_prefix() {
        let (mut h, mut ana) = hub();
        let on = send(
            &mut ana,
            &h,
            Message::Admin(Command::Set(Switch::Emergency, true)),
            false,
        );
        h.on_rx(&rx(&on, T + 10));
        assert!(h.switch(Switch::Emergency));
        let st = send(&mut ana, &h, Message::Admin(Command::Status), false);
        let out = h.on_rx(&rx(&st, T + 20));
        assert!(
            !out.iter()
                .any(|o| matches!(o, Out::Air(Effect::Log(l)) if l.contains("status"))),
            "plain status dropped in an emergency"
        );
        let off = send(
            &mut ana,
            &h,
            Message::Admin(Command::Set(Switch::Emergency, false)),
            false,
        );
        h.on_rx(&rx(&off, T + 30));
        assert!(!h.switch(Switch::Emergency));

        let node = Address::MeshcoreContact([0xA1; 32]);
        let mut m = bridge(node.clone(), "hola", T + 40);
        m.name = Some("Refugio".into());
        h.on_bridge(m);
        assert_eq!(h.station.access().requests[0].name, "Refugio");
        let out = h.on_bridge(bridge(
            op(),
            "/admin block meshcore a1a1a1a1a1a1a1a1",
            T + 50,
        ));
        assert!(
            texts_to(&out, &op()).contains("Refugio · A1A1 A1A1"),
            "{out:?}"
        );
        assert!(h.router.state().blocked.contains(&node));
        assert!(
            h.station.access().requests.is_empty(),
            "blocking ends the request"
        );
    }

    #[test]
    fn radio_users_are_named_by_id_not_alias() {
        let (mut h, ana) = hub();
        let out = h.on_bridge(bridge(op(), "/admin clients", T + 10));
        let id = hex(&user_id(&ana.public()));
        assert!(
            texts_to(&out, &op()).contains(&format!("@ana {id}")),
            "{out:?}"
        );
        let out = h.on_bridge(bridge(op(), "/admin block @ana", T + 20));
        assert!(
            texts_to(&out, &op()).starts_with("Admin commands"),
            "aliases give the help"
        );
        let out = h.on_bridge(bridge(op(), &format!("/admin block {id}"), T + 30));
        assert!(texts_to(&out, &op()).contains("@ana · "), "{out:?}");
        assert_eq!(
            h.station.registry().get(&ana.public()).unwrap().status,
            crate::registry::Status::Blocked
        );
    }
}
