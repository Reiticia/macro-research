use std::{
    net::{IpAddr, SocketAddr},
    path::Path,
    str::FromStr,
    sync::Arc,
    time::Duration,
};

use market_event_analyzer::{
    AppState,
    ai_analysis::{self, AiAnalysisService},
    alert::{
        AlertService, HealthRegistry, Severity, TelegramClient,
        bot::{BotState, bot_loop},
    },
    analysis::{AnalysisService, RuleEngine},
    api,
    auth::{AuthState, TokenStore},
    backfill::{BackfillRange, BackfillService, repository::BackfillRepository},
    calendar::{
        CalendarService, ForexFactoryProvider, TradingEconomicsApiProvider, TradingViewProvider,
    },
    config::{self, AppConfig},
    fcm::FcmNotifier,
    llm_usage::LlmUsageRepository,
    market::{BinanceProvider, BiquoteProvider, CnbcProvider, MarketService, YahooProvider},
    market_selection::MarketSelector,
    model::MarketSymbol,
    quota::QuotaService,
    repository::{AnalysisRepository, EventRepository, MarketRepository},
    scheduler,
    translation::{EventNameTranslator, OpenAiEventNameTranslator, TranslationService},
    typesafe::TypeSafeVerifier,
};
use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqlitePoolOptions},
};
use tokio::sync::broadcast;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // The config decides the log filter too, so it must be loaded before tracing is installed.
    // RUST_LOG still wins when the operator sets it.
    let config = AppConfig::load()?;
    let config_path = AppConfig::resolve_path()?;
    let config_dir = config.dir(&config_path);
    let log_filter = std::env::var("RUST_LOG")
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .or_else(|| {
            let from_config = config.server.log_level.trim();
            (!from_config.is_empty()).then(|| from_config.to_owned())
        })
        .unwrap_or_else(|| "market_event_analyzer=info,tower_http=info".to_owned());
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::new(log_filter))
        .init();
    tracing::info!("configuration: {}", config_path.display());
    let args: Vec<String> = std::env::args().skip(1).collect();
    // `--check-ai` is a diagnostic that does not touch the database, so it is intercepted
    // before the backfill range parser (which would reject the flag).
    let check_ai = args.iter().any(|arg| arg == "--check-ai");
    let repair_market = args.first().is_some_and(|arg| arg == "--repair-market");
    let history_range = if args.is_empty() || check_ai {
        None
    } else if repair_market {
        Some(BackfillRange::from_repair_args(&args, chrono::Utc::now())?)
    } else {
        Some(BackfillRange::from_args(&args, chrono::Utc::now())?)
    };
    // The local repair reads events from the database and never calls the TradingEconomics
    // calendar, so only a calendar backfill requires its key.
    let history_key = if history_range.is_some() && !repair_market {
        Some(
            config::require_secret(&config.backfill.te_api_key, "backfill.te_api_key")
                .map_err(|error| error.to_string())?,
        )
    } else {
        None
    };
    ensure_database_directory(&config.database.url)?;
    let options = SqliteConnectOptions::from_str(&config.database.url)?
        .create_if_missing(true)
        .foreign_keys(true);
    let pool: SqlitePool = SqlitePoolOptions::new()
        .max_connections(8)
        .connect_with(options)
        .await?;
    sqlx::migrate!("./migrations").run(&pool).await?;

    let direct_http = reqwest::Client::builder()
        .user_agent("market-event-analyzer/0.2 (personal research tool)")
        .timeout(Duration::from_secs(
            config.network.request_timeout_seconds.max(5),
        ))
        .build()?;
    let proxied_http = if config.network.proxy_url.trim().is_empty() {
        direct_http.clone()
    } else {
        reqwest::Client::builder()
            .user_agent("market-event-analyzer/0.2 (personal research tool)")
            .timeout(Duration::from_secs(
                config.network.request_timeout_seconds.max(5),
            ))
            .proxy(reqwest::Proxy::all(config.network.proxy_url.trim())?)
            .build()?
    };
    let client_for = |source: &str| -> reqwest::Client {
        if config.network.proxies(source) {
            proxied_http.clone()
        } else {
            direct_http.clone()
        }
    };

    if check_ai {
        check_ai_relays(&config, &client_for).await?;
        return Ok(());
    }

    let events = EventRepository::new(pool.clone());
    let market = MarketRepository::new(pool.clone());
    let backfill = BackfillRepository::new(pool.clone());
    let analyses = AnalysisRepository::new(pool.clone());

    // A release first seen through a later calendar sync (e.g. after downtime) can never
    // re-enter the watch window; reclassify those rows before the scheduler loops start.
    match events
        .reconcile_missed_releases(
            chrono::Utc::now()
                - chrono::Duration::minutes(config.scheduler.release_timeout_minutes.max(0)),
        )
        .await
    {
        Ok(0) => {}
        Ok(count) => tracing::info!(count, "missed releases reclassified as historical"),
        Err(error) => tracing::warn!(%error, "missed-release reconciliation failed"),
    }

    // Admin alerting: Telegram is optional, the rest of the server works without it.
    let telegram = if config.telegram.enabled {
        config.telegram.bot_token().map(|token| {
            let http = {
                let mut builder = reqwest::Client::builder()
                    .user_agent("market-event-analyzer/0.2")
                    .timeout(Duration::from_secs(
                        config.telegram.poll_timeout_seconds + 20,
                    ));
                if !config.network.proxy_url.trim().is_empty()
                    && let Ok(proxy) = reqwest::Proxy::all(config.network.proxy_url.trim())
                {
                    builder = builder.proxy(proxy);
                }
                builder.build().unwrap_or_else(|_| direct_http.clone())
            };
            Arc::new(TelegramClient::new(http, &config.telegram.api_base, token))
        })
    } else {
        None
    };
    let alerts = Arc::new(AlertService::new(
        telegram.clone(),
        config.telegram.admin_chat_ids(),
        pool.clone(),
        config.alerts.clone(),
    ));
    let health = Arc::new(HealthRegistry::new(
        pool.clone(),
        Some(alerts.clone()),
        config.alerts.failure_threshold,
        config.alerts.cooldown_seconds,
    ));

    let fcm = if config.fcm.enabled {
        let configured_path = config.fcm.service_account_file.trim();
        if configured_path.is_empty() {
            tracing::warn!("FCM enabled but fcm.service_account_file is empty; FCM disabled");
            None
        } else {
            let path = Path::new(configured_path);
            let path = if path.is_absolute() {
                path.to_path_buf()
            } else {
                config_dir.join(path)
            };
            match FcmNotifier::from_file(
                client_for("fcm"),
                &path,
                Duration::from_secs(config.fcm.send_timeout_seconds.max(5)),
            ) {
                Ok(notifier) => Some(Arc::new(notifier.with_health(health.clone()))),
                Err(error) => {
                    tracing::warn!(%error, "FCM disabled because the service account could not be loaded");
                    None
                }
            }
        }
    } else {
        None
    };

    // Model-call audit log. Built before every model user so both paths can record into it.
    let llm_usage = if config.limits.llm_usage_retention_days > 0 {
        let repository = Arc::new(LlmUsageRepository::new(
            pool.clone(),
            config.limits.llm_usage_retention_days,
        ));
        if let Err(error) = repository.prune().await {
            tracing::warn!(%error, "LLM usage pruning failed on startup");
        }
        Some(repository)
    } else {
        None
    };

    // 一个缺失的密钥只关闭它所属的子系统：日历、行情与告警仍然照常工作，这是文档承诺的
    // 降级行为；启动横幅会说明哪个子系统被关了。
    let translation_service = if config.translation.enabled {
        match config.translation.api_key() {
            Ok(api_key) => {
                let translator = Arc::new(
                    OpenAiEventNameTranslator::with_options(
                        client_for("translation"),
                        &config.translation.base_url,
                        config.translation.model.clone(),
                        api_key,
                        market_event_analyzer::openai_compat::ExtraHeaders::from_map(
                            &config.translation.extra_headers,
                        ),
                        config.translation.extra_body.clone(),
                    )?
                    .with_audit_opt(llm_usage.clone()),
                );
                let typesafe_verifier = if config.typesafe.enabled {
                    match config.typesafe.api_key() {
                        Ok(typesafe_key) => match TypeSafeVerifier::new(
                            client_for("typesafe"),
                            &config.typesafe.base_url,
                            typesafe_key,
                            config.typesafe.model.clone(),
                            config.typesafe.review_threshold,
                        ) {
                            Ok(verifier) => Some(Arc::new(
                                verifier
                                    .with_audit_opt(llm_usage.clone())
                                    .with_health(health.clone()),
                            )),
                            Err(error) => {
                                tracing::warn!(%error, "TypeSafe translation verifier disabled");
                                None
                            }
                        },
                        Err(error) => {
                            tracing::warn!(%error, "TypeSafe translation verifier disabled");
                            None
                        }
                    }
                } else {
                    None
                };
                let mut service = TranslationService::new(
                    events.clone(),
                    translator,
                    config.translation.batch_size,
                )
                .with_max_rounds(config.translation.max_rounds)
                .with_health(health.clone());
                if let Some(verifier) = typesafe_verifier {
                    service = service.with_verifier(verifier);
                }
                Some(Arc::new(service))
            }
            Err(error) => {
                tracing::warn!(%error, "translation disabled");
                None
            }
        }
    } else {
        // Say it out loud: with the relay off, every client keeps showing source names.
        tracing::info!(
            "translation relay disabled by config; event names stay in the source language"
        );
        None
    };

    let mut calendar_service = CalendarService::new(
        Arc::new(TradingViewProvider::new(
            client_for("tradingview"),
            &config.calendar.primary_url,
        )),
        Arc::new(ForexFactoryProvider::new(
            client_for("forexfactory"),
            &config.calendar.fallback_url,
        )),
        events.clone(),
    )
    .with_health(health.clone());
    if let Some(translation) = &translation_service {
        calendar_service = calendar_service.with_translation(translation.clone());
    }
    let calendar_service = Arc::new(calendar_service);

    let symbols = config
        .market
        .symbols
        .iter()
        .map(|symbol| MarketSymbol::from_str(symbol))
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| format!("invalid market symbol configuration: {error}"))?;
    let market_candidates = symbols.clone();
    let market_service = Arc::new(
        MarketService::new(
            Arc::new(YahooProvider::new(
                client_for("yahoo"),
                &config.market.yahoo_base_url,
            )),
            Arc::new(BinanceProvider::new(
                client_for("binance"),
                &config.market.binance_base_url,
            )),
            market.clone(),
            symbols,
        )
        .with_health(health.clone())
        .with_biquote(Arc::new(BiquoteProvider::new(
            client_for("biquote"),
            &config.market.biquote_base_url,
        )))
        .with_cnbc(Arc::new(CnbcProvider::new(
            client_for("cnbc"),
            &config.market.cnbc_quote_url,
            &config.market.cnbc_chart_url,
        )))
        .with_live_quote_refresh(
            Duration::from_secs(config.market.live_quote_refresh_seconds),
            Duration::from_secs(config.market.live_quote_stale_seconds),
        ),
    );

    let rules = RuleEngine::from_path(
        config_dir
            .join("rules.toml")
            .to_str()
            .ok_or("rules.toml path is not valid UTF-8")?,
    )?;
    let analysis_service = Arc::new(AnalysisService::new(
        events.clone(),
        market.clone(),
        analyses.clone(),
        rules,
    ));

    config.market_selection.validate()?;
    let market_selector = if config.market_selection.enabled {
        let api_key = if !config.typesafe.enabled {
            tracing::warn!(
                "market selection is enabled but typesafe.enabled is false; safe fallback will be used"
            );
            None
        } else {
            match config.typesafe.api_key() {
                Ok(key) => Some(key),
                Err(error) => {
                    tracing::warn!(%error, "market selection enabled without TypeSafe credentials; safe fallback will be used");
                    None
                }
            }
        };
        match MarketSelector::new(
            client_for("typesafe"),
            &config.typesafe,
            config.market_selection.clone(),
            market_candidates,
            events.clone(),
            llm_usage.clone(),
            Some(health.clone()),
            api_key,
        ) {
            Ok(selector) => Some(Arc::new(selector)),
            Err(error) => {
                tracing::warn!(%error, "Jev market selector unavailable; all-symbol fallback will be used");
                None
            }
        }
    } else {
        None
    };

    let ai_analysis_service = if config.ai.enabled {
        match AiAnalysisService::new(
            client_for("ai"),
            config.ai.clone(),
            pool.clone(),
            events.clone(),
            analyses.clone(),
            market.clone(),
        ) {
            Ok(service) => Some(Arc::new(
                service
                    .with_health(health.clone())
                    .with_regenerate_cooldown(config.limits.ai_regenerate_cooldown_seconds)
                    .with_audit_opt(llm_usage.clone()),
            )),
            Err(error) => {
                tracing::warn!(%error, "AI analysis disabled");
                None
            }
        }
    } else {
        None
    };

    let quota = Arc::new(QuotaService::new(pool.clone(), config.limits.clone()));
    let token_store = match TokenStore::from_config(&config.auth) {
        Ok(store) => Arc::new(store),
        Err(error) => {
            // Missing tokens leave the API open, so say it loudly on every boot.
            tracing::warn!(%error, "auth disabled; the API is public, set auth.tokens to lock it");
            Arc::new(
                TokenStore::from_config(&crate::config::AuthConfig {
                    enabled: false,
                    tokens: config.auth.tokens.clone(),
                })
                .expect("a disabled auth store always resolves"),
            )
        }
    };
    let auth = AuthState {
        store: token_store,
        quota: quota.clone(),
    };

    if let Some(range) = history_range {
        let request_delay = Duration::from_millis(config.backfill.request_delay_ms.max(250));
        let service = if repair_market {
            BackfillService::new_local(
                backfill,
                events,
                analyses,
                market_service,
                analysis_service,
                request_delay,
            )
        } else {
            let provider = Arc::new(TradingEconomicsApiProvider::new(
                client_for("tradingeconomics"),
                &config.backfill.calendar_api_base_url,
                history_key.unwrap(),
            )?);
            let mut service = BackfillService::new(
                backfill,
                provider,
                events,
                analyses,
                market_service,
                analysis_service,
                request_delay,
            );
            if let Some(translation) = translation_service {
                service = service.with_translation(translation);
            }
            service
        };
        let summary = if repair_market {
            service.repair_local(range).await?
        } else {
            service.run(range).await?
        };
        println!("{}", serde_json::to_string_pretty(&summary)?);
        if summary.status != "complete" {
            return Err("Historical import is partial; inspect /api/v1/history/backfill and analysis historical.coverage before using the data".into());
        }
        return Ok(());
    }

    let (event_bus, _) = broadcast::channel(256);
    let state = AppState {
        config: Arc::new(config.clone()),
        events: events.clone(),
        market: market.clone(),
        analyses: analyses.clone(),
        calendar_service: calendar_service.clone(),
        market_service: market_service.clone(),
        analysis_service,
        ai_analysis_service,
        health: health.clone(),
        llm_usage: llm_usage.clone(),
        auth,
        quota,
        event_bus,
        fcm: fcm.clone(),
        market_selector: market_selector.clone(),
        backfill,
    };

    if let Some(selector) = market_selector.clone() {
        let events = events.clone();
        tokio::spawn(async move {
            match events.market_selection_missing_active().await {
                Ok(active_events) => {
                    for event in active_events {
                        if let Err(error) = selector.selection_for(&event).await {
                            tracing::warn!(event_id = event.id, %error, "could not restore missing event market selection");
                        }
                    }
                }
                Err(error) => {
                    tracing::warn!(%error, "could not scan for missing event market selections")
                }
            }
        });
    }

    if config.translation.backfill_on_startup
        && let Some(translation) = translation_service
    {
        tokio::spawn(async move {
            match translation.backfill_existing().await {
                Ok(0) => {}
                Ok(count) => tracing::info!(count, "existing event names translated"),
                Err(error) => tracing::warn!(%error, "existing event-name translation failed"),
            }
        });
    }

    // Populate and maintain the in-process price/change cache independently of HTTP traffic.
    // Per-symbol loops are staggered by MarketService to avoid synchronized upstream bursts.
    market_service.clone().start_live_quote_refresh();
    tokio::spawn(market_event_analyzer::shared_ai::run(
        state.clone(),
        alerts.clone(),
    ));
    tokio::spawn(scheduler::startup_calendar_sync(calendar_service.clone()));
    tokio::spawn(scheduler::calendar_sync_loop(
        calendar_service,
        config.calendar.clone(),
        config.scheduler.calendar_sync_seconds,
    ));
    tokio::spawn(scheduler::event_watch_loop(
        state.clone(),
        config.calendar.clone(),
        config.scheduler.clone(),
    ));
    tokio::spawn(scheduler::market_collect_loop(
        state.clone(),
        config.scheduler.clone(),
    ));
    if alerts.enabled() {
        // Startup notice: tells the admin the service is live (and at which address the app
        // should point), and proves the bot token and chat id work. Sent as a task so a slow
        // Telegram round trip cannot delay the listener, and retried once because the outbound
        // proxy may still be coming up at boot.
        let notice = startup_notice(&config, llm_usage.as_ref()).await;
        let startup_alerts = alerts.clone();
        tokio::spawn(async move {
            for attempt in 1..=2 {
                if startup_alerts
                    .notify_unsuppressed(
                        Severity::Info,
                        "service.startup",
                        "healthy",
                        notice.clone(),
                    )
                    .await
                    .is_ok()
                {
                    break;
                }
                if attempt == 1 {
                    tokio::time::sleep(Duration::from_secs(10)).await;
                }
            }
        });
        tokio::spawn(scheduler::data_missing_loop(
            state.clone(),
            alerts.clone(),
            300,
            config.alerts.clone(),
        ));
        if let Some(telegram) = telegram.clone() {
            tokio::spawn(bot_loop(BotState {
                telegram,
                chat_ids: config.telegram.admin_chat_ids(),
                health,
                llm_usage,
                pool: pool.clone(),
                poll_timeout_seconds: config.telegram.poll_timeout_seconds,
            }));
        }
    } else {
        tracing::warn!("Telegram is disabled; source failures will only be logged");
    }

    let address = SocketAddr::new(IpAddr::from_str(&config.server.host)?, config.server.port);
    let router = api::router(state);
    if config.server.tls_enabled() {
        let cert = config.server.tls_cert.as_deref().unwrap_or_default().trim();
        let key = config.server.tls_key.as_deref().unwrap_or_default().trim();
        let tls = axum_server::tls_rustls::RustlsConfig::from_pem_file(cert, key).await?;
        let handle = axum_server::Handle::new();
        let shutdown_handle = handle.clone();
        tokio::spawn(async move {
            shutdown_signal().await;
            shutdown_handle.graceful_shutdown(Some(Duration::from_secs(10)));
        });
        tracing::info!(%address, "market event analyzer listening (https, direct TLS)");
        axum_server::bind_rustls(address, tls)
            .handle(handle)
            .serve(router.into_make_service())
            .await?;
        return Ok(());
    }
    tracing::info!(%address, "market event analyzer listening (http)");
    let listener = tokio::net::TcpListener::bind(address).await?;
    axum::serve(listener, router)
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    Ok(())
}

