use crate::error::{ApiError, ApiResult};
use crate::state::{AppState, Session};
use crate::util::{is_sha256, now};
use axum::Json;
use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{StatusCode, header};
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::{IntoResponse, Response};
use futures_util::{Stream, StreamExt};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::convert::Infallible;
use std::path::PathBuf;
use tokio::io::AsyncWriteExt;
use tokio_stream::wrappers::BroadcastStream;

/// Unreferenced blobs younger than this are kept, so an upload can land before its manifest.
const BLOB_GRACE_MS: i64 = 60 * 60 * 1000;

#[derive(Clone, Copy, PartialEq)]
pub enum Kind {
    Document,
    Folder,
}

impl Kind {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Kind::Document => "document",
            Kind::Folder => "folder",
        }
    }
}

fn blob_path(state: &AppState, user: &str, sha: &str) -> PathBuf {
    state.config.blob_dir().join(user).join(&sha[..2]).join(sha)
}

/// Bytes the user's blobs and shared PDFs take up.
pub(crate) async fn used_bytes(state: &AppState, user: &str) -> ApiResult<i64> {
    Ok(sqlx::query_scalar(
        "SELECT (SELECT COALESCE(SUM(size), 0) FROM blobs WHERE user_id = ?1) + (SELECT COALESCE(SUM(size), 0) FROM shares WHERE user_id = ?1)",
    )
    .bind(user)
    .fetch_one(&state.db)
    .await?)
}

pub(crate) async fn quota(state: &AppState, user: &str) -> ApiResult<i64> {
    let q: Option<i64> = sqlx::query_scalar("SELECT quota_bytes FROM users WHERE id = ?")
        .bind(user)
        .fetch_one(&state.db)
        .await?;
    Ok(q.unwrap_or(state.config.default_quota_bytes))
}

pub async fn usage(State(state): State<AppState>, session: Session) -> ApiResult<Json<Value>> {
    Ok(Json(
        json!({ "used": used_bytes(&state, &session.user_id).await?, "quota": quota(&state, &session.user_id).await? }),
    ))
}

#[derive(Deserialize)]
pub struct Hashes {
    pub hashes: Vec<String>,
}

pub async fn missing_blobs(
    State(state): State<AppState>,
    session: Session,
    Json(r): Json<Hashes>,
) -> ApiResult<Json<Value>> {
    let mut missing = Vec::new();
    for sha in r.hashes.iter().filter(|h| is_sha256(h)) {
        let have: Option<i64> =
            sqlx::query_scalar("SELECT 1 FROM blobs WHERE user_id = ? AND sha256 = ?")
                .bind(&session.user_id)
                .bind(sha)
                .fetch_optional(&state.db)
                .await?;
        if have.is_none() && !missing.contains(sha) {
            missing.push(sha.clone());
        }
    }
    Ok(Json(json!({ "missing": missing })))
}

/// Stores a blob, checking that its content hashes to the name it's uploaded under and fits the quota.
pub async fn put_blob(
    State(state): State<AppState>,
    session: Session,
    Path(sha): Path<String>,
    body: Body,
) -> ApiResult<StatusCode> {
    if !is_sha256(&sha) {
        return Err(ApiError::bad_request("bad_hash"));
    }
    let path = blob_path(&state, &session.user_id, &sha);
    let have: Option<i64> =
        sqlx::query_scalar("SELECT 1 FROM blobs WHERE user_id = ? AND sha256 = ?")
            .bind(&session.user_id)
            .bind(&sha)
            .fetch_optional(&state.db)
            .await?;
    if have.is_some() && path.exists() {
        return Ok(StatusCode::OK);
    }
    let room =
        quota(&state, &session.user_id).await? - used_bytes(&state, &session.user_id).await?;
    tokio::fs::create_dir_all(path.parent().unwrap()).await?;
    let tmp = path.with_extension(format!("{}.part", crate::util::new_id()));
    let result = async {
        let mut file = tokio::fs::File::create(&tmp).await?;
        let mut hasher = Sha256::new();
        let mut size: i64 = 0;
        let mut stream = body.into_data_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|_| ApiError::bad_request("upload_failed"))?;
            size += chunk.len() as i64;
            if size as usize > state.config.max_blob_bytes {
                return Err(ApiError::new(StatusCode::PAYLOAD_TOO_LARGE, "too_large"));
            }
            if size > room {
                return Err(ApiError::new(
                    StatusCode::INSUFFICIENT_STORAGE,
                    "quota_exceeded",
                ));
            }
            hasher.update(&chunk);
            file.write_all(&chunk).await?;
        }
        file.flush().await?;
        file.sync_all().await?;
        if hex::encode(hasher.finalize()) != sha {
            return Err(ApiError::bad_request("hash_mismatch"));
        }
        Ok(size)
    }
    .await;
    let size = match result {
        Ok(size) => size,
        Err(e) => {
            let _ = tokio::fs::remove_file(&tmp).await;
            return Err(e);
        }
    };
    tokio::fs::rename(&tmp, &path).await?;
    sqlx::query("INSERT INTO blobs (user_id, sha256, size, created_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
        .bind(&session.user_id)
        .bind(&sha)
        .bind(size)
        .bind(now())
        .execute(&state.db)
        .await?;
    Ok(StatusCode::CREATED)
}

