use axum::Router;
use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use nxtes_server::config::Config;
use nxtes_server::state::AppState;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tower::ServiceExt;

struct Server {
    app: Router,
    state: AppState,
    _dir: tempfile::TempDir,
}

impl Server {
    async fn new() -> Self {
        Self::with(|_| {}).await
    }

    async fn with(change: impl FnOnce(&mut Config)) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let mut config = Config::for_tests(dir.path().to_path_buf());
        change(&mut config);
        let state = AppState::open(config).await.unwrap();
        Server {
            app: nxtes_server::app(state.clone()),
            state,
            _dir: dir,
        }
    }

    async fn call(
        &self,
        method: &str,
        uri: &str,
        token: Option<&str>,
        body: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut req = Request::builder().method(method).uri(uri);
        if let Some(t) = token {
            req = req.header("authorization", format!("Bearer {t}"));
        }
        let req = match body {
            Some(b) => req
                .header("content-type", "application/json")
                .body(Body::from(b.to_string())),
            None => req.body(Body::empty()),
        }
        .unwrap();
        let res = self.app.clone().oneshot(req).await.unwrap();
        let status = res.status();
        let bytes = res.into_body().collect().await.unwrap().to_bytes();
        (
            status,
            serde_json::from_slice(&bytes).unwrap_or(Value::Null),
        )
    }

    async fn raw(
        &self,
        method: &str,
        uri: &str,
        token: &str,
        body: Vec<u8>,
    ) -> (StatusCode, Vec<u8>) {
        let req = Request::builder()
            .method(method)
            .uri(uri)
            .header("authorization", format!("Bearer {token}"))
            .body(Body::from(body))
            .unwrap();
        let res = self.app.clone().oneshot(req).await.unwrap();
        let status = res.status();
        (
            status,
            res.into_body().collect().await.unwrap().to_bytes().to_vec(),
        )
    }

    /// The token in the last mail sent, from its link.
    fn last_mail_token(&self) -> String {
        let outbox = self.state.mailer.outbox.lock().unwrap();
        let body = &outbox.last().expect("no mail").2;
        let link = body
            .lines()
            .find(|l| l.starts_with("http://nxtes.test/"))
            .expect("no link");
        link.rsplit('/').next().unwrap().to_string()
    }

    /// Registers, verifies and signs in; returns the access token.
    async fn signed_in(&self, email: &str) -> String {
        self.call(
            "POST",
            "/v1/auth/register",
            None,
            Some(json!({ "email": email, "password": "correct horse" })),
        )
        .await;
        let t = self.last_mail_token();
        assert_eq!(
            self.call("POST", "/v1/auth/verify", None, Some(json!({ "token": t })))
                .await
                .0,
            StatusCode::OK
        );
        let (status, body) = self
            .call(
                "POST",
                "/v1/auth/login",
                None,
                Some(
                    json!({ "email": email, "password": "correct horse", "device_name": "Tablet" }),
                ),
            )
            .await;
        assert_eq!(status, StatusCode::OK);
        body["access_token"].as_str().unwrap().to_string()
    }
}

