//! Whitelisted response evidence only. Never include URLs, credentials or raw provider bodies.
use crate::error::AppError;
use chrono::NaiveDate;
use reqwest::header::{CONTENT_TYPE, HeaderMap};
use serde_json::Value;

pub(crate) fn network_failure(error: &reqwest::Error) -> &'static str {
    if error.is_timeout() {
        "timeout"
    } else if error.is_connect() {
        "connection"
    } else {
        "transport"
    }
}

pub(crate) fn content_kind(headers: &HeaderMap) -> &'static str {
    match headers
        .get(CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .map(|value| {
            value
                .split(';')
                .next()
                .unwrap_or("")
                .trim()
                .to_ascii_lowercase()
        })
        .as_deref()
    {
        Some("application/json") => "application/json",
        Some("image/png") => "image/png",
        Some("text/html") => "text/html",
        Some("text/plain") => "text/plain",
        None => "missing",
        _ => "other",
    }
}

fn shape(value: Option<&Value>) -> String {
    match value {
        None => "missing".into(),
        Some(Value::Null) => "null".into(),
        Some(Value::Array(rows)) => format!("array({})", rows.len()),
        Some(Value::Object(_)) => "object".into(),
        Some(Value::String(_)) => "string".into(),
        Some(Value::Bool(_)) => "boolean".into(),
        Some(Value::Number(_)) => "number".into(),
    }
}

pub(super) fn response_shape(root: &Value) -> String {
    let data = root.get("data");
    let rows = match data {
        Some(Value::Array(_)) => data,
        _ => data.and_then(|data| data.get("rows")),
    };
    let as_of = match data.and_then(|data| data.get("asOf")) {
        Some(Value::String(value)) => NaiveDate::parse_from_str(value, "%a, %b %e, %Y")
            .map(|date| date.to_string())
            .unwrap_or_else(|_| "invalid".into()),
        value => shape(value),
    };
    let code = root.pointer("/status/rCode");
    let total = root.get("totalCount");
    format!(
        "JSON={}; rCode={}; data={}; rows={}; asOf={}; totalCount={}",
        shape(Some(root)),
        code.and_then(Value::as_i64)
            .map(|code| code.to_string())
            .unwrap_or_else(|| shape(code)),
        shape(data),
        shape(rows),
        as_of,
        total
            .and_then(Value::as_u64)
            .map(|total| total.to_string())
            .unwrap_or_else(|| shape(total))
    )
}

pub(super) fn with_evidence(error: AppError, evidence: &str) -> AppError {
    match error {
        AppError::Provider(message) => AppError::Provider(format!("{message}; {evidence}")),
        _ => super::provider(&format!("invalid provider payload; {evidence}")),
    }
}