pub async fn get_blob(
    State(state): State<AppState>,
    session: Session,
    Path(sha): Path<String>,
) -> ApiResult<Response> {
    if !is_sha256(&sha) {
        return Err(ApiError::bad_request("bad_hash"));
    }
    let have: Option<i64> =
        sqlx::query_scalar("SELECT size FROM blobs WHERE user_id = ? AND sha256 = ?")
            .bind(&session.user_id)
            .bind(&sha)
            .fetch_optional(&state.db)
            .await?;
    let size = have.ok_or_else(ApiError::not_found)?;
    let file = tokio::fs::File::open(blob_path(&state, &session.user_id, &sha))
        .await
        .map_err(|_| ApiError::not_found())?;
    let body = Body::from_stream(tokio_util_stream(file));
    Ok((
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_string()),
            (header::CONTENT_LENGTH, size.to_string()),
        ],
        body,
    )
        .into_response())
}

/// Reads a file as a stream of chunks.
fn tokio_util_stream(file: tokio::fs::File) -> impl Stream<Item = std::io::Result<Vec<u8>>> {
    futures_util::stream::unfold(file, |mut file| async move {
        use tokio::io::AsyncReadExt;
        let mut buf = vec![0u8; 64 * 1024];
        match file.read(&mut buf).await {
            Ok(0) => None,
            Ok(n) => {
                buf.truncate(n);
                Some((Ok(buf), file))
            }
            Err(e) => Some((Err(e), file)),
        }
    })
}

fn item_json(
    kind: &str,
    id: String,
    revision: i64,
    seq: i64,
    deleted: bool,
    body: String,
) -> Value {
    let body: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
    json!({ "kind": kind, "id": id, "revision": revision, "seq": seq, "deleted": deleted, "body": body })
}

/// Every live folder and document with its revision, and the cursor to ask for changes after.
pub async fn library(State(state): State<AppState>, session: Session) -> ApiResult<Json<Value>> {
    let rows: Vec<(String, String, i64, i64, bool, String)> =
        sqlx::query_as("SELECT kind, id, revision, seq, deleted, body FROM items WHERE user_id = ? AND deleted = 0 ORDER BY seq")
            .bind(&session.user_id)
            .fetch_all(&state.db)
            .await?;
    let cursor: i64 =
        sqlx::query_scalar("SELECT COALESCE(MAX(seq), 0) FROM counters WHERE user_id = ?")
            .bind(&session.user_id)
            .fetch_one(&state.db)
            .await?;
    let items: Vec<Value> = rows
        .into_iter()
        .map(|(k, id, rev, seq, del, body)| item_json(&k, id, rev, seq, del, body))
        .collect();
    Ok(Json(json!({ "items": items, "cursor": cursor })))
}

#[derive(Deserialize)]
pub struct Since {
    #[serde(default)]
    pub since: i64,
}

