//! Golden test vectors: fixed identities, fixed messages, expected wire bytes.
//!
//! Any change in these bytes is a wire-format change. If it is intended,
//! regenerate with `KERYX_REGEN=1 cargo test -p keryx --test vectors`,
//! bump the protocol version if old apps can no longer talk to new ones,
//! and update `docs/spec/`.

use data_encoding::HEXUPPER;
use keryx::bridge::{Address, notice};
use keryx::crypto::{GroupKey, Identity};
use keryx::message::{self, Message, Received, flags};
use keryx::position::Position;
use keryx::{Keyring, LinkRef, PeerKind};
use serde_json::{Value, json};

const PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testvectors/v1.json");
const FIELD_SECRET: [u8; 32] = [0x11; 32];
const PHAROS_SECRET: [u8; 32] = [0x22; 32];
const GROUP_KEY: [u8; 32] = [0x42; 32];

fn stations() -> (Keyring, Keyring) {
    let mut field = Keyring::new(
        Identity::from_secret(FIELD_SECRET),
        "ana",
        Some("EA5AAA"),
        PeerKind::Person,
    );
    let mut pharos = Keyring::new(
        Identity::from_secret(PHAROS_SECRET),
        "casa",
        Some("EA5YYY"),
        PeerKind::Pharos,
    );
    field.add_peer(pharos.card().clone(), true).unwrap();
    pharos.add_peer(field.card().clone(), true).unwrap();
    let id = field.add_group(GroupKey(GROUP_KEY), "Familia");
    pharos.add_group(GroupKey(GROUP_KEY), "Familia");
    pharos.add_group_member(&id, field.public(), "ana").unwrap();
    (field, pharos)
}

fn generate() -> Value {
    let (mut field, pharos) = stations();
    let to_pharos = LinkRef::Peer(pharos.public());
    let group = LinkRef::Group(GroupKey(GROUP_KEY).id());
    let pos = Position {
        lat: 42.6311,
        lon: 0.6588,
    };
    let cases: Vec<(&str, Message, LinkRef, bool)> = vec![
        (
            "text_signed",
            Message::Text("Llegamos al refugio, todo bien.".into()),
            to_pharos,
            false,
        ),
        (
            "text_encrypted",
            Message::Text("Llegamos al refugio, todo bien.".into()),
            to_pharos,
            true,
        ),
        ("canned_encrypted", Message::Canned(0), to_pharos, true),
        (
            "ok_with_position_encrypted",
            Message::StatusOk(Some(pos)),
            to_pharos,
            true,
        ),
        ("sos_signed", Message::Sos(None), to_pharos, false),
        ("poll", Message::Poll { listen_min: 30 }, to_pharos, false),
        (
            "register",
            Message::Register {
                key: field.public(),
                proof: keryx::access::AccessKey::derive(
                    "caballo pila grapa correcto",
                    &pharos.public(),
                )
                .unwrap()
                .proof(&field.public(), &pharos.public()),
                alias: "ana".into(),
            },
            to_pharos,
            false,
        ),
        (
            "bridge_open_whatsapp",
            Message::BridgeOpen {
                handle: 0,
                address: Address::WhatsappPhone("34600123456".into()),
                name: None,
            },
            to_pharos,
            false,
        ),
        (
            "bridged_text",
            Message::Bridged {
                handle: 0,
                inner: Box::new(Message::Text("Hola mamá".into())),
            },
            to_pharos,
            false,
        ),
        (
            "notice",
            Message::Notice {
                code: notice::UNKNOWN_HANDLE,
                handle: Some(0),
            },
            to_pharos,
            false,
        ),
        (
            "group_join",
            Message::GroupJoin {
                key: field.public(),
                alias: "ana".into(),
            },
            group,
            false,
        ),
        (
            "group_text_signed",
            Message::Text("A cenar".into()),
            group,
            false,
        ),
    ];
    let frames: Vec<Value> = cases
        .into_iter()
        .map(|(name, m, link, enc)| {
            let s = message::encode(&mut field, &m, &link, enc).unwrap();
            json!({ "name": name, "message": m, "link": link, "encrypted": enc, "wire_hex": HEXUPPER.encode(&s.wire) })
        })
        .collect();
    let ack = pharos
        .ack(
            &LinkRef::Peer(field.public()),
            &field.public(),
            2,
            -17,
            4,
            true,
        )
        .unwrap();
    json!({
        "protocol": 1,
        "field_secret_hex": HEXUPPER.encode(&FIELD_SECRET),
        "pharos_secret_hex": HEXUPPER.encode(&PHAROS_SECRET),
        "group_key_hex": HEXUPPER.encode(&GROUP_KEY),
        "field_public": field.public(),
        "pharos_public": pharos.public(),
        "frames": frames,
        "discover_hex": HEXUPPER.encode(&message::encode_discover(&field, 0xBEEF).unwrap()),
        "announce_hex": HEXUPPER.encode(&message::encode_announce(&pharos, flags::PHAROS | flags::SIGNAL, field.short_id(), 0xBEEF).unwrap()),
        "ack": { "from": "pharos", "counter": 2, "snr_db": -17, "dt_ds": 4, "more": true, "wire_hex": HEXUPPER.encode(&ack) },
    })
}

#[test]
fn wire_format_matches_golden_vectors() {
    let got = generate();
    if std::env::var_os("KERYX_REGEN").is_some() {
        std::fs::write(PATH, serde_json::to_string_pretty(&got).unwrap() + "\n").unwrap();
        return;
    }
    let want: Value =
        serde_json::from_str(&std::fs::read_to_string(PATH).expect("run with KERYX_REGEN=1 once"))
            .unwrap();
    assert_eq!(
        got, want,
        "wire format changed; see the header of this file"
    );
}

#[test]
fn golden_frames_decode() {
    let (_, mut pharos) = stations();
    let v = generate();
    for f in v["frames"].as_array().unwrap() {
        let wire = HEXUPPER
            .decode(f["wire_hex"].as_str().unwrap().as_bytes())
            .unwrap();
        let r = message::decode(&mut pharos, &wire).unwrap();
        assert!(matches!(r, Received::Message(_)), "{}", f["name"]);
    }
}
