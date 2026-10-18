use crate::error::{ApiError, ApiResult};
use crate::state::{AppState, Session};
use crate::util::{hash_token, new_id, normalize_email, now, token};
use axum::Json;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use serde::Deserialize;
use serde_json::{Value, json};

const ACCESS_MS: i64 = 60 * 60 * 1000;
const REFRESH_MS: i64 = 60 * 24 * 60 * 60 * 1000;
const VERIFY_MS: i64 = 24 * 60 * 60 * 1000;
const RESET_MS: i64 = 60 * 60 * 1000;
const MIN_PASSWORD: usize = 8;
/// One email of a kind per user per minute, and ten a day.
const MAIL_GAP_MS: i64 = 60 * 1000;
const MAIL_DAILY: i64 = 10;

pub async fn hash_password(password: String) -> ApiResult<String> {
    tokio::task::spawn_blocking(move || {
        use argon2::password_hash::PasswordHasher;
        argon2::Argon2::default()
            .hash_password(password.as_bytes())
            .map(|h| h.to_string())
    })
    .await
    .map_err(ApiError::internal)?
    .map_err(ApiError::internal)
}

pub async fn password_matches(password: String, hash: String) -> bool {
    tokio::task::spawn_blocking(move || {
        use argon2::password_hash::PasswordVerifier;
        argon2::password_hash::phc::PasswordHash::new(&hash).is_ok_and(|parsed| {
            argon2::Argon2::default()
                .verify_password(password.as_bytes(), &parsed)
                .is_ok()
        })
    })
    .await
    .unwrap_or(false)
}

fn check_password(password: &str) -> ApiResult<()> {
    if password.chars().count() < MIN_PASSWORD {
        return Err(ApiError::bad_request("weak_password").with("min_length", MIN_PASSWORD));
    }
    Ok(())
}

