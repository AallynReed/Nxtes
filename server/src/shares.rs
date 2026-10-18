use crate::error::{ApiError, ApiResult};
use crate::pages::{escape, page};
use crate::state::{AppState, Session};
use crate::util::{new_id, now, token};
use axum::Json;
use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{Html, IntoResponse, Response};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::{Value, json};
use std::path::PathBuf;
use tokio::io::AsyncWriteExt;

const DAY_MS: i64 = 24 * 60 * 60 * 1000;
const MAX_TITLE: usize = 200;

fn share_path(state: &AppState, user: &str, id: &str) -> PathBuf {
    state
        .config
        .share_dir()
        .join(user)
        .join(format!("{id}.pdf"))
}

#[derive(Deserialize)]
pub struct NewShare {
    #[serde(default)]
    pub title: String,
    /// Days until the link stops working; none for a link that lasts until it's removed.
    pub days: Option<i64>,
}

type Row = (String, String, String, i64, i64, i64, Option<i64>);

fn share_json(state: &AppState, (id, token, title, size, views, created, expires): Row) -> Value {
    json!({
        "id": id,
        "url": format!("{}/s/{}", state.config.public_url, token),
        "title": title,
        "size": size,
        "views": views,
        "created_at": created,
        "expires_at": expires,
    })
}

/// Stores an uploaded PDF and returns a link anyone can open it with.
pub async fn create(
    State(state): State<AppState>,
    session: Session,
    Query(q): Query<NewShare>,
    body: Body,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let title: String = q.title.trim().chars().take(MAX_TITLE).collect();
    let title = if title.is_empty() {
        "Untitled".to_string()
    } else {
        title
    };
    let expires = match q.days {
        Some(d) if !(1..=3650).contains(&d) => return Err(ApiError::bad_request("bad_days")),
        Some(d) => Some(now() + d * DAY_MS),
        None => None,
    };
    let room = crate::sync::quota(&state, &session.user_id).await?
        - crate::sync::used_bytes(&state, &session.user_id).await?;
    let id = new_id();
    let path = share_path(&state, &session.user_id, &id);
    tokio::fs::create_dir_all(path.parent().unwrap()).await?;
    let tmp = path.with_extension("part");
    let result = async {
        let mut file = tokio::fs::File::create(&tmp).await?;
        let mut size: i64 = 0;
        let mut head: Vec<u8> = Vec::new();
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
            if head.len() < 5 {
                head.extend(chunk.iter().take(5 - head.len()));
            }
            file.write_all(&chunk).await?;
        }
        file.flush().await?;
        if head != b"%PDF-".as_slice() {
            return Err(ApiError::bad_request("not_a_pdf"));
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
    let row: Row = (id, token(), title, size, 0, now(), expires);
    sqlx::query("INSERT INTO shares (id, user_id, token, title, size, views, created_at, expires_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?)")
        .bind(&row.0)
        .bind(&session.user_id)
        .bind(&row.1)
        .bind(&row.2)
        .bind(row.3)
        .bind(row.5)
        .bind(row.6)
        .execute(&state.db)
        .await?;
    Ok((StatusCode::CREATED, Json(share_json(&state, row))))
}

pub async fn list(State(state): State<AppState>, session: Session) -> ApiResult<Json<Value>> {
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT id, token, title, size, views, created_at, expires_at FROM shares WHERE user_id = ? ORDER BY created_at DESC",
    )
    .bind(&session.user_id)
    .fetch_all(&state.db)
    .await?;
    let shares: Vec<Value> = rows.into_iter().map(|r| share_json(&state, r)).collect();
    Ok(Json(json!({ "shares": shares })))
}

/// Stops sharing: the link stops working and the PDF is deleted.
pub async fn remove(
    State(state): State<AppState>,
    session: Session,
    Path(id): Path<String>,
) -> ApiResult<StatusCode> {
    let done = sqlx::query("DELETE FROM shares WHERE id = ? AND user_id = ?")
        .bind(&id)
        .bind(&session.user_id)
        .execute(&state.db)
        .await?;
    if done.rows_affected() == 0 {
        return Err(ApiError::not_found());
    }
    let _ = tokio::fs::remove_file(share_path(&state, &session.user_id, &id)).await;
    Ok(StatusCode::NO_CONTENT)
}

