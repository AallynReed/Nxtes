use crate::config::Config;
use lettre::message::Mailbox;
use lettre::{AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor};
use std::sync::{Arc, Mutex};

/// Sends account emails over SMTP, or logs them when no SMTP server is set up.
#[derive(Clone)]
pub struct Mailer {
    from: Option<Mailbox>,
    transport: Option<AsyncSmtpTransport<Tokio1Executor>>,
    /// Mail "sent" without SMTP, kept for tests: (to, subject, body).
    pub outbox: Arc<Mutex<Vec<(String, String, String)>>>,
}

impl Mailer {
    pub fn new(config: &Config) -> Result<Self, String> {
        let transport = match &config.smtp_url {
            Some(url) => Some(
                AsyncSmtpTransport::<Tokio1Executor>::from_url(url)
                    .map_err(|e| format!("bad NXTES_SMTP_URL: {e}"))?
                    .build(),
            ),
            None => None,
        };
        let from = config
            .mail_from
            .parse::<Mailbox>()
            .map_err(|e| format!("bad NXTES_MAIL_FROM: {e}"))?;
        Ok(Mailer {
            from: Some(from),
            transport,
            outbox: Arc::default(),
        })
    }

    pub async fn send(&self, to: &str, subject: &str, body: String) -> Result<(), String> {
        let Some(transport) = &self.transport else {
            tracing::info!("mail to {to}: {subject}\n{body}");
            self.outbox
                .lock()
                .unwrap()
                .push((to.into(), subject.into(), body));
            return Ok(());
        };
        let message = Message::builder()
            .from(self.from.clone().ok_or("no sender")?)
            .to(to.parse().map_err(|e| format!("bad address {to}: {e}"))?)
            .subject(subject)
            .body(body)
            .map_err(|e| e.to_string())?;
        transport
            .send(message)
            .await
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
}
