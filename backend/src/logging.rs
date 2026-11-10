use std::{
    fs::{self, OpenOptions},
    io,
    path::Path,
    sync::Mutex,
};

use market_event_analyzer::config::ServerConfig;
use tracing::Subscriber;
use tracing_subscriber::{EnvFilter, layer::SubscriberExt};

/// Keep console output and optionally append plain-text events to a file.
/// File writes are synchronous, so shutdown requires no background-worker flush guard.
pub fn subscriber(
    config: &ServerConfig,
    config_dir: &Path,
    filter: &str,
) -> io::Result<impl Subscriber + Send + Sync> {
    let file_layer = config
        .resolve_log_file(config_dir)
        .map(|path| {
            let file = (|| {
                if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
                    fs::create_dir_all(parent)?;
                }
                OpenOptions::new().create(true).append(true).open(&path)
            })()
            .map_err(|error: io::Error| {
                io::Error::new(
                    error.kind(),
                    format!("could not open server.log_file {}: {error}", path.display()),
                )
            })?;
            Ok::<_, io::Error>(
                tracing_subscriber::fmt::layer()
                    .with_ansi(false)
                    .with_writer(Mutex::new(file)),
            )
        })
        .transpose()?;

    Ok(tracing_subscriber::registry()
        .with(EnvFilter::new(filter))
        .with(tracing_subscriber::fmt::layer())
        .with(file_layer))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        path::PathBuf,
        sync::atomic::{AtomicUsize, Ordering},
    };

    struct TestDir(PathBuf);

    impl TestDir {
        fn new() -> Self {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let path = std::env::temp_dir().join(format!(
                "macro-logging-test-{}-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
                NEXT.fetch_add(1, Ordering::Relaxed),
            ));
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for TestDir {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.0).ok();
        }
    }

    fn config() -> ServerConfig {
        toml::from_str("host = '127.0.0.1'\nport = 8080").unwrap()
    }

    #[test]
    fn empty_path_keeps_console_only_without_creating_directories() {
        let dir = TestDir::new();
        let config_dir = dir.0.join("not-created");
        let subscriber = subscriber(&config(), &config_dir, "info").unwrap();
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!("console-only event");
        });
        assert!(!config_dir.exists());
    }

    #[test]
    fn file_logs_create_parents_append_and_respect_filter_without_ansi() {
        let dir = TestDir::new();
        let mut config = config();
        config.log_file = "logs/nested/backend.log".into();
        let path = dir.0.join(&config.log_file);
        for _ in 0..2 {
            let subscriber = subscriber(&config, &dir.0, "info").unwrap();
            tracing::subscriber::with_default(subscriber, || {
                tracing::info!(count = 7, "persisted event");
                tracing::debug!("filtered event");
            });
        }
        let text = fs::read_to_string(path).unwrap();
        assert_eq!(text.matches("persisted event").count(), 2, "{text}");
        assert!(text.contains("count=7"), "{text}");
        assert!(!text.contains("filtered event"), "{text}");
        assert!(!text.contains('\u{1b}'), "{text}");
    }

    #[test]
    fn absolute_path_is_used_and_existing_contents_are_preserved() {
        let dir = TestDir::new();
        let path = dir.0.join("existing.log");
        fs::write(&path, "previous log\n").unwrap();
        let mut config = config();
        config.log_file = path.to_string_lossy().into_owned();
        let subscriber = subscriber(&config, &dir.0.join("unused"), "info").unwrap();
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!("new event");
        });
        let text = fs::read_to_string(path).unwrap();
        assert!(text.starts_with("previous log\n"), "{text}");
        assert!(text.contains("new event"), "{text}");
        assert!(!dir.0.join("unused").exists());
    }

    #[test]
    fn invalid_file_or_parent_returns_a_configuration_specific_error() {
        let dir = TestDir::new();
        let mut config = config();
        config.log_file = dir.0.to_string_lossy().into_owned();
        let error = subscriber(&config, &dir.0, "info").err().unwrap();
        assert!(error.to_string().contains("server.log_file"));
        let blocked_parent = dir.0.join("not-a-directory");
        fs::write(&blocked_parent, "fixture").unwrap();
        config.log_file = "not-a-directory/backend.log".into();
        let error = subscriber(&config, &dir.0, "info").err().unwrap();
        assert!(error.to_string().contains("server.log_file"));
    }
}