/// Everything changed after [Since::since], deletions included, oldest first.
pub async fn changes(
    State(state): State<AppState>,
    session: Session,
    Query(q): Query<Since>,
) -> ApiResult<Json<Value>> {
    let rows: Vec<(String, String, i64, i64, bool, String)> =
        sqlx::query_as("SELECT kind, id, revision, seq, deleted, body FROM items WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT 1000")
            .bind(&session.user_id)
            .bind(q.since)
            .fetch_all(&state.db)
            .await?;
    let cursor = rows.last().map_or(q.since, |r| r.3);
    let more = rows.len() == 1000;
    let items: Vec<Value> = rows
        .into_iter()
        .map(|(k, id, rev, seq, del, body)| item_json(&k, id, rev, seq, del, body))
        .collect();
    Ok(Json(
        json!({ "items": items, "cursor": cursor, "more": more }),
    ))
}

#[derive(Deserialize)]
pub struct ItemWrite {
    /// The revision the client last saw: 0 for a new item.
    pub base_revision: i64,
    /// The document's manifest or the folder's data. Only `blobs`, a list of hashes, is read.
    pub body: Value,
}

pub async fn put_document(
    state: State<AppState>,
    session: Session,
    id: Path<String>,
    body: Json<ItemWrite>,
) -> ApiResult<Json<Value>> {
    put_item(state, session, Kind::Document, id, body).await
}

pub async fn put_folder(
    state: State<AppState>,
    session: Session,
    id: Path<String>,
    body: Json<ItemWrite>,
) -> ApiResult<Json<Value>> {
    put_item(state, session, Kind::Folder, id, body).await
}

#[derive(Deserialize)]
pub struct Base {
    pub base_revision: i64,
}

pub async fn delete_document(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
    Query(q): Query<Base>,
) -> ApiResult<Json<Value>> {
    write_item(
        &state,
        &session.user_id,
        Kind::Document,
        &id,
        q.base_revision,
        None,
    )
    .await
    .map(Json)
}

pub async fn delete_folder(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
    Query(q): Query<Base>,
) -> ApiResult<Json<Value>> {
    write_item(
        &state,
        &session.user_id,
        Kind::Folder,
        &id,
        q.base_revision,
        None,
    )
    .await
    .map(Json)
}

async fn put_item(
    State(state): State<AppState>,
    session: Session,
    kind: Kind,
    Path(id): Path<String>,
    Json(w): Json<ItemWrite>,
) -> ApiResult<Json<Value>> {
    if id.is_empty() || id.len() > 64 {
        return Err(ApiError::bad_request("bad_id"));
    }
    write_item(
        &state,
        &session.user_id,
        kind,
        &id,
        w.base_revision,
        Some(w.body),
    )
    .await
    .map(Json)
}

