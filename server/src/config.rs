use std::path::PathBuf;

/// Server settings, read from `NXTES_*` environment variables.
#[derive(Clone, Debug)]
pub struct Config {
    pub bind: String,
    /// Holds the database (`nxtes.db`), the blobs and shared PDFs.
    pub data_dir: PathBuf,
    /// The address users reach the server at, for links in emails.
    pub public_url: String,
    /// `smtp://host:port`, `smtps://user:pass@host` and the like; mail is only logged when unset.
    pub smtp_url: Option<String>,
    pub mail_from: String,
    pub require_email_verification: bool,
    pub allow_registration: bool,
    pub default_quota_bytes: i64,
    /// 0 for no limit.
    pub max_devices: i64,
    pub max_blob_bytes: usize,
    /// Days earlier versions of documents and folders are kept for restoring; 0 keeps none.
    pub history_days: i64,
    /// Static files for the web viewer, such as `pdfjs/`; the viewer uses a CDN without them.
    pub assets_dir: Option<PathBuf>,
}

/// Where the web viewer loads pdf.js from when the server has no copy of its own.
pub const PDFJS_CDN: &str = "https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/legacy/build";

impl Config {
    pub fn from_env() -> Self {
        fn var(name: &str) -> Option<String> {
            std::env::var(name).ok().filter(|v| !v.trim().is_empty())
        }
        fn flag(name: &str, default: bool) -> bool {
            var(name).map_or(default, |v| {
                matches!(v.to_ascii_lowercase().as_str(), "1" | "true" | "yes" | "on")
            })
        }
        fn number(name: &str, default: i64) -> i64 {
            var(name).and_then(|v| v.parse().ok()).unwrap_or(default)
        }
        let bind = var("NXTES_BIND").unwrap_or_else(|| "127.0.0.1:8080".into());
        Config {
            public_url: var("NXTES_PUBLIC_URL")
                .unwrap_or_else(|| format!("http://{bind}"))
                .trim_end_matches('/')
                .to_string(),
            bind,
            data_dir: var("NXTES_DATA").map_or_else(|| PathBuf::from("data"), PathBuf::from),
            smtp_url: var("NXTES_SMTP_URL"),
            mail_from: var("NXTES_MAIL_FROM").unwrap_or_else(|| "Nxtes <nxtes@localhost>".into()),
            require_email_verification: flag("NXTES_REQUIRE_EMAIL_VERIFICATION", true),
            allow_registration: flag("NXTES_ALLOW_REGISTRATION", true),
            default_quota_bytes: number("NXTES_DEFAULT_QUOTA_MB", 2048) * 1024 * 1024,
            max_devices: number("NXTES_MAX_DEVICES", 0),
            max_blob_bytes: number("NXTES_MAX_BLOB_MB", 1024) as usize * 1024 * 1024,
            history_days: number("NXTES_HISTORY_DAYS", 30).max(0),
            assets_dir: var("NXTES_ASSETS").map(PathBuf::from),
        }
    }

    /// Settings for tests: everything in [dir], mail logged, generous limits.
    pub fn for_tests(dir: PathBuf) -> Self {
        Config {
            bind: "127.0.0.1:0".into(),
            data_dir: dir,
            public_url: "http://nxtes.test".into(),
            smtp_url: None,
            mail_from: "Nxtes <nxtes@nxtes.test>".into(),
            require_email_verification: true,
            allow_registration: true,
            default_quota_bytes: 10 * 1024 * 1024,
            max_devices: 0,
            max_blob_bytes: 4 * 1024 * 1024,
            history_days: 30,
            assets_dir: None,
        }
    }

    pub fn blob_dir(&self) -> PathBuf {
        self.data_dir.join("blobs")
    }

    pub fn share_dir(&self) -> PathBuf {
        self.data_dir.join("shares")
    }

    /// The base URL of pdf.js for the viewer: the server's own copy if it has one.
    pub fn pdfjs_base(&self) -> String {
        match &self.assets_dir {
            Some(dir) if dir.join("pdfjs/pdf.min.mjs").exists() => "/assets/pdfjs".into(),
            _ => PDFJS_CDN.into(),
        }
    }
}
