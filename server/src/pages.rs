use crate::auth::{apply_email_change, delete_with_password, reset_password, verify_token};
use crate::state::AppState;
use axum::Form;
use axum::extract::{Path, State};
use axum::response::Html;
use serde::Deserialize;

/// A small page in the server's own style, with [body] already escaped.
pub(crate) fn page(title: &str, body: &str) -> Html<String> {
    layout(title, body, "")
}

fn layout(title: &str, body: &str, class: &str) -> Html<String> {
    Html(format!(
        r#"<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} · Nxtes</title><style>
:root{{color-scheme:light dark;--bg:#f4f5f7;--card:#fff;--text:#1d1f23;--muted:#5f6368;--accent:#3a6df0}}
@media (prefers-color-scheme:dark){{:root{{--bg:#16181c;--card:#202329;--text:#e8eaed;--muted:#9aa0a6;--accent:#8ab4f8}}}}
body{{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif;padding:16px;box-sizing:border-box}}
main{{background:var(--card);border-radius:16px;padding:32px;max-width:420px;width:100%;box-shadow:0 2px 12px #0002}}
h1{{font-size:22px;margin:0 0 12px}}p{{color:var(--muted)}}
input{{width:100%;box-sizing:border-box;padding:10px 12px;margin:6px 0 14px;border:1px solid var(--muted);border-radius:8px;background:transparent;color:inherit;font:inherit}}
button{{background:var(--accent);color:#fff;border:0;border-radius:999px;padding:10px 22px;font:inherit;cursor:pointer}}
a{{color:var(--accent)}}button.danger{{background:#d93025}}
label.check{{display:flex;gap:8px;align-items:center;margin:0 0 18px}}label.check input{{width:auto;margin:0}}
main.doc{{max-width:720px}}.doc p,.doc li{{color:var(--text)}}.doc p.meta{{color:var(--muted)}}.doc h2{{font-size:17px;margin:26px 0 6px}}
</style></head><body><main class="{class}"><h1>{title}</h1>{body}</main></body></html>"#
    ))
}

pub(crate) fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// The verification link's page: a button, so mail scanners that open links don't use the token up.
pub async fn verify_page(Path(token): Path<String>) -> Html<String> {
    page(
        "Confirm your address",
        &format!(
            r#"<p>Confirm this email address for your Nxtes account.</p><form method="post" action="/verify/{}"><button>Confirm</button></form>"#,
            escape(&token)
        ),
    )
}

pub async fn verify_submit(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> Html<String> {
    match verify_token(&state, &token).await {
        Ok(()) => page(
            "Address confirmed",
            "<p>You can sign in to Nxtes on your devices now.</p>",
        ),
        Err(_) => page(
            "This link has expired",
            "<p>Sign in from the app to get a new confirmation link.</p>",
        ),
    }
}

pub async fn reset_page(Path(token): Path<String>) -> Html<String> {
    page(
        "Choose a new password",
        &format!(
            r#"<form method="post" action="/reset/{}"><label>New password<input type="password" name="password" minlength="8" required autocomplete="new-password"></label>
<label>Repeat it<input type="password" name="repeat" minlength="8" required autocomplete="new-password"></label><button>Save</button></form>"#,
            escape(&token)
        ),
    )
}

#[derive(Deserialize)]
pub struct NewPassword {
    pub password: String,
    pub repeat: String,
}

pub async fn reset_submit(
    State(state): State<AppState>,
    Path(token): Path<String>,
    Form(f): Form<NewPassword>,
) -> Html<String> {
    if f.password != f.repeat {
        return page(
            "Passwords don't match",
            &format!(
                r#"<p>Type the same password twice.</p><p><a href="/reset/{}">Try again</a></p>"#,
                escape(&token)
            ),
        );
    }
    match reset_password(&state, &token, f.password).await {
        Ok(()) => page(
            "Password changed",
            "<p>Sign in to Nxtes with your new password. Your devices were signed out.</p>",
        ),
        Err(e) if e.body["error"] == "weak_password" => page(
            "Too short",
            &format!(
                r#"<p>Use at least 8 characters.</p><p><a href="/reset/{}">Try again</a></p>"#,
                escape(&token)
            ),
        ),
        Err(_) => page(
            "This link has expired",
            "<p>Ask for a new reset link from the app.</p>",
        ),
    }
}

pub async fn confirm_email_page(Path(token): Path<String>) -> Html<String> {
    page(
        "Confirm your new address",
        &format!(
            r#"<p>Use this address for your Nxtes account from now on.</p><form method="post" action="/confirm-email/{}"><button>Confirm</button></form>"#,
            escape(&token)
        ),
    )
}

pub async fn confirm_email_submit(
    State(state): State<AppState>,
    Path(token): Path<String>,
) -> Html<String> {
    match apply_email_change(&state, &token).await {
        Ok(()) => page(
            "Address changed",
            "<p>Sign in with your new address from now on.</p>",
        ),
        Err(_) => page(
            "This link has expired",
            "<p>Change the address again from the app.</p>",
        ),
    }
}

pub async fn home() -> Html<String> {
    page(
        "Nxtes",
        r#"<p>This is a Nxtes sync server. Sign in to it from the Nxtes app's settings.</p><p><a href="https://github.com/AallynReed/Nxtes">Nxtes on GitHub</a> · <a href="/privacy">Privacy</a> · <a href="/delete-account">Delete an account</a></p>"#,
    )
}

pub async fn privacy() -> Html<String> {
    layout("Privacy policy", crate::privacy::EN, "doc")
}

pub async fn privacy_pt() -> Html<String> {
    layout("Política de privacidade", crate::privacy::PT, "doc")
}

pub async fn delete_page() -> Html<String> {
    page(
        "Delete your account",
        r#"<p>This permanently deletes your Nxtes account and everything synced to it: documents, version history and share links. Documents already on your devices stay there. Copies in the server's backups are gone within 30 days.</p>
<form method="post" action="/delete-account"><label>Email<input type="email" name="email" required autocomplete="username"></label>
<label>Password<input type="password" name="password" required autocomplete="current-password"></label>
<label class="check"><input type="checkbox" name="confirm" required>I understand this can't be undone</label><button class="danger">Delete account</button></form>
<p>Forgot your password? Choose a new one from the sign-in screen in the app, then come back. You can also delete your account in the app under Settings, Sync.</p>"#,
    )
}

#[derive(Deserialize)]
pub struct DeleteAccount {
    pub email: String,
    pub password: String,
    pub confirm: Option<String>,
}

pub async fn delete_submit(
    State(state): State<AppState>,
    Form(f): Form<DeleteAccount>,
) -> Html<String> {
    if f.confirm.is_none() {
        return page(
            "Nothing was deleted",
            r#"<p>Tick the box to confirm, then try again.</p><p><a href="/delete-account">Back</a></p>"#,
        );
    }
    match delete_with_password(&state, &f.email, f.password).await {
        Ok(true) => page(
            "Account deleted",
            "<p>Your account and everything synced to it are gone from the server.</p>",
        ),
        Ok(false) => page(
            "Nothing was deleted",
            r#"<p>That email and password don't match an account.</p><p><a href="/delete-account">Try again</a></p>"#,
        ),
        Err(_) => page(
            "Something went wrong",
            r#"<p>Nothing was deleted. Try again in a moment.</p><p><a href="/delete-account">Back</a></p>"#,
        ),
    }
}
