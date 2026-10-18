//! Golden test vectors: fixed key, fixed messages, expected wire bytes.
//!
//! Any change in these bytes is a wire-format change. If it is intended,
//! regenerate with `KERYX_REGEN=1 cargo test -p keryx --test vectors`,
//! bump the protocol version if old apps can no longer talk to new ones,
//! and update `docs/spec/`.

use data_encoding::HEXUPPER;
use keryx::BROADCAST;
use keryx::ack::Ack;
use keryx::crypto::{GroupKey, Session};
use keryx::message::{self, Message};
use keryx::position::Position;
use serde_json::{Value, json};

const PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testvectors/v0.json");

fn generate() -> Value {
    let gk = GroupKey([0x42; 32]);
    let mut field = Session::provision(&gk, 1, 8).unwrap();
    let base = Session::provision(&gk, 0, 8).unwrap();
    let cases: Vec<(&str, Message, bool)> = vec![
        (
            "text_signed",
            Message::Text("Llegamos al refugio, todo bien.".into()),
            false,
        ),
        (
            "text_encrypted",
            Message::Text("Llegamos al refugio, todo bien.".into()),
            true,
        ),
        ("canned_encrypted", Message::Canned(0), true),
        (
            "ok_with_position_encrypted",
            Message::StatusOk(Some(Position {
                lat: 42.6311,
                lon: 0.6588,
            })),
            true,
        ),
        ("sos_encrypted", Message::Sos(None), true),
        ("poll_signed", Message::Poll, false),
    ];
    let frames: Vec<Value> = cases
        .into_iter()
        .map(|(name, m, enc)| {
            let wire = message::encode(&mut field, &m, BROADCAST, enc).unwrap();
            json!({ "name": name, "sender": 1, "message": m, "encrypted": enc, "wire_hex": HEXUPPER.encode(&wire) })
        })
        .collect();
    let ack = Ack {
        src: 0,
        dst: 1,
        acked_counter_lo: 2,
        snr_db: -17,
        dt_ds: 4,
    };
    json!({
        "protocol": 0,
        "group_key_hex": HEXUPPER.encode(&[0x42; 32]),
        "tag_len": 8,
        "frames": frames,
        "ack": { "fields": ack, "sender": 0, "wire_hex": HEXUPPER.encode(&ack.encode(base.ack_key()).unwrap()) },
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
    let gk = GroupKey([0x42; 32]);
    let mut base = Session::provision(&gk, 0, 8).unwrap();
    let v = generate();
    for f in v["frames"].as_array().unwrap() {
        let wire = HEXUPPER
            .decode(f["wire_hex"].as_str().unwrap().as_bytes())
            .unwrap();
        message::decode(&mut base, &wire).unwrap();
    }
}