/// `--check-ai`: proves the configured relays answer, without a database or a real event.
///
/// Relay setups fail for reasons that never show up in the app (wrong model name, a key bound to
/// a different endpoint, a gateway that rejects `response_format`), so this prints what the
/// relay itself says.
async fn check_ai_relays(
    config: &AppConfig,
    client_for: &impl Fn(&str) -> reqwest::Client,
) -> Result<(), Box<dyn std::error::Error>> {
    println!("== 中转站自检 ==");
    println!(
        "出网代理: {}",
        if config.network.proxy_url.trim().is_empty() {
            "未配置（直连）".to_owned()
        } else {
            config.network.proxy_url.clone()
        }
    );

    if config.translation.enabled {
        println!(
            "\n[翻译] base_url={} model={}",
            config.translation.base_url, config.translation.model
        );
        match config.translation.api_key() {
            Ok(api_key) => {
                match OpenAiEventNameTranslator::with_headers(
                    client_for("translation"),
                    &config.translation.base_url,
                    config.translation.model.clone(),
                    api_key,
                    market_event_analyzer::openai_compat::ExtraHeaders::from_map(
                        &config.translation.extra_headers,
                    ),
                ) {
                    Ok(translator) => {
                        let sample = ["Nonfarm Payrolls".to_owned(), "Core CPI m/m".to_owned()];
                        match translator.translate(&sample, &[]).await {
                            Ok(rows) => {
                                for row in rows {
                                    println!("  ✅ {} → {} / {}", row.source, row.zh_cn, row.zh_tw);
                                }
                            }
                            Err(error) => println!("  ❌ {error}"),
                        }
                    }
                    Err(error) => println!("  ❌ {error}"),
                }
            }
            Err(error) => println!("  ❌ {error}"),
        }
    } else {
        println!("\n[翻译] 未启用 (translation.enabled = false)");
    }

    if config.ai.enabled {
        println!(
            "\n[AI 分析] base_url={} model={}",
            config.ai.base_url, config.ai.model
        );
        match ai_analysis::probe(&client_for("ai"), &config.ai).await {
            Ok(content) => println!("  ✅ 回复: {content}"),
            Err(error) => println!("  ❌ {error}"),
        }
    } else {
        println!("\n[AI 分析] 未启用 (ai.enabled = false)");
    }

    println!("\nbase_url 可填 https://relay/v1 或完整的 .../v1/chat/completions");
    println!("两个模块可以用不同的中转站与不同的 Key（都写在配置文件的 api_key 里）。");
    Ok(())
}

