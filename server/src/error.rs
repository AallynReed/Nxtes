use axum::Json;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::{Value, json};

/// An error reply: a status and a JSON body with a machine-readable `error` code.
#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub body: Value,
}

impl ApiError {
    pub fn new(status: StatusCode, code: &str) -> Self {
        ApiError {
            status,
            body: json!({ "error": code }),
        }
    }

    /// The same error with extra fields in its body.
    pub fn with(mut self, key: &str, value: impl Into<Value>) -> Self {
        self.body[key] = value.into();
        self
    }

    pub fn bad_request(code: &str) -> Self {
        Self::new(StatusCode::BAD_REQUEST, code)
    }

    pub fn unauthorized() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, "unauthorized")
    }

    pub fn forbidden(code: &str) -> Self {
        Self::new(StatusCode::FORBIDDEN, code)
    }

    pub fn not_found() -> Self {
        Self::new(StatusCode::NOT_FOUND, "not_found")
    }

    pub fn conflict(code: &str) -> Self {
        Self::new(StatusCode::CONFLICT, code)
    }

    pub fn too_many() -> Self {
        Self::new(StatusCode::TOO_MANY_REQUESTS, "slow_down")
    }

    pub fn internal(error: impl std::fmt::Display) -> Self {
        tracing::error!("internal error: {error}");
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "internal")
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(self.body)).into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(e: sqlx::Error) -> Self {
        ApiError::internal(e)
    }
}

impl From<std::io::Error> for ApiError {
    fn from(e: std::io::Error) -> Self {
        ApiError::internal(e)
    }
}

pub type ApiResult<T> = Result<T, ApiError>;
