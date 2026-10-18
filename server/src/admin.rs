use crate::auth::{hash_password, remove_user};
use crate::state::AppState;
use crate::util::{new_id, normalize_email, now};
use clap::Subcommand;

/// Account management from the server's shell.
#[derive(Subcommand, Debug)]
pub enum Admin {
    /// Adds an account that's already verified.
    CreateUser { email: String, password: String },
    /// Marks an account's address as confirmed.
    VerifyUser { email: String },
    /// Sets an account's storage quota in megabytes; 0 goes back to the default.
    SetQuota { email: String, megabytes: i64 },
    /// Lists accounts with their storage use.
    ListUsers,
    /// Deletes an account and everything it stored.
    DeleteUser { email: String },
    /// Deletes blobs that no document refers to any more.
    CollectBlobs,
    /// Lists an account's documents and folders that have earlier versions, and deleted ones that can come back.
    History { email: String },
    /// Brings a document or folder back to an earlier revision, by default the last one before it was deleted or changed.
    Restore {
        email: String,
        id: String,
        revision: Option<i64>,
    },
    /// Writes a consistent copy of the database to a new file, for snapshots; blobs are plain files to copy alongside.
    Backup { path: String },
}

pub async fn run(state: &AppState, command: Admin) -> Result<String, String> {
    let email_of =
        |e: &str| normalize_email(e).ok_or_else(|| format!("{e} isn't an email address"));
    let user_id = |email: String| async move {
        sqlx::query_scalar::<_, String>("SELECT id FROM users WHERE email = ?")
            .bind(&email)
            .fetch_optional(&state.db)
            .await
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("no account for {email}"))
    };
    match command {
        Admin::CreateUser { email, password } => {
            let email = email_of(&email)?;
            if password.chars().count() < 8 {
                return Err("use a password of at least 8 characters".into());
            }
            let hash = hash_password(password)
                .await
                .map_err(|e| format!("{:?}", e.body))?;
            sqlx::query("INSERT INTO users (id, email, password_hash, verified, created_at) VALUES (?, ?, ?, 1, ?)")
                .bind(new_id())
                .bind(&email)
                .bind(hash)
                .bind(now())
                .execute(&state.db)
                .await
                .map_err(|e| e.to_string())?;
            Ok(format!("created {email}"))
        }
        Admin::VerifyUser { email } => {
            let id = user_id(email_of(&email)?).await?;
            sqlx::query("UPDATE users SET verified = 1 WHERE id = ?")
                .bind(id)
                .execute(&state.db)
                .await
                .map_err(|e| e.to_string())?;
            Ok(format!("verified {email}"))
        }
        Admin::SetQuota { email, megabytes } => {
            let id = user_id(email_of(&email)?).await?;
            let quota = (megabytes > 0).then_some(megabytes * 1024 * 1024);
            sqlx::query("UPDATE users SET quota_bytes = ? WHERE id = ?")
                .bind(quota)
                .bind(id)
                .execute(&state.db)
                .await
                .map_err(|e| e.to_string())?;
            Ok(format!(
                "quota for {email}: {}",
                if megabytes > 0 {
                    format!("{megabytes} MB")
                } else {
                    "default".into()
                }
            ))
        }
        Admin::ListUsers => {
            let rows: Vec<(String, bool, Option<i64>, i64)> = sqlx::query_as(
                "SELECT u.email, u.verified, u.quota_bytes, COALESCE((SELECT SUM(size) FROM blobs b WHERE b.user_id = u.id), 0) FROM users u ORDER BY u.email",
            )
            .fetch_all(&state.db)
            .await
            .map_err(|e| e.to_string())?;
            let default = state.config.default_quota_bytes;
            Ok(rows
                .into_iter()
                .map(|(email, verified, quota, used)| {
                    format!(
                        "{email}\t{}\t{} / {} MB",
                        if verified { "verified" } else { "unverified" },
                        used / 1_048_576,
                        quota.unwrap_or(default) / 1_048_576
                    )
                })
                .collect::<Vec<_>>()
                .join("\n"))
        }
        Admin::DeleteUser { email } => {
            let id = user_id(email_of(&email)?).await?;
            remove_user(state, &id)
                .await
                .map_err(|e| format!("{:?}", e.body))?;
            Ok(format!("deleted {email}"))
        }
        Admin::History { email } => {
            let id = user_id(email_of(&email)?).await?;
            let rows: Vec<(String, String, bool, i64, i64)> = sqlx::query_as(
                "SELECT t.kind, t.id, t.deleted, t.revision, (SELECT COUNT(*) FROM item_history h WHERE h.user_id = t.user_id AND h.kind = t.kind AND h.id = t.id)
                 FROM items t WHERE t.user_id = ? ORDER BY t.updated_at DESC",
            )
            .bind(&id)
            .fetch_all(&state.db)
            .await
            .map_err(|e| e.to_string())?;
            let mut out = Vec::new();
            for (kind, item, deleted, revision, kept) in rows.into_iter().filter(|r| r.4 > 0) {
                let kind_of = if kind == "folder" {
                    crate::sync::Kind::Folder
                } else {
                    crate::sync::Kind::Document
                };
                let title = crate::history::versions(state, &id, kind_of, &item)
                    .await
                    .ok()
                    .and_then(|v| v["versions"][0]["title"].as_str().map(str::to_string))
                    .unwrap_or_default();
                out.push(format!(
                    "{kind}\t{item}\t{}\trevision {revision}\t{kept} earlier\t{title}",
                    if deleted { "deleted" } else { "live" }
                ));
            }
            Ok(if out.is_empty() {
                "no earlier versions".into()
            } else {
                out.join("\n")
            })
        }
        Admin::Restore {
            email,
            id: item,
            revision,
        } => {
            let id = user_id(email_of(&email)?).await?;
            let kind: String =
                sqlx::query_scalar("SELECT kind FROM items WHERE user_id = ? AND id = ?")
                    .bind(&id)
                    .bind(&item)
                    .fetch_optional(&state.db)
                    .await
                    .map_err(|e| e.to_string())?
                    .ok_or_else(|| format!("no document or folder {item}"))?;
            let kind_of = if kind == "folder" {
                crate::sync::Kind::Folder
            } else {
                crate::sync::Kind::Document
            };
            let revision = match revision {
                Some(r) => r,
                None => sqlx::query_scalar::<_, Option<i64>>(
                    "SELECT MAX(revision) FROM item_history WHERE user_id = ? AND kind = ? AND id = ? AND deleted = 0",
                )
                .bind(&id)
                .bind(&kind)
                .bind(&item)
                .fetch_one(&state.db)
                .await
                .map_err(|e| e.to_string())?
                .ok_or_else(|| format!("{item} has no earlier version to restore"))?,
            };
            let done = crate::history::restore(state, &id, kind_of, &item, revision)
                .await
                .map_err(|e| format!("{:?}", e.body))?;
            Ok(format!(
                "restored {kind} {item} to revision {revision}; it is now revision {}",
                done["revision"]
            ))
        }
        Admin::Backup { path } => {
            if std::path::Path::new(&path).exists() {
                return Err(format!("{path} already exists"));
            }
            sqlx::query("VACUUM INTO ?")
                .bind(&path)
                .execute(&state.db)
                .await
                .map_err(|e| e.to_string())?;
            Ok(format!("wrote {path}"))
        }
        Admin::CollectBlobs => {
            let n = crate::sync::collect_all_blobs(state)
                .await
                .map_err(|e| format!("{:?}", e.body))?;
            Ok(format!("deleted {n} unused blobs"))
        }
    }
}