/// Deletes shares whose links have expired.
pub async fn collect_expired(state: &AppState) -> ApiResult<u64> {
    let doomed: Vec<(String, String)> = sqlx::query_as(
        "SELECT id, user_id FROM shares WHERE expires_at IS NOT NULL AND expires_at < ?",
    )
    .bind(now())
    .fetch_all(&state.db)
    .await?;
    for (id, user) in &doomed {
        sqlx::query("DELETE FROM shares WHERE id = ?")
            .bind(id)
            .execute(&state.db)
            .await?;
        let _ = tokio::fs::remove_file(share_path(state, user, id)).await;
    }
    Ok(doomed.len() as u64)
}

/// The live share behind a link: its id, owner and title.
async fn find(state: &AppState, token: &str) -> ApiResult<Option<(String, String, String)>> {
    Ok(sqlx::query_as(
        "SELECT id, user_id, title FROM shares WHERE token = ? AND (expires_at IS NULL OR expires_at > ?)",
    )
    .bind(token)
    .bind(now())
    .fetch_optional(&state.db)
    .await?)
}

fn private(mut response: Response) -> Response {
    let headers = response.headers_mut();
    headers.insert("x-robots-tag", HeaderValue::from_static("noindex"));
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    response
}

fn gone() -> Response {
    private(
        (
            StatusCode::NOT_FOUND,
            page(
                "This link doesn't work any more",
                "<p>It expired, or the person who shared it stopped sharing.</p>",
            ),
        )
            .into_response(),
    )
}

/// The web viewer: the shared PDF drawn page by page with pdf.js.
pub async fn view(State(state): State<AppState>, Path(token): Path<String>) -> ApiResult<Response> {
    let Some((id, _, title)) = find(&state, &token).await? else {
        return Ok(gone());
    };
    sqlx::query("UPDATE shares SET views = views + 1 WHERE id = ?")
        .bind(&id)
        .execute(&state.db)
        .await?;
    let base = state.config.pdfjs_base();
    let html = VIEWER
        .replace("{{title}}", &escape(&title))
        .replace("{{file}}", &format!("/s/{}/file.pdf", escape(&token)))
        .replace("{{name}}", &escape(&file_name(&title)))
        .replace("{{base}}", &base);
    let cdn = if base.starts_with("http") {
        " https://cdn.jsdelivr.net"
    } else {
        ""
    };
    let policy = format!(
        "default-src 'self'; script-src 'self' 'unsafe-inline'{cdn}; worker-src 'self' blob:{cdn}; connect-src 'self'{cdn}; style-src 'unsafe-inline'; img-src 'self' data: blob:; frame-ancestors 'none'"
    );
    let mut response = private(Html(html).into_response());
    response.headers_mut().insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_str(&policy).map_err(ApiError::internal)?,
    );
    Ok(response)
}

/// The shared PDF itself.
pub async fn file(State(state): State<AppState>, Path(token): Path<String>) -> ApiResult<Response> {
    let Some((id, user, title)) = find(&state, &token).await? else {
        return Ok(gone());
    };
    let bytes = tokio::fs::read(share_path(&state, &user, &id))
        .await
        .map_err(|_| ApiError::not_found())?;
    let disposition = format!("inline; filename=\"{}\"", file_name(&title));
    Ok(private(
        (
            [
                (header::CONTENT_TYPE, "application/pdf".to_string()),
                (header::CONTENT_DISPOSITION, disposition),
                (header::CACHE_CONTROL, "private, max-age=300".to_string()),
            ],
            bytes,
        )
            .into_response(),
    ))
}

/// A safe ASCII file name for a title.
fn file_name(title: &str) -> String {
    let base: String = title
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || " -_.()".contains(c) {
                c
            } else {
                '_'
            }
        })
        .collect();
    let base = base.trim().trim_matches('.');
    format!("{}.pdf", if base.is_empty() { "document" } else { base })
}