/// Makes a single-use emailed token of [kind] and returns it, unless too many were sent lately.
async fn new_email_token(
    state: &AppState,
    user_id: &str,
    kind: &str,
    new_email: Option<&str>,
    lifetime: i64,
) -> ApiResult<Option<String>> {
    let (recent, today): (i64, i64) = sqlx::query_as(
        "SELECT COALESCE(SUM(created_at > ?), 0), COUNT(*) FROM email_tokens WHERE user_id = ? AND kind = ? AND created_at > ?",
    )
    .bind(now() - MAIL_GAP_MS)
    .bind(user_id)
    .bind(kind)
    .bind(now() - 24 * 60 * 60 * 1000)
    .fetch_one(&state.db)
    .await?;
    if recent > 0 || today >= MAIL_DAILY {
        return Ok(None);
    }
    let t = token();
    sqlx::query("INSERT INTO email_tokens (token_hash, user_id, kind, new_email, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(hash_token(&t))
        .bind(user_id)
        .bind(kind)
        .bind(new_email)
        .bind(now() + lifetime)
        .bind(now())
        .execute(&state.db)
        .await?;
    Ok(Some(t))
}

/// Uses up an emailed token: its user and, for an email change, the new address.
pub async fn take_email_token(
    state: &AppState,
    t: &str,
    kind: &str,
) -> ApiResult<(String, Option<String>)> {
    let row: Option<(String, Option<String>, i64)> =
        sqlx::query_as("DELETE FROM email_tokens WHERE token_hash = ? AND kind = ? RETURNING user_id, new_email, expires_at")
            .bind(hash_token(t))
            .bind(kind)
            .fetch_optional(&state.db)
            .await?;
    match row {
        Some((user, new_email, expires)) if expires > now() => Ok((user, new_email)),
        _ => Err(ApiError::bad_request("invalid_token")),
    }
}

async fn send_verification(state: &AppState, user_id: &str, email: &str) -> ApiResult<()> {
    if let Some(t) = new_email_token(state, user_id, "verify", None, VERIFY_MS).await? {
        let body = format!(
            "Welcome to Nxtes.\n\nConfirm your email address by opening this link:\n{}/verify/{t}\n\nIt works for 24 hours. If you didn't sign up, you can ignore this message.\n",
            state.config.public_url
        );
        if let Err(e) = state
            .mailer
            .send(email, "Confirm your Nxtes account", body)
            .await
        {
            tracing::warn!("verification mail to {email} failed: {e}");
        }
    }
    Ok(())
}

#[derive(Deserialize)]
pub struct Credentials {
    pub email: String,
    pub password: String,
    #[serde(default)]
    pub device_name: Option<String>,
}

pub async fn register(
    State(state): State<AppState>,
    Json(r): Json<Credentials>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    if !state.config.allow_registration {
        return Err(ApiError::forbidden("registration_closed"));
    }
    let email = normalize_email(&r.email).ok_or_else(|| ApiError::bad_request("bad_email"))?;
    check_password(&r.password)?;
    let taken: Option<String> = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    if taken.is_some() {
        return Err(ApiError::conflict("email_taken"));
    }
    let id = new_id();
    let verified = !state.config.require_email_verification;
    sqlx::query(
        "INSERT INTO users (id, email, password_hash, verified, created_at) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(&id)
    .bind(&email)
    .bind(hash_password(r.password).await?)
    .bind(verified)
    .bind(now())
    .execute(&state.db)
    .await?;
    if !verified {
        send_verification(&state, &id, &email).await?;
    }
    Ok((
        StatusCode::CREATED,
        Json(json!({ "verification_required": !verified })),
    ))
}

#[derive(Deserialize)]
pub struct EmailOnly {
    pub email: String,
}

pub async fn resend_verification(
    State(state): State<AppState>,
    Json(r): Json<EmailOnly>,
) -> ApiResult<Json<Value>> {
    if let Some(email) = normalize_email(&r.email) {
        let user: Option<String> =
            sqlx::query_scalar("SELECT id FROM users WHERE email = ? AND verified = 0")
                .bind(&email)
                .fetch_optional(&state.db)
                .await?;
        if let Some(id) = user {
            send_verification(&state, &id, &email).await?;
        }
    }
    Ok(Json(json!({ "ok": true })))
}

#[derive(Deserialize)]
pub struct TokenOnly {
    pub token: String,
}

pub async fn verify(
    State(state): State<AppState>,
    Json(r): Json<TokenOnly>,
) -> ApiResult<Json<Value>> {
    verify_token(&state, &r.token).await?;
    Ok(Json(json!({ "ok": true })))
}

pub async fn verify_token(state: &AppState, t: &str) -> ApiResult<()> {
    let (user, _) = take_email_token(state, t, "verify").await?;
    sqlx::query("UPDATE users SET verified = 1 WHERE id = ?")
        .bind(user)
        .execute(&state.db)
        .await?;
    Ok(())
}

/// A new pair of tokens for a device, replacing any it had.
async fn issue_session(state: &AppState, device_id: &str) -> ApiResult<Value> {
    let access = token();
    let refresh = token();
    sqlx::query(
        "INSERT INTO sessions (device_id, access_hash, access_expires, refresh_hash, refresh_expires) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (device_id) DO UPDATE SET access_hash = excluded.access_hash, access_expires = excluded.access_expires,
         refresh_hash = excluded.refresh_hash, refresh_expires = excluded.refresh_expires",
    )
    .bind(device_id)
    .bind(hash_token(&access))
    .bind(now() + ACCESS_MS)
    .bind(hash_token(&refresh))
    .bind(now() + REFRESH_MS)
    .execute(&state.db)
    .await?;
    Ok(
        json!({ "access_token": access, "refresh_token": refresh, "expires_in": ACCESS_MS / 1000, "device_id": device_id }),
    )
}

pub async fn login(
    State(state): State<AppState>,
    Json(r): Json<Credentials>,
) -> ApiResult<Json<Value>> {
    let email = normalize_email(&r.email).unwrap_or_default();
    let user: Option<(String, String, bool)> =
        sqlx::query_as("SELECT id, password_hash, verified FROM users WHERE email = ?")
            .bind(&email)
            .fetch_optional(&state.db)
            .await?;
    let Some((id, hash, verified)) = user else {
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "bad_credentials"));
    };
    if !password_matches(r.password, hash).await {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "bad_credentials"));
    }
    if state.config.require_email_verification && !verified {
        return Err(ApiError::forbidden("email_not_verified"));
    }
    if state.config.max_devices > 0 {
        let devices: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM devices WHERE user_id = ?")
            .bind(&id)
            .fetch_one(&state.db)
            .await?;
        if devices >= state.config.max_devices {
            return Err(
                ApiError::forbidden("device_limit").with("max_devices", state.config.max_devices)
            );
        }
    }
    let device = new_id();
    let name = r
        .device_name
        .unwrap_or_default()
        .trim()
        .chars()
        .take(80)
        .collect::<String>();
    sqlx::query(
        "INSERT INTO devices (id, user_id, name, created_at, last_seen) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(&device)
    .bind(&id)
    .bind(if name.is_empty() {
        "Device".to_string()
    } else {
        name
    })
    .bind(now())
    .bind(now())
    .execute(&state.db)
    .await?;
    let mut session = issue_session(&state, &device).await?;
    session["user"] = json!({ "id": id, "email": email });
    Ok(Json(session))
}

