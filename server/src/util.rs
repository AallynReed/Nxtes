use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use sha2::{Digest, Sha256};
use std::time::{SystemTime, UNIX_EPOCH};

/// Milliseconds since the Unix epoch.
pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

/// A random, URL-safe token of 32 bytes.
pub fn token() -> String {
    let bytes: [u8; 32] = rand::random();
    URL_SAFE_NO_PAD.encode(bytes)
}

/// How tokens are kept at rest, so a leaked database can't be used to sign in.
pub fn hash_token(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// A plausible email address, trimmed and lowercased; None if it isn't one.
pub fn normalize_email(email: &str) -> Option<String> {
    let email = email.trim().to_lowercase();
    let (local, domain) = email.split_once('@')?;
    let ok = !local.is_empty()
        && domain.contains('.')
        && !domain.starts_with('.')
        && !domain.ends_with('.')
        && !email.contains(char::is_whitespace)
        && email.len() <= 254;
    ok.then_some(email)
}

pub fn is_sha256(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emails_are_normalized_or_refused() {
        assert_eq!(
            normalize_email("  Ana@Example.COM "),
            Some("ana@example.com".into())
        );
        assert_eq!(normalize_email("ana@localhost"), None);
        assert_eq!(normalize_email("ana example@x.org"), None);
        assert_eq!(normalize_email("@x.org"), None);
    }

    #[test]
    fn tokens_are_unique_and_hash_stably() {
        let a = token();
        assert_ne!(a, token());
        assert_eq!(hash_token(&a), hash_token(&a));
        assert!(is_sha256(&hash_token(&a)));
    }
}
