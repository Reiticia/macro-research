mod api_test_commands;
pub mod bot;
mod earnings_commands;
mod event_commands;
mod key_commands;
pub mod preferences;
pub mod service;
pub mod state;
pub mod telegram;

#[cfg(test)]
mod telegram_tests;
#[cfg(test)]
pub(crate) mod test_support;

pub use service::{AlertService, Severity};
pub use state::{HealthRegistry, SourceHealth};
pub use telegram::{InlineButton, TelegramClient};