#[derive(Deserialize)]
pub struct RefreshRequest {
    pub refresh_token: String,
}

pub async fn refresh(
    State(state): State<AppState>,
    Json(r): Json<RefreshRequest>,
) -> ApiResult<Json<Value>> {
    let device: Option<String> = sqlx::query_scalar(
        "SELECT device_id FROM sessions WHERE refresh_hash = ? AND refresh_expires > ?",
    )
    .bind(hash_token(&r.refresh_token))
    .bind(now())
    .fetch_optional(&state.db)
    .await?;
    let device = device.ok_or_else(ApiError::unauthorized)?;
    Ok(Json(issue_session(&state, &device).await?))
}

/// Signs this device out; it leaves the device list too.
pub async fn logout(State(state): State<AppState>, session: Session) -> ApiResult<StatusCode> {
    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(&session.device_id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn request_password_reset(
    State(state): State<AppState>,
    Json(r): Json<EmailOnly>,
) -> ApiResult<Json<Value>> {
    if let Some(email) = normalize_email(&r.email) {
        let user: Option<String> = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
            .bind(&email)
            .fetch_optional(&state.db)
            .await?;
        if let Some(id) = user
            && let Some(t) = new_email_token(&state, &id, "reset", None, RESET_MS).await?
        {
            let body = format!(
                "Someone asked to reset the password of your Nxtes account.\n\nChoose a new password here:\n{}/reset/{t}\n\nThe link works for an hour. If it wasn't you, ignore this message; your password stays as it is.\n",
                state.config.public_url
            );
            if let Err(e) = state
                .mailer
                .send(&email, "Reset your Nxtes password", body)
                .await
            {
                tracing::warn!("reset mail to {email} failed: {e}");
            }
        }
    }
    Ok(Json(json!({ "ok": true })))
}

#[derive(Deserialize)]
pub struct ResetConfirm {
    pub token: String,
    pub password: String,
}

pub async fn confirm_password_reset(
    State(state): State<AppState>,
    Json(r): Json<ResetConfirm>,
) -> ApiResult<Json<Value>> {
    reset_password(&state, &r.token, r.password).await?;
    Ok(Json(json!({ "ok": true })))
}

/// Sets a new password from a reset token and signs every device out. Reaching the link proves the address too.
pub async fn reset_password(state: &AppState, t: &str, password: String) -> ApiResult<()> {
    check_password(&password)?;
    let (user, _) = take_email_token(state, t, "reset").await?;
    sqlx::query("UPDATE users SET password_hash = ?, verified = 1 WHERE id = ?")
        .bind(hash_password(password).await?)
        .bind(&user)
        .execute(&state.db)
        .await?;
    sqlx::query("DELETE FROM devices WHERE user_id = ?")
        .bind(&user)
        .execute(&state.db)
        .await?;
    Ok(())
}

#[derive(Deserialize)]
pub struct EmailChange {
    pub email: String,
    pub password: String,
}

/// Emails a confirmation link to the new address; the change happens when it's opened.
pub async fn change_email(
    State(state): State<AppState>,
    session: Session,
    Json(r): Json<EmailChange>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let email = normalize_email(&r.email).ok_or_else(|| ApiError::bad_request("bad_email"))?;
    let hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
        .bind(&session.user_id)
        .fetch_one(&state.db)
        .await?;
    if !password_matches(r.password, hash).await {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "bad_credentials"));
    }
    let taken: Option<String> = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?;
    if taken.is_some() {
        return Err(ApiError::conflict("email_taken"));
    }
    let t = new_email_token(&state, &session.user_id, "email", Some(&email), VERIFY_MS)
        .await?
        .ok_or_else(ApiError::too_many)?;
    let body = format!(
        "Confirm {email} as the new address of your Nxtes account:\n{}/confirm-email/{t}\n\nIt works for 24 hours. If you didn't ask for this, ignore this message.\n",
        state.config.public_url
    );
    state
        .mailer
        .send(&email, "Confirm your new Nxtes address", body)
        .await
        .map_err(ApiError::internal)?;
    Ok((StatusCode::ACCEPTED, Json(json!({ "ok": true }))))
}

