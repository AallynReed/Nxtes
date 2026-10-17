use axum::{Router, routing::get};

fn app() -> Router {
    Router::new().route("/healthz", get(|| async { "ok" }))
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();
    let addr = std::env::var("NXTES_BIND").unwrap_or_else(|_| "127.0.0.1:8080".into());
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|e| panic!("cannot bind {addr}: {e}"));
    tracing::info!("listening on {addr}");
    axum::serve(listener, app()).await.expect("server stopped");
}

#[cfg(test)]
mod tests {
    use super::app;
    use axum::{body::Body, http::Request};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn healthz_returns_ok() {
        let res = app()
            .oneshot(Request::get("/healthz").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), 200);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(&body[..], b"ok");
    }
}