/// Writes an item if [base] is still its revision, bumping the revision and the user's change
/// sequence; a stale base gets the server's copy back in a 409. A None body deletes it.
pub(crate) async fn write_item(
    state: &AppState,
    user: &str,
    kind: Kind,
    id: &str,
    base: i64,
    body: Option<Value>,
) -> ApiResult<Value> {
    let blobs: Vec<String> = body
        .as_ref()
        .and_then(|b| b.get("blobs"))
        .and_then(|b| b.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|v| v.as_str())
                .filter(|s| is_sha256(s))
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default();
    let mut tx = state.db.begin().await?;
    let current: Option<(i64, i64, bool, String, i64)> = sqlx::query_as(
        "SELECT revision, seq, deleted, body, updated_at FROM items WHERE user_id = ? AND kind = ? AND id = ?",
    )
    .bind(user)
    .bind(kind.as_str())
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?;
    let revision = current.as_ref().map_or(0, |c| c.0);
    if revision != base {
        let (rev, seq, deleted, body, _) = current.unwrap_or((0, 0, true, "null".into(), 0));
        return Err(ApiError::conflict("conflict").with(
            "item",
            item_json(kind.as_str(), id.to_string(), rev, seq, deleted, body),
        ));
    }
    if body.is_none() && current.is_none() {
        return Err(ApiError::not_found());
    }
    let mut missing = Vec::new();
    for sha in &blobs {
        let have: Option<i64> =
            sqlx::query_scalar("SELECT 1 FROM blobs WHERE user_id = ? AND sha256 = ?")
                .bind(user)
                .bind(sha)
                .fetch_optional(&mut *tx)
                .await?;
        if have.is_none() {
            missing.push(sha.clone());
        }
    }
    if !missing.is_empty() {
        return Err(ApiError::bad_request("missing_blobs").with("missing", missing));
    }
    let seq: i64 = sqlx::query_scalar("INSERT INTO counters (user_id, seq) VALUES (?, 1) ON CONFLICT (user_id) DO UPDATE SET seq = seq + 1 RETURNING seq")
        .bind(user)
        .fetch_one(&mut *tx)
        .await?;
    if let Some((rev, _, was_deleted, old, updated)) = &current {
        crate::history::keep(
            state,
            &mut tx,
            user,
            kind,
            id,
            (*rev, *was_deleted, old, *updated),
        )
        .await?;
    }
    let deleted = body.is_none();
    let text = body.map_or_else(|| "null".to_string(), |b| b.to_string());
    sqlx::query(
        "INSERT INTO items (user_id, kind, id, revision, seq, deleted, body, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (user_id, kind, id) DO UPDATE SET revision = excluded.revision, seq = excluded.seq, deleted = excluded.deleted,
         body = excluded.body, updated_at = excluded.updated_at",
    )
    .bind(user)
    .bind(kind.as_str())
    .bind(id)
    .bind(revision + 1)
    .bind(seq)
    .bind(deleted)
    .bind(&text)
    .bind(now())
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM item_blobs WHERE user_id = ? AND kind = ? AND id = ?")
        .bind(user)
        .bind(kind.as_str())
        .bind(id)
        .execute(&mut *tx)
        .await?;
    for sha in &blobs {
        sqlx::query("INSERT INTO item_blobs (user_id, kind, id, sha256) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .bind(user)
            .bind(kind.as_str())
            .bind(id)
            .bind(sha)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    state.events.notify(user, seq);
    if deleted {
        collect_blobs(state, user).await?;
    }
    Ok(json!({ "revision": revision + 1, "seq": seq }))
}

/// Deletes the user's blobs that nothing refers to any more, once they're past the upload grace time.
pub async fn collect_blobs(state: &AppState, user: &str) -> ApiResult<u64> {
    let doomed: Vec<String> = sqlx::query_scalar(
        "SELECT sha256 FROM blobs b WHERE user_id = ? AND created_at < ? AND NOT EXISTS
         (SELECT 1 FROM item_blobs i JOIN items t ON t.user_id = i.user_id AND t.kind = i.kind AND t.id = i.id
          WHERE i.user_id = b.user_id AND i.sha256 = b.sha256 AND t.deleted = 0)
         AND NOT EXISTS (SELECT 1 FROM history_blobs h WHERE h.user_id = b.user_id AND h.sha256 = b.sha256)",
    )
    .bind(user)
    .bind(now() - BLOB_GRACE_MS)
    .fetch_all(&state.db)
    .await?;
    for sha in &doomed {
        sqlx::query("DELETE FROM blobs WHERE user_id = ? AND sha256 = ?")
            .bind(user)
            .bind(sha)
            .execute(&state.db)
            .await?;
        let _ = tokio::fs::remove_file(blob_path(state, user, sha)).await;
    }
    Ok(doomed.len() as u64)
}

/// Drops versions past the history time, then collects every user's unreferenced blobs.
pub async fn collect_all_blobs(state: &AppState) -> ApiResult<u64> {
    crate::history::prune(state).await?;
    let users: Vec<String> = sqlx::query_scalar("SELECT id FROM users")
        .fetch_all(&state.db)
        .await?;
    let mut total = 0;
    for user in users {
        total += collect_blobs(state, &user).await?;
    }
    Ok(total)
}

/// Server-sent events: a `change` event with the new cursor whenever something changes, and a ping now and then.
pub async fn events(
    State(state): State<AppState>,
    session: Session,
) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let stream = BroadcastStream::new(state.events.subscribe(&session.user_id)).filter_map(
        |seq| async move {
            seq.ok()
                .map(|seq| Ok(Event::default().event("change").data(seq.to_string())))
        },
    );
    Sse::new(stream).keep_alive(KeepAlive::new().interval(std::time::Duration::from_secs(25)))
}