pub async fn confirm_email(
    State(state): State<AppState>,
    Json(r): Json<TokenOnly>,
) -> ApiResult<Json<Value>> {
    apply_email_change(&state, &r.token).await?;
    Ok(Json(json!({ "ok": true })))
}

pub async fn apply_email_change(state: &AppState, t: &str) -> ApiResult<()> {
    let (user, email) = take_email_token(state, t, "email").await?;
    let email = email.ok_or_else(|| ApiError::bad_request("invalid_token"))?;
    sqlx::query("UPDATE users SET email = ?, verified = 1 WHERE id = ?")
        .bind(&email)
        .bind(&user)
        .execute(&state.db)
        .await
        .map_err(|_| ApiError::conflict("email_taken"))?;
    Ok(())
}

pub async fn account(State(state): State<AppState>, session: Session) -> ApiResult<Json<Value>> {
    let (email, quota): (String, Option<i64>) =
        sqlx::query_as("SELECT email, quota_bytes FROM users WHERE id = ?")
            .bind(&session.user_id)
            .fetch_one(&state.db)
            .await?;
    Ok(Json(
        json!({ "id": session.user_id, "email": email, "device_id": session.device_id, "quota": quota.unwrap_or(state.config.default_quota_bytes) }),
    ))
}

#[derive(Deserialize)]
pub struct PasswordOnly {
    pub password: String,
}

/// Deletes the account with all its devices, documents and blobs.
pub async fn delete_account(
    State(state): State<AppState>,
    session: Session,
    Json(r): Json<PasswordOnly>,
) -> ApiResult<StatusCode> {
    let hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
        .bind(&session.user_id)
        .fetch_one(&state.db)
        .await?;
    if !password_matches(r.password, hash).await {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "bad_credentials"));
    }
    remove_user(&state, &session.user_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Deletes the account behind [email] if [password] is right, for the web deletion page.
pub async fn delete_with_password(
    state: &AppState,
    email: &str,
    password: String,
) -> ApiResult<bool> {
    let email = normalize_email(email).unwrap_or_default();
    let user: Option<(String, String)> =
        sqlx::query_as("SELECT id, password_hash FROM users WHERE email = ?")
            .bind(&email)
            .fetch_optional(&state.db)
            .await?;
    let Some((id, hash)) = user else {
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
        return Ok(false);
    };
    if !password_matches(password, hash).await {
        return Ok(false);
    }
    remove_user(state, &id).await?;
    Ok(true)
}

pub async fn remove_user(state: &AppState, user_id: &str) -> ApiResult<()> {
    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(user_id)
        .execute(&state.db)
        .await?;
    for dir in [state.config.blob_dir(), state.config.share_dir()] {
        let dir = dir.join(user_id);
        if dir.exists() {
            tokio::fs::remove_dir_all(dir).await?;
        }
    }
    Ok(())
}

pub async fn devices(State(state): State<AppState>, session: Session) -> ApiResult<Json<Value>> {
    let rows: Vec<(String, String, i64, i64)> =
        sqlx::query_as("SELECT id, name, created_at, last_seen FROM devices WHERE user_id = ? ORDER BY last_seen DESC").bind(&session.user_id).fetch_all(&state.db).await?;
    let list: Vec<Value> = rows
        .into_iter()
        .map(|(id, name, created, seen)| json!({ "id": id, "name": name, "created_at": created, "last_seen": seen, "current": id == session.device_id }))
        .collect();
    Ok(Json(json!({ "devices": list })))
}

pub async fn remove_device(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
) -> ApiResult<StatusCode> {
    let done = sqlx::query("DELETE FROM devices WHERE id = ? AND user_id = ?")
        .bind(&id)
        .bind(&session.user_id)
        .execute(&state.db)
        .await?;
    if done.rows_affected() == 0 {
        return Err(ApiError::not_found());
    }
    Ok(StatusCode::NO_CONTENT)
}
