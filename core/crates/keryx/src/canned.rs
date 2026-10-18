//! Stock phrases sent as a single byte (`MsgType::Canned`).
//!
//! WIRE FORMAT: the index is the id on air. Append only; never reorder,
//! edit or delete an entry, or old and new apps will disagree.

pub const PHRASES: &[&str] = &[
    "Estoy bien",                          // 0
    "Todo bien, sin novedad",              // 1
    "Hemos llegado bien",                  // 2
    "Necesito ayuda",                      // 3
    "Vamos con retraso, no os preocupéis", // 4
    "Mañana bajamos",                      // 5
    "Volvemos hoy",                        // 6
    "Hemos acampado",                      // 7
    "Hace mal tiempo, esperamos",          // 8
    "Estamos a cubierto",                  // 9
    "Cambio de planes",                    // 10
    "Batería baja",                        // 11
    "Mensaje recibido",                    // 12
    "Sí",                                  // 13
    "No",                                  // 14
    "Te quiero",                           // 15
    "Buenas noches",                       // 16
    "Buenos días",                         // 17
    "¿Estáis bien?",                       // 18
    "Llamad a emergencias (112)",          // 19
];

/// Id of a phrase that matches `text` exactly (ignoring case and outer spaces).
pub fn lookup(text: &str) -> Option<u8> {
    let t = text.trim().to_lowercase();
    PHRASES
        .iter()
        .position(|p| p.to_lowercase() == t)
        .map(|i| i as u8)
}

pub fn phrase(id: u8) -> Option<&'static str> {
    PHRASES.get(id as usize).copied()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lookup_is_case_insensitive() {
        assert_eq!(lookup("  estoy BIEN "), Some(0));
        assert_eq!(lookup("estoy bastante bien"), None);
        assert!(PHRASES.len() <= 256);
    }
}
