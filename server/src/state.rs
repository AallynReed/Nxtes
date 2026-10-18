use crate::config::Config;
use crate::error::{ApiError, ApiResult};
use crate::mail::Mailer;
use crate::util::{hash_token, now};
use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use sqlx::SqlitePool;
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions};
use std::collections::HashMap;
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use tokio::sync::broadcast;

/// What every request handler shares.
#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub db: SqlitePool,
    pub mailer: Mailer,
    pub events: Events,
}

impl AppState {
    pub async fn open(config: Config) -> Result<Self, String> {
        std::fs::create_dir_all(config.blob_dir())
            .map_err(|e| format!("can't create {}: {e}", config.blob_dir().display()))?;
        let options = SqliteConnectOptions::from_str(&format!(
            "sqlite://{}",
            config.data_dir.join("nxtes.db").display()
        ))
        .map_err(|e| e.to_string())?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .foreign_keys(true)
        .busy_timeout(std::time::Duration::from_secs(10));
        let db = SqlitePoolOptions::new()
            .max_connections(8)
            .connect_with(options)
            .await
            .map_err(|e| format!("can't open the database: {e}"))?;
        sqlx::migrate!("./migrations")
            .run(&db)
            .await
            .map_err(|e| format!("migration failed: {e}"))?;
        let mailer = Mailer::new(&config)?;
        Ok(AppState {
            config: Arc::new(config),
            db,
            mailer,
            events: Events::default(),
        })
    }
}

/// Tells a user's other connected devices that something changed, by the change's sequence number.
#[derive(Clone, Default)]
pub struct Events {
    channels: Arc<Mutex<HashMap<String, broadcast::Sender<i64>>>>,
}

impl Events {
    pub fn subscribe(&self, user: &str) -> broadcast::Receiver<i64> {
        self.channels
            .lock()
            .unwrap()
            .entry(user.to_string())
            .or_insert_with(|| broadcast::channel(64).0)
            .subscribe()
    }

    pub fn notify(&self, user: &str, seq: i64) {
        let mut channels = self.channels.lock().unwrap();
        if let Some(sender) = channels.get(user)
            && sender.send(seq).is_err()
        {
            channels.remove(user);
        }
    }
}

/// The signed-in user and device behind a request's bearer token.
#[derive(Clone, Debug)]
pub struct Session {
    pub user_id: String,
    pub device_id: String,
}

impl FromRequestParts<AppState> for Session {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> ApiResult<Self> {
        let header = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default();
        let token = header
            .strip_prefix("Bearer ")
            .ok_or_else(ApiError::unauthorized)?;
        let row: Option<(String, String)> = sqlx::query_as(
            "SELECT d.user_id, d.id FROM sessions s JOIN devices d ON d.id = s.device_id WHERE s.access_hash = ? AND s.access_expires > ?",
        )
        .bind(hash_token(token))
        .bind(now())
        .fetch_optional(&state.db)
        .await?;
        let (user_id, device_id) = row.ok_or_else(ApiError::unauthorized)?;
        sqlx::query("UPDATE devices SET last_seen = ? WHERE id = ? AND last_seen < ?")
            .bind(now())
            .bind(&device_id)
            .bind(now() - 60_000)
            .execute(&state.db)
            .await?;
        Ok(Session { user_id, device_id })
    }
}