fn sha(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

#[tokio::test]
async fn accounts_need_a_confirmed_address_before_signing_in() {
    let s = Server::new().await;
    let (status, body) = s
        .call(
            "POST",
            "/v1/auth/register",
            None,
            Some(json!({ "email": "Ana@Example.com", "password": "correct horse" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(body["verification_required"], true);
    let (status, body) = s
        .call(
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({ "email": "ana@example.com", "password": "correct horse" })),
        )
        .await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::FORBIDDEN, Some("email_not_verified"))
    );

    let t = s.last_mail_token();
    assert_eq!(
        s.call("POST", "/v1/auth/verify", None, Some(json!({ "token": t })))
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        s.call("POST", "/v1/auth/verify", None, Some(json!({ "token": t })))
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    let (status, body) = s.call("POST", "/v1/auth/login", None, Some(json!({ "email": "ana@example.com", "password": "correct horse", "device_name": "Tablet" }))).await;
    assert_eq!(status, StatusCode::OK);
    let access = body["access_token"].as_str().unwrap();
    let (_, devices) = s.call("GET", "/v1/devices", Some(access), None).await;
    assert_eq!(devices["devices"][0]["name"], "Tablet");
    assert_eq!(devices["devices"][0]["current"], true);

    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/register",
            None,
            Some(json!({ "email": "ana@example.com", "password": "another one" })),
        )
        .await;
    assert_eq!(status, StatusCode::CONFLICT);
    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({ "email": "ana@example.com", "password": "wrong password" })),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn refresh_rotates_tokens_and_logout_ends_the_device() {
    let s = Server::new().await;
    s.call(
        "POST",
        "/v1/auth/register",
        None,
        Some(json!({ "email": "bo@example.com", "password": "correct horse" })),
    )
    .await;
    let t = s.last_mail_token();
    s.call("POST", "/v1/auth/verify", None, Some(json!({ "token": t })))
        .await;
    let (_, login) = s
        .call(
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({ "email": "bo@example.com", "password": "correct horse" })),
        )
        .await;
    let (status, fresh) = s
        .call(
            "POST",
            "/v1/auth/refresh",
            None,
            Some(json!({ "refresh_token": login["refresh_token"] })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        s.call("GET", "/v1/account", login["access_token"].as_str(), None)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    let access = fresh["access_token"].as_str().unwrap();
    assert_eq!(
        s.call("GET", "/v1/account", Some(access), None).await.0,
        StatusCode::OK
    );
    assert_eq!(
        s.call("POST", "/v1/auth/logout", Some(access), None)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        s.call("GET", "/v1/account", Some(access), None).await.0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn password_resets_sign_every_device_out() {
    let s = Server::new().await;
    let access = s.signed_in("cy@example.com").await;
    assert_eq!(
        s.call(
            "POST",
            "/v1/auth/password-reset",
            None,
            Some(json!({ "email": "cy@example.com" }))
        )
        .await
        .0,
        StatusCode::OK
    );
    let t = s.last_mail_token();
    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/password-reset/confirm",
            None,
            Some(json!({ "token": t, "password": "short" })),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let t = {
        sqlx::query("DELETE FROM email_tokens")
            .execute(&s.state.db)
            .await
            .unwrap();
        s.call(
            "POST",
            "/v1/auth/password-reset",
            None,
            Some(json!({ "email": "cy@example.com" })),
        )
        .await;
        s.last_mail_token()
    };
    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/password-reset/confirm",
            None,
            Some(json!({ "token": t, "password": "a new password" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        s.call("GET", "/v1/account", Some(&access), None).await.0,
        StatusCode::UNAUTHORIZED
    );
    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({ "email": "cy@example.com", "password": "a new password" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn reset_mail_is_rate_limited_and_doesnt_reveal_accounts() {
    let s = Server::new().await;
    s.signed_in("di@example.com").await;
    let before = s.state.mailer.outbox.lock().unwrap().len();
    s.call(
        "POST",
        "/v1/auth/password-reset",
        None,
        Some(json!({ "email": "di@example.com" })),
    )
    .await;
    s.call(
        "POST",
        "/v1/auth/password-reset",
        None,
        Some(json!({ "email": "di@example.com" })),
    )
    .await;
    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/password-reset",
            None,
            Some(json!({ "email": "nobody@example.com" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(s.state.mailer.outbox.lock().unwrap().len(), before + 1);
}

#[tokio::test]
async fn email_changes_wait_for_the_new_address() {
    let s = Server::new().await;
    let access = s.signed_in("ed@example.com").await;
    let (status, _) = s
        .call(
            "POST",
            "/v1/account/email",
            Some(&access),
            Some(json!({ "email": "ed@new.example", "password": "correct horse" })),
        )
        .await;
    assert_eq!(status, StatusCode::ACCEPTED);
    let (_, account) = s.call("GET", "/v1/account", Some(&access), None).await;
    assert_eq!(account["email"], "ed@example.com");
    let t = s.last_mail_token();
    assert_eq!(
        s.call(
            "POST",
            "/v1/auth/email/confirm",
            None,
            Some(json!({ "token": t }))
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, account) = s.call("GET", "/v1/account", Some(&access), None).await;
    assert_eq!(account["email"], "ed@new.example");
}

#[tokio::test]
async fn blobs_are_checked_against_their_hash_and_the_quota() {
    let s = Server::with(|c| c.default_quota_bytes = 10).await;
    let access = s.signed_in("fe@example.com").await;
    let data = b"page data".to_vec();
    let hash = sha(&data);
    let (_, missing) = s
        .call(
            "POST",
            "/v1/blobs/missing",
            Some(&access),
            Some(json!({ "hashes": [hash] })),
        )
        .await;
    assert_eq!(missing["missing"][0], hash.as_str());
    assert_eq!(
        s.raw(
            "PUT",
            &format!("/v1/blobs/{}", sha(b"other")),
            &access,
            data.clone()
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        s.raw("PUT", &format!("/v1/blobs/{hash}"), &access, data.clone())
            .await
            .0,
        StatusCode::CREATED
    );
    let (status, back) = s
        .raw("GET", &format!("/v1/blobs/{hash}"), &access, vec![])
        .await;
    assert_eq!((status, back), (StatusCode::OK, data.clone()));
    let (_, usage) = s.call("GET", "/v1/usage", Some(&access), None).await;
    assert_eq!(usage["used"], 9);
    let more = b"too much".to_vec();
    assert_eq!(
        s.raw("PUT", &format!("/v1/blobs/{}", sha(&more)), &access, more)
            .await
            .0,
        StatusCode::INSUFFICIENT_STORAGE
    );

    let other = s.signed_in("gi@example.com").await;
    assert_eq!(
        s.raw("GET", &format!("/v1/blobs/{hash}"), &other, vec![])
            .await
            .0,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn documents_move_by_revision_and_conflicts_return_the_servers_copy() {
    let s = Server::new().await;
    let access = s.signed_in("ha@example.com").await;
    let page = b"ink".to_vec();
    let hash = sha(&page);
    let (status, body) = s
        .call(
            "PUT",
            "/v1/documents/doc-1",
            Some(&access),
            Some(json!({ "base_revision": 0, "body": { "title": "Notes", "blobs": [hash] } })),
        )
        .await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::BAD_REQUEST, Some("missing_blobs"))
    );
    s.raw("PUT", &format!("/v1/blobs/{hash}"), &access, page)
        .await;
    let (status, body) = s
        .call(
            "PUT",
            "/v1/documents/doc-1",
            Some(&access),
            Some(json!({ "base_revision": 0, "body": { "title": "Notes", "blobs": [hash] } })),
        )
        .await;
    assert_eq!(
        (status, body["revision"].as_i64()),
        (StatusCode::OK, Some(1))
    );

    let (status, body) = s
        .call(
            "PUT",
            "/v1/documents/doc-1",
            Some(&access),
            Some(json!({ "base_revision": 0, "body": { "title": "Other" } })),
        )
        .await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["item"]["body"]["title"], "Notes");
    assert_eq!(body["item"]["revision"], 1);

    s.call(
        "PUT",
        "/v1/folders/f-1",
        Some(&access),
        Some(json!({ "base_revision": 0, "body": { "name": "Class" } })),
    )
    .await;
    let (_, library) = s.call("GET", "/v1/library", Some(&access), None).await;
    assert_eq!(library["items"].as_array().unwrap().len(), 2);
    let cursor = library["cursor"].as_i64().unwrap();

    let (status, _) = s
        .call(
            "DELETE",
            "/v1/documents/doc-1?base_revision=1",
            Some(&access),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, changes) = s
        .call(
            "GET",
            &format!("/v1/changes?since={cursor}"),
            Some(&access),
            None,
        )
        .await;
    assert_eq!(changes["items"][0]["id"], "doc-1");
    assert_eq!(changes["items"][0]["deleted"], true);
    let (_, library) = s.call("GET", "/v1/library", Some(&access), None).await;
    assert_eq!(library["items"].as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn unreferenced_blobs_are_collected_after_the_grace_time() {
    let s = Server::new().await;
    let access = s.signed_in("io@example.com").await;
    let kept = b"kept".to_vec();
    let loose = b"loose".to_vec();
    s.raw(
        "PUT",
        &format!("/v1/blobs/{}", sha(&kept)),
        &access,
        kept.clone(),
    )
    .await;
    s.raw(
        "PUT",
        &format!("/v1/blobs/{}", sha(&loose)),
        &access,
        loose.clone(),
    )
    .await;
    s.call(
        "PUT",
        "/v1/documents/d",
        Some(&access),
        Some(json!({ "base_revision": 0, "body": { "blobs": [sha(&kept)] } })),
    )
    .await;
    assert_eq!(
        nxtes_server::sync::collect_all_blobs(&s.state)
            .await
            .unwrap(),
        0
    );
    sqlx::query("UPDATE blobs SET created_at = 0")
        .execute(&s.state.db)
        .await
        .unwrap();
    assert_eq!(
        nxtes_server::sync::collect_all_blobs(&s.state)
            .await
            .unwrap(),
        1
    );
    assert_eq!(
        s.raw("GET", &format!("/v1/blobs/{}", sha(&kept)), &access, vec![])
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        s.raw(
            "GET",
            &format!("/v1/blobs/{}", sha(&loose)),
            &access,
            vec![]
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn device_limits_and_account_deletion() {
    let s = Server::with(|c| c.max_devices = 1).await;
    s.signed_in("jo@example.com").await;
    let (status, body) = s
        .call(
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({ "email": "jo@example.com", "password": "correct horse" })),
        )
        .await;
    assert_eq!(
        (status, body["error"].as_str()),
        (StatusCode::FORBIDDEN, Some("device_limit"))
    );

    let s = Server::new().await;
    let access = s.signed_in("ka@example.com").await;
    let (status, _) = s
        .call(
            "DELETE",
            "/v1/account",
            Some(&access),
            Some(json!({ "password": "wrong password" })),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _) = s
        .call(
            "DELETE",
            "/v1/account",
            Some(&access),
            Some(json!({ "password": "correct horse" })),
        )
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({ "email": "ka@example.com", "password": "correct horse" })),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn emailed_links_open_pages_that_need_a_click() {
    let s = Server::new().await;
    s.call(
        "POST",
        "/v1/auth/register",
        None,
        Some(json!({ "email": "lu@example.com", "password": "correct horse" })),
    )
    .await;
    let t = s.last_mail_token();
    let req = Request::get(format!("/verify/{t}"))
        .body(Body::empty())
        .unwrap();
    let res = s.app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let (status, _) = s
        .call(
            "POST",
            "/v1/auth/login",
            None,
            Some(json!({ "email": "lu@example.com", "password": "correct horse" })),
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let req = Request::post(format!("/verify/{t}"))
        .body(Body::empty())
        .unwrap();
    let res = s.app.clone().oneshot(req).await.unwrap();
    let page =
        String::from_utf8(res.into_body().collect().await.unwrap().to_bytes().to_vec()).unwrap();
    assert!(page.contains("Address confirmed"));
}