async fn startup_notice(config: &AppConfig, llm_usage: Option<&Arc<LlmUsageRepository>>) -> String {
    let scheme = if config.server.tls_enabled() {
        "https"
    } else {
        "http"
    };
    let mut lines = vec![
        format!(
            "服务已启动，监听 {}:{}（{}）",
            config.server.host, config.server.port, scheme
        ),
        format!("数据库 {}", config.database.url),
    ];
    lines.push(format!(
        "鉴权：{} · 翻译：{} · AI 分析：{}",
        if config.auth.enabled {
            "已启用"
        } else {
            "已关闭"
        },
        if config.translation.enabled {
            format!("已启用（{}）", config.translation.model)
        } else {
            "已关闭".to_owned()
        },
        if config.ai.enabled {
            format!("已启用（{}）", config.ai.model)
        } else {
            "已关闭".to_owned()
        },
    ));
    if !config.network.proxy_url.trim().is_empty() {
        lines.push(format!(
            "出网代理 {}（{}）",
            config.network.proxy_url.trim(),
            config.network.proxied.join(", ")
        ));
    }
    if let Some(audit) = llm_usage
        && let Ok(Some(recorded)) = audit.last_recorded_at().await
    {
        lines.push(format!(
            "最近一次模型调用 {}",
            recorded.format("%Y-%m-%d %H:%M UTC")
        ));
    }
    lines.join("\n")
}

fn ensure_database_directory(url: &str) -> std::io::Result<()> {
    let path = url
        .strip_prefix("sqlite://")
        .or_else(|| url.strip_prefix("sqlite:"))
        .unwrap_or(url);
    let path = path.split('?').next().unwrap_or(path);
    if path == ":memory:" {
        return Ok(());
    }
    if let Some(parent) = Path::new(path)
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        std::fs::create_dir_all(parent)?;
    }
    Ok(())
}

async fn shutdown_signal() {
    if let Err(error) = tokio::signal::ctrl_c().await {
        tracing::error!(%error, "failed to install shutdown signal handler");
    }
}