const VIEWER: &str = r#"<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>{{title}} · Nxtes</title>
<style>
:root{color-scheme:light dark;--bg:#e9eaee;--bar:#fff;--text:#1d1f23;--muted:#5f6368;--accent:#3a6df0}
@media (prefers-color-scheme:dark){:root{--bg:#121316;--bar:#202329;--text:#e8eaed;--muted:#9aa0a6;--accent:#8ab4f8}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.4 system-ui,sans-serif}
header{position:sticky;top:0;z-index:1;display:flex;align-items:center;gap:4px;padding:8px 16px;background:var(--bar);box-shadow:0 1px 4px #0003}
h1{flex:1;min-width:0;font-size:16px;font-weight:600;margin:0 8px 0 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#count{color:var(--muted);margin-right:8px;white-space:nowrap}
button,a{border:0;background:none;color:var(--accent);font:inherit;padding:6px 10px;border-radius:8px;cursor:pointer;text-decoration:none;white-space:nowrap}
button:hover,a:hover{background:#8882}
main{display:flex;flex-direction:column;align-items:safe center;gap:12px;padding:16px;overflow-x:auto}
.page{flex:none;background:#fff;box-shadow:0 1px 6px #0003}
.page canvas{display:block;width:100%;height:100%}
#status{margin:48px 0;color:var(--muted);text-align:center}
@media (max-width:560px){#count{display:none}header{padding:8px}}
</style>
</head>
<body>
<header>
<h1>{{title}}</h1>
<span id="count"></span>
<button id="out" aria-label="Zoom out">&minus;</button>
<button id="fit">Fit</button>
<button id="in" aria-label="Zoom in">+</button>
<a href="{{file}}" download="{{name}}">Download</a>
</header>
<main id="pages"><p id="status">Loading…</p></main>
<script type="module">
import * as pdfjs from "{{base}}/pdf.min.mjs";
pdfjs.GlobalWorkerOptions.workerSrc = "{{base}}/pdf.worker.min.mjs";
const main = document.getElementById("pages");
const status = document.getElementById("status");
const views = [];
let scale = 1, fitting = true;
const observer = new IntersectionObserver(entries => {
  for (const entry of entries) if (entry.isIntersecting) draw(entry.target.view);
}, { rootMargin: "1000px 0px" });

function fitScale() {
  const widest = Math.max(...views.map(v => v.size.width));
  return Math.min(main.clientWidth - 32, 1100) / widest;
}

function layout() {
  observer.disconnect();
  for (const v of views) {
    v.el.style.width = Math.floor(v.size.width * scale) + "px";
    v.el.style.height = Math.floor(v.size.height * scale) + "px";
    v.drawn = 0;
    observer.observe(v.el);
  }
}

async function draw(v) {
  if (v.drawn === scale) return;
  const at = v.drawn = scale;
  const viewport = v.page.getViewport({ scale: at * (window.devicePixelRatio || 1) });
  const canvas = document.createElement("canvas");
  canvas.width = Math.floor(viewport.width);
  canvas.height = Math.floor(viewport.height);
  await v.page.render({ canvasContext: canvas.getContext("2d"), viewport }).promise;
  if (v.drawn === at) v.el.replaceChildren(canvas);
}

function zoom(next, fit = false) {
  fitting = fit;
  scale = Math.min(Math.max(next, 0.25), 8);
  layout();
}

try {
  const doc = await pdfjs.getDocument("{{file}}").promise;
  for (let i = 1; i <= doc.numPages; i++) {
    const page = await doc.getPage(i);
    const el = document.createElement("div");
    el.className = "page";
    const view = { page, el, size: page.getViewport({ scale: 1 }), drawn: 0 };
    el.view = view;
    views.push(view);
  }
  status.remove();
  main.append(...views.map(v => v.el));
  document.getElementById("count").textContent = doc.numPages === 1 ? "1 page" : doc.numPages + " pages";
  zoom(fitScale(), true);
} catch (e) {
  status.textContent = "This PDF can't be shown here, but you can download it.";
}
document.getElementById("in").onclick = () => zoom(scale * 1.25);
document.getElementById("out").onclick = () => zoom(scale / 1.25);
document.getElementById("fit").onclick = () => zoom(fitScale(), true);
addEventListener("resize", () => { if (fitting && views.length) zoom(fitScale(), true); });
</script>
</body>
</html>
"#;
