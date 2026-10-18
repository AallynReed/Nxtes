use crate::error::{ApiError, ApiResult};
use crate::state::{AppState, Session};
use crate::sync::{Kind, write_item};
use crate::util::now;
use axum::Json;
use axum::extract::{Path, State};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::{Sqlite, Transaction};

const DAY_MS: i64 = 24 * 60 * 60 * 1000;

/// Keeps the version an item write is about to replace, with the blobs it uses, so it can be restored.
pub(crate) async fn keep(
    state: &AppState,
    tx: &mut Transaction<'_, Sqlite>,
    user: &str,
    kind: Kind,
    id: &str,
    (revision, deleted, body, updated): (i64, bool, &str, i64),
) -> ApiResult<()> {
    if state.config.history_days == 0 {
        return Ok(());
    }
    sqlx::query(
        "INSERT OR REPLACE INTO item_history (user_id, kind, id, revision, deleted, body, updated_at, replaced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user)
    .bind(kind.as_str())
    .bind(id)
    .bind(revision)
    .bind(deleted)
    .bind(body)
    .bind(updated)
    .bind(now())
    .execute(&mut **tx)
    .await?;
    sqlx::query(
        "INSERT OR IGNORE INTO history_blobs (user_id, kind, id, revision, sha256)
         SELECT user_id, kind, id, ?, sha256 FROM item_blobs WHERE user_id = ? AND kind = ? AND id = ?",
    )
    .bind(revision)
    .bind(user)
    .bind(kind.as_str())
    .bind(id)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

/// Forgets versions replaced longer ago than the history time; their blobs go at the next collection.
pub async fn prune(state: &AppState) -> ApiResult<u64> {
    let done = sqlx::query("DELETE FROM item_history WHERE replaced_at < ?")
        .bind(now() - state.config.history_days * DAY_MS)
        .execute(&state.db)
        .await?;
    Ok(done.rows_affected())
}

/// A document's title or a folder's name, for lists of versions.
fn title_of(body: &str) -> Option<String> {
    let body: Value = serde_json::from_str(body).ok()?;
    body.pointer("/document/title")
        .or_else(|| body.pointer("/folder/name"))
        .and_then(Value::as_str)
        .map(str::to_string)
}

type Version = (i64, bool, String, i64, i64);

/// An item's kept versions, newest first, and its current one.
pub async fn versions(state: &AppState, user: &str, kind: Kind, id: &str) -> ApiResult<Value> {
    let current: Option<(i64, bool, String, i64)> = sqlx::query_as(
        "SELECT revision, deleted, body, updated_at FROM items WHERE user_id = ? AND kind = ? AND id = ?",
    )
    .bind(user)
    .bind(kind.as_str())
    .bind(id)
    .fetch_optional(&state.db)
    .await?;
    let Some((revision, deleted, body, updated)) = current else {
        return Err(ApiError::not_found());
    };
    let rows: Vec<Version> = sqlx::query_as(
        "SELECT revision, deleted, body, updated_at, replaced_at FROM item_history WHERE user_id = ? AND kind = ? AND id = ? ORDER BY revision DESC",
    )
    .bind(user)
    .bind(kind.as_str())
    .bind(id)
    .fetch_all(&state.db)
    .await?;
    let list: Vec<Value> = rows
        .into_iter()
        .map(|(revision, deleted, body, updated, replaced)| {
            json!({ "revision": revision, "deleted": deleted, "title": title_of(&body), "updated_at": updated, "replaced_at": replaced })
        })
        .collect();
    Ok(json!({
        "current": { "revision": revision, "deleted": deleted, "title": title_of(&body), "updated_at": updated },
        "versions": list,
    }))
}

/// Makes a kept version the current one, as a new revision; the version it replaces is kept in turn.
pub async fn restore(
    state: &AppState,
    user: &str,
    kind: Kind,
    id: &str,
    revision: i64,
) -> ApiResult<Value> {
    let kept: Option<(bool, String)> = sqlx::query_as(
        "SELECT deleted, body FROM item_history WHERE user_id = ? AND kind = ? AND id = ? AND revision = ?",
    )
    .bind(user)
    .bind(kind.as_str())
    .bind(id)
    .bind(revision)
    .fetch_optional(&state.db)
    .await?;
    let (deleted, body) = kept.ok_or_else(ApiError::not_found)?;
    let current: i64 =
        sqlx::query_scalar("SELECT revision FROM items WHERE user_id = ? AND kind = ? AND id = ?")
            .bind(user)
            .bind(kind.as_str())
            .bind(id)
            .fetch_one(&state.db)
            .await?;
    let body = if deleted {
        None
    } else {
        Some(serde_json::from_str(&body).map_err(ApiError::internal)?)
    };
    write_item(state, user, kind, id, current, body).await
}

pub async fn document_versions(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    versions(&state, &session.user_id, Kind::Document, &id)
        .await
        .map(Json)
}

pub async fn folder_versions(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    versions(&state, &session.user_id, Kind::Folder, &id)
        .await
        .map(Json)
}

#[derive(Deserialize)]
pub struct Restore {
    pub revision: i64,
}

pub async fn restore_document(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
    Json(r): Json<Restore>,
) -> ApiResult<Json<Value>> {
    restore(&state, &session.user_id, Kind::Document, &id, r.revision)
        .await
        .map(Json)
}

pub async fn restore_folder(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
    Json(r): Json<Restore>,
) -> ApiResult<Json<Value>> {
    restore(&state, &session.user_id, Kind::Folder, &id, r.revision)
        .await
        .map(Json)
}

/// Deleted items that still have a kept version to bring back, most recently deleted first.
pub async fn deleted_items(state: &AppState, user: &str) -> ApiResult<Vec<Value>> {
    let rows: Vec<(String, String, i64, i64, String)> = sqlx::query_as(
        "SELECT t.kind, t.id, t.updated_at, h.revision, h.body FROM items t
         JOIN item_history h ON h.user_id = t.user_id AND h.kind = t.kind AND h.id = t.id
         WHERE t.user_id = ? AND t.deleted = 1 AND h.deleted = 0
           AND h.revision = (SELECT MAX(revision) FROM item_history x WHERE x.user_id = t.user_id AND x.kind = t.kind AND x.id = t.id AND x.deleted = 0)
         ORDER BY t.updated_at DESC",
    )
    .bind(user)
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(kind, id, deleted_at, revision, body)| {
            json!({ "kind": kind, "id": id, "title": title_of(&body), "deleted_at": deleted_at, "revision": revision })
        })
        .collect())
}

pub async fn deleted(State(state): State<AppState>, session: Session) -> ApiResult<Json<Value>> {
    Ok(Json(
        json!({ "items": deleted_items(&state, &session.user_id).await? }),
    ))
}
