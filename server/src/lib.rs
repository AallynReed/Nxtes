pub mod admin;
pub mod auth;
pub mod config;
pub mod error;
pub mod mail;
pub mod pages;
pub mod state;
pub mod sync;
pub mod util;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::routing::{delete, get, post, put};
use state::AppState;

/// Every route; see docs/plan.md for the API.
pub fn app(state: AppState) -> Router {
    let blob_limit = state.config.max_blob_bytes;
    Router::new()
        .route("/", get(pages::home))
        .route("/healthz", get(|| async { "ok" }))
        .route("/v1/auth/register", post(auth::register))
        .route("/v1/auth/login", post(auth::login))
        .route("/v1/auth/refresh", post(auth::refresh))
        .route("/v1/auth/logout", post(auth::logout))
        .route("/v1/auth/verify", post(auth::verify))
        .route(
            "/v1/auth/resend-verification",
            post(auth::resend_verification),
        )
        .route(
            "/v1/auth/password-reset",
            post(auth::request_password_reset),
        )
        .route(
            "/v1/auth/password-reset/confirm",
            post(auth::confirm_password_reset),
        )
        .route("/v1/auth/email/confirm", post(auth::confirm_email))
        .route(
            "/v1/account",
            get(auth::account).delete(auth::delete_account),
        )
        .route("/v1/account/email", post(auth::change_email))
        .route("/v1/devices", get(auth::devices))
        .route("/v1/devices/{id}", delete(auth::remove_device))
        .route("/v1/usage", get(sync::usage))
        .route("/v1/blobs/missing", post(sync::missing_blobs))
        .route(
            "/v1/blobs/{sha256}",
            put(sync::put_blob)
                .get(sync::get_blob)
                .layer(DefaultBodyLimit::max(blob_limit)),
        )
        .route("/v1/library", get(sync::library))
        .route("/v1/changes", get(sync::changes))
        .route("/v1/events", get(sync::events))
        .route(
            "/v1/documents/{id}",
            put(sync::put_document).delete(sync::delete_document),
        )
        .route(
            "/v1/folders/{id}",
            put(sync::put_folder).delete(sync::delete_folder),
        )
        .route(
            "/verify/{token}",
            get(pages::verify_page).post(pages::verify_submit),
        )
        .route(
            "/reset/{token}",
            get(pages::reset_page).post(pages::reset_submit),
        )
        .route(
            "/confirm-email/{token}",
            get(pages::confirm_email_page).post(pages::confirm_email_submit),
        )
        .layer(DefaultBodyLimit::max(4 * 1024 * 1024))
        .layer(tower_http::trace::TraceLayer::new_for_http())
        .with_state(state)
}
