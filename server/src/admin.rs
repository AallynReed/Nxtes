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
        Admin::CollectBlobs => {
            let n = crate::sync::collect_all_blobs(state)
                .await
                .map_err(|e| format!("{:?}", e.body))?;
            Ok(format!("deleted {n} unused blobs"))
        }
    }
}
