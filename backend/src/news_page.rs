//! Credential-free, publisher-allowlisted static text extraction; never executes JS or fetches assets.
use reqwest::{
    Client, Url,
    dns::{Addrs, Name, Resolve, Resolving},
};
use scraper::{Html, Selector};
use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::Duration,
};

const MAX_BYTES: usize = 512 * 1024;
pub const MAX_BODY_CHARS: usize = 64_000;
pub struct PageText {
    pub url: String,
    pub text: String,
    pub truncated: bool,
}
pub struct PageReader {
    client: Client,
}

pub fn is_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => {
            let [a, b, c, _] = ip.octets();
            !(a == 0
                || a == 10
                || a == 127
                || a >= 224
                || a == 100 && (64..=127).contains(&b)
                || a == 169 && b == 254
                || a == 172 && (16..=31).contains(&b)
                || a == 192 && (b == 168 || b == 0 && c == 0 || b == 0 && c == 2)
                || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                || a == 203 && b == 0 && c == 113
                || a == 192 && b == 88 && c == 99)
        }
        IpAddr::V6(ip) => {
            let b = ip.octets();
            (b[0] & 0xe0) == 0x20
                && !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8))
                && !(b[0] == 0x20 && b[1] == 0x02)
                && !(b[0] == 0x3f && b[1] == 0xff && b[2] & 0xf0 == 0)
        }
    }
}
fn publisher_url(raw: &str) -> Option<Url> {
    let safe = crate::news::safe_article_url(raw)?;
    let url = Url::parse(&safe).ok()?;
    let host = url.host_str()?.trim_end_matches('.');
    [
        "bbc.com",
        "bbc.co.uk",
        "marketwatch.com",
        "federalreserve.gov",
        "bls.gov",
        "bea.gov",
        "chinanews.com.cn",
        "chinanews.com",
    ]
    .iter()
    .any(|d| host == *d || host.ends_with(&format!(".{d}")))
    .then_some(url)
}
async fn addresses(
    host: &str,
    allow_private_proxy: bool,
) -> Result<Vec<SocketAddr>, std::io::Error> {
    let addresses: Vec<_> = tokio::net::lookup_host((host, 443)).await?.collect();
    if addresses.is_empty()
        || !allow_private_proxy && addresses.iter().any(|a| !is_public_ip(a.ip()))
    {
        return Err(std::io::Error::other("Non-public publisher DNS"));
    }
    Ok(addresses)
}
struct PublicResolver {
    proxy_host: Option<String>,
}
impl Resolve for PublicResolver {
    fn resolve(&self, name: Name) -> Resolving {
        let host = name.as_str().to_owned();
        let trusted_proxy = self.proxy_host.as_deref() == Some(host.as_str());
        Box::pin(async move {
            Ok(Box::new(addresses(&host, trusted_proxy).await?.into_iter()) as Addrs)
        })
    }
}
impl PageReader {
    pub fn new(proxy: Option<&str>) -> Result<Self, crate::error::AppError> {
        let mut builder = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .user_agent("MacroResearch/1.0 (public news text archive)")
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(12))
            .dns_resolver(Arc::new(PublicResolver {
                proxy_host: proxy
                    .and_then(|p| Url::parse(p).ok())
                    .and_then(|p| p.host_str().map(str::to_owned)),
            }));
        if let Some(proxy) = proxy {
            builder = builder.proxy(reqwest::Proxy::all(proxy)?);
        }
        Ok(Self {
            client: builder.build()?,
        })
    }
    pub async fn read(&self, url: &str) -> Result<PageText, &'static str> {
        tokio::time::timeout(Duration::from_secs(20), self.load(url))
            .await
            .map_err(|_| "timeout")?
    }
    async fn load(&self, url: &str) -> Result<PageText, &'static str> {
        let mut url = publisher_url(url).ok_or("publisher_not_allowed")?;
        let mut seen = std::collections::HashSet::new();
        for hop in 0..5 {
            if !seen.insert(url.to_string()) {
                return Err("redirect_loop");
            }
            // Applies to proxy routes too; the direct resolver rechecks actual connection DNS.
            addresses(url.host_str().ok_or("invalid_url")?, false)
                .await
                .map_err(|_| "publisher_dns")?;
            let mut response = self
                .client
                .get(url.clone())
                .header("Accept", "text/html, application/xhtml+xml")
                .send()
                .await
                .map_err(|_| "transport")?;
            if response.status().is_redirection() {
                if hop == 4 {
                    return Err("redirect_limit");
                }
                url = response
                    .headers()
                    .get("location")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| url.join(v).ok())
                    .and_then(|v| publisher_url(v.as_str()))
                    .ok_or("unsafe_redirect")?;
                continue;
            }
            if !response.status().is_success() {
                return Err("http_unavailable");
            }
            let mime = response
                .headers()
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .unwrap_or("")
                .split(';')
                .next()
                .unwrap_or("")
                .trim();
            if !matches!(mime, "text/html" | "application/xhtml+xml") {
                return Err("not_html");
            }
            if response
                .content_length()
                .is_some_and(|n| n > MAX_BYTES as u64)
            {
                return Err("too_large");
            }
            let mut raw = Vec::new();
            while let Some(chunk) = response.chunk().await.map_err(|_| "transport")? {
                if raw.len() + chunk.len() > MAX_BYTES {
                    return Err("too_large");
                }
                raw.extend_from_slice(&chunk);
            }
            let html = String::from_utf8(raw).map_err(|_| "encoding_unavailable")?;
            let (text, truncated) = tokio::task::spawn_blocking(move || extract(&html))
                .await
                .map_err(|_| "text_extraction_failed")??;
            return Ok(PageText {
                url: url.to_string(),
                text,
                truncated,
            });
        }
        Err("redirect_limit")
    }
}
fn selector(value: &str) -> Selector {
    Selector::parse(value).expect("static news selector")
}
fn clean(node: scraper::ElementRef<'_>) -> String {
    node.descendants()
        .filter_map(|n| {
            let text = n.value().as_text()?;
            (!n.ancestors()
                .filter_map(scraper::ElementRef::wrap)
                .any(|e| {
                    matches!(
                        e.value().name(),
                        "script"
                            | "style"
                            | "noscript"
                            | "iframe"
                            | "nav"
                            | "header"
                            | "footer"
                            | "aside"
                            | "form"
                            | "svg"
                            | "template"
                    )
                }))
            .then_some(text.text.as_ref())
        })
        .collect::<Vec<_>>()
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}
pub fn extract(html: &str) -> Result<(String, bool), &'static str> {
    let document = Html::parse_document(html);
    if document.tree.nodes().take(30_001).count() > 30_000
        || document
            .tree
            .nodes()
            .any(|n| n.ancestors().take(65).count() >= 65)
    {
        return Err("html_too_complex");
    }
    let title = document
        .select(&selector("title"))
        .next()
        .map(clean)
        .unwrap_or_default()
        .to_lowercase();
    if [
        "access denied",
        "just a moment",
        "robot check",
        "verify you are human",
        "sign in to continue",
        "subscription required",
    ]
    .iter()
    .any(|s| title.starts_with(s))
    {
        return Err("access_restricted");
    }
    // Fail closed on known subscription/interstitial structures; never remove a paywall to reveal text.
    if document
        .select(&selector(
            "[data-testid=paywall],.paywall,.paywall-container,#paywall,form[action*=login]",
        ))
        .next()
        .is_some()
    {
        return Err("access_restricted");
    }
    let bbc_blocks = document
        .select(&selector("[data-component=text-block]"))
        .map(clean)
        .filter(|v| !v.is_empty())
        .collect::<Vec<_>>();
    let text = if !bbc_blocks.is_empty() {
        bbc_blocks.join("\n\n")
    } else {
        let container = [".left_zw", "#article", "article", "main"]
            .iter()
            .find_map(|s| document.select(&selector(s)).next())
            .ok_or("no_article_text")?;
        let parts = container
            .select(&selector("h1,h2,h3,p,li,blockquote"))
            .filter(|node| {
                !node
                    .ancestors()
                    .take_while(|a| a.id() != container.id())
                    .filter_map(scraper::ElementRef::wrap)
                    .any(|p| matches!(p.value().name(), "p" | "li" | "blockquote"))
            })
            .map(clean)
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>();
        if parts.is_empty() {
            clean(container)
        } else {
            parts.join("\n\n")
        }
    };
    if text.chars().count() < 80 {
        return Err("no_article_text");
    }
    let truncated = text.chars().count() > MAX_BODY_CHARS;
    Ok((text.chars().take(MAX_BODY_CHARS).collect(), truncated))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn extraction_is_plain_bounded_and_does_not_use_navigation_or_denial_pages() {
        let (text,truncated)=extract(&format!("<article><nav>BAD NAV</nav><p>{}</p><script>EVIL</script><p>Second paragraph.</p></article>","Policy facts. ".repeat(30))).unwrap();
        assert!(!truncated);
        assert!(text.contains("\n\n"));
        assert!(!text.contains("EVIL"));
        assert!(!text.contains("BAD NAV"));
        let (nested, _) = extract(&format!(
            "<article><ul><li><p>{}</p></li></ul></article>",
            "Unique paragraph. ".repeat(10)
        ))
        .unwrap();
        assert_eq!(nested.matches("Unique paragraph.").count(), 10);
        assert!(extract("<html><body>Navigation only</body></html>").is_err());
        assert!(
            extract(&format!(
                "{}<article><p>{}</p></article>{}",
                "<div>".repeat(70),
                "Deep text. ".repeat(20),
                "</div>".repeat(70)
            ))
            .is_err()
        );
        assert!(
            extract(&format!(
                "<title>Access Denied</title><article>{}</article>",
                "denied ".repeat(100)
            ))
            .is_err()
        );
        assert!(
            extract(&format!(
                "<div class=paywall>Subscribe</div><article>{}</article>",
                "text ".repeat(100)
            ))
            .is_err()
        );
        let (text, truncated) = extract(&format!(
            "<article><p>{}</p></article>",
            "界".repeat(70_000)
        ))
        .unwrap();
        assert_eq!(text.chars().count(), MAX_BODY_CHARS);
        assert!(truncated);
    }
    #[test]
    fn publisher_and_public_address_policies_reject_internal_and_off_site_targets() {
        for url in [
            "http://www.bbc.com/a",
            "https://user:pass@www.bbc.com/a",
            "https://www.bbc.com:8443/a",
            "https://evilbbc.com/a",
            "https://www.bbc.com.evil.test/a",
            "https://127.0.0.1/a",
            "https://localhost/a",
        ] {
            assert!(publisher_url(url).is_none());
        }
        assert!(publisher_url("https://www.bbc.com/news/articles/a").is_some());
        for ip in [
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "192.168.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "::1",
            "fc00::1",
            "2001:db8::1",
            "2002::1",
            "3fff::1",
            "::ffff:127.0.0.1",
        ] {
            assert!(!is_public_ip(ip.parse().unwrap()), "{ip}");
        }
        for ip in ["8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"] {
            assert!(is_public_ip(ip.parse().unwrap()));
        }
    }
}
