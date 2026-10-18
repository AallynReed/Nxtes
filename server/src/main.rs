use clap::{Parser, Subcommand};
use nxtes_server::admin::{self, Admin};
use nxtes_server::config::Config;
use nxtes_server::state::AppState;
use std::time::Duration;

/// The Nxtes sync server. Settings come from NXTES_* environment variables; see the README.
#[derive(Parser)]
#[command(version)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// Runs the server (the default).
    Serve,
    /// Manages accounts.
    #[command(subcommand)]
    Admin(Admin),
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();
    let cli = Cli::parse();
    let config = Config::from_env();
    let state = AppState::open(config).await.unwrap_or_else(|e| {
        eprintln!("{e}");
        std::process::exit(1);
    });
    match cli.command.unwrap_or(Command::Serve) {
        Command::Admin(command) => match admin::run(&state, command).await {
            Ok(out) => println!("{out}"),
            Err(e) => {
                eprintln!("{e}");
                std::process::exit(1);
            }
        },
        Command::Serve => serve(state).await,
    }
}

async fn serve(state: AppState) {
    let addr = state.config.bind.clone();
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|e| panic!("cannot bind {addr}: {e}"));
    tracing::info!("listening on {addr}");
    let collector = state.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(60 * 60)).await;
            if let Err(e) = nxtes_server::sync::collect_all_blobs(&collector).await {
                tracing::warn!("blob collection failed: {:?}", e.body);
            }
        }
    });
    axum::serve(listener, nxtes_server::app(state))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await
        .expect("server stopped");
}
