use axum::{
    Json,
    extract::{Path, Query, State},
};
use futures_util::future::join_all;
use serde::{Deserialize, Serialize};
use std::{collections::HashSet, str::FromStr};

use crate::{
    AppState,
    error::AppError,
    model::{LiveQuote, MarketReaction, MarketSnapshot, MarketSymbol},
};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MarketResponse {
    snapshots: Vec<MarketSnapshot>,
    reactions: Vec<MarketReaction>,
}

#[derive(Debug, Deserialize)]
pub struct QuotesQuery {
    symbols: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QuotesResponse {
    quotes: Vec<LiveQuote>,
    unavailable: Vec<MarketSymbol>,
}

pub async fn quotes(
    State(state): State<AppState>,
    Query(query): Query<QuotesQuery>,
) -> Result<Json<QuotesResponse>, AppError> {
    let symbols = requested_symbols(query.symbols.as_deref(), state.market_service.symbols())?;
    let service = state.market_service.clone();
    let results = join_all(symbols.into_iter().map(|symbol| {
        let service = service.clone();
        async move { (symbol, service.live_quote(symbol).await) }
    }))
    .await;
    let mut quotes = Vec::new();
    let mut unavailable = Vec::new();
    for (symbol, result) in results {
        match result {
            Ok(quote) => quotes.push(quote),
            Err(error) => {
                tracing::debug!(symbol = %symbol, error = %error, "live market quote unavailable");
                unavailable.push(symbol);
            }
        }
    }
    Ok(Json(QuotesResponse {
        quotes,
        unavailable,
    }))
}

fn requested_symbols(
    raw: Option<&str>,
    configured: &[MarketSymbol],
) -> Result<Vec<MarketSymbol>, AppError> {
    let values = match raw {
        Some(value) if value.trim().is_empty() => {
            return Err(AppError::InvalidRequest("symbols must not be empty".into()));
        }
        Some(value) => value
            .split(',')
            .map(MarketSymbol::from_str)
            .collect::<Result<Vec<_>, _>>()
            .map_err(AppError::InvalidRequest)?,
        None => configured.to_vec(),
    };
    if values.len() > 50 {
        return Err(AppError::InvalidRequest(
            "at most 50 market symbols may be requested".into(),
        ));
    }
    let mut seen = HashSet::new();
    Ok(values
        .into_iter()
        .filter(|symbol| seen.insert(*symbol))
        .collect())
}

pub async fn market(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Json<MarketResponse>, AppError> {
    state.events.get(id).await?;
    Ok(Json(MarketResponse {
        snapshots: state.market.snapshots(id).await?,
        reactions: state.market.reactions(id).await?,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quote_symbols_are_validated_and_deduplicated() {
        let symbols = requested_symbols(Some("BTC,eur/usd,bitcoin"), &[]).unwrap();
        assert_eq!(symbols, [MarketSymbol::Bitcoin, MarketSymbol::EurUsd]);
        assert!(requested_symbols(Some("unknown"), &[]).is_err());
        assert!(requested_symbols(Some("  "), &[]).is_err());
    }
}
