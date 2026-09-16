use serde_json::Value;

use crate::ai_analysis::TransmissionStep;

/// Parsed model output before it is persisted.
#[derive(Clone, Debug)]
pub struct Draft {
    pub chain: Vec<TransmissionStep>,
    pub data_analysis: String,
    pub market_outlook: String,
    pub risks: Option<String>,
}

/// Recovers the JSON a model was asked to produce.
///
/// OpenAI-compatible endpoints wrap the answer in markdown fences, prepend reasoning prose,
/// embed the example schema from the prompt, or cut the answer off at the token limit.
/// Slicing "first brace to last brace" then yields malformed JSON, so candidates are scanned
/// with string/escape awareness and each candidate is parsed individually.
pub fn values(raw: &str) -> Vec<Value> {
    let text = strip_fences(raw);
    let mut parsed: Vec<Value> = Vec::new();
    if let Some(value) = parse(&text) {
        parsed.push(value);
    }
    for slice in slices(&text) {
        if slice != text
            && let Some(value) = parse(&slice)
        {
            parsed.push(value);
        }
    }
    let mut unique: Vec<Value> = Vec::new();
    for value in parsed {
        if !unique.iter().any(|existing| existing == &value) {
            unique.push(value);
        }
    }
    unique
}

/// Text payloads carried by an OpenAI chat envelope, falling back to the value itself.
pub fn contents(root: &Value) -> Vec<String> {
    if let Some(choices) = root.get("choices").and_then(Value::as_array) {
        let mut texts = Vec::new();
        if let Some(choice) = choices.first() {
            let message = choice.get("message");
            let candidates = [
                message.and_then(|value| value.get("content")),
                message.and_then(|value| value.get("reasoning_content")),
                choice.get("text"),
            ];
            for candidate in candidates.into_iter().flatten() {
                match candidate {
                    Value::String(text) if !text.trim().is_empty() => texts.push(text.clone()),
                    Value::Object(_) | Value::Array(_) => texts.push(candidate.to_string()),
                    _ => {}
                }
            }
        }
        return texts;
    }
    match root {
        Value::String(text) => vec![text.clone()],
        other => vec![other.to_string()],
    }
}

/// Every JSON object that appears inside `text`, including the text itself when it is one.
pub fn objects(text: &str) -> Vec<Value> {
    values(text)
        .into_iter()
        .flat_map(|element| match element {
            Value::Object(_) => vec![element],
            Value::Array(rows) => rows.into_iter().filter(|row| row.is_object()).collect(),
            _ => Vec::new(),
        })
        .collect()
}

/// Parses the model reply into a draft, rejecting echoes of the prompt's example schema.
pub fn parse_draft(response_text: &str) -> Result<Draft, String> {
    let payload = briefing_candidates(response_text)
        .into_iter()
        .filter_map(|candidate| usable_briefing(&candidate).then_some(candidate))
        .next_back()
        .ok_or_else(|| {
            "AI did not return a usable analysis JSON; the reply may have been truncated or missing the JSON object"
                .to_owned()
        })?;
    let chain = parse_chain(first_array(
        &payload,
        &[
            "chain",
            "transmissionChain",
            "transmission_chain",
            "steps",
            "links",
        ],
    ));
    let data_analysis = first_string(
        &payload,
        &[
            "dataAnalysis",
            "data_analysis",
            "dataRead",
            "data_read",
            "analysis",
        ],
    )
    .unwrap_or_default()
    .trim()
    .to_owned();
    let market_outlook = first_string(
        &payload,
        &[
            "marketOutlook",
            "market_outlook",
            "marketView",
            "market_view",
            "outlook",
        ],
    )
    .unwrap_or_default()
    .trim()
    .to_owned();
    let risks = first_string(&payload, &["risks", "risk", "caveats"])
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty());
    if data_analysis.is_empty() && market_outlook.is_empty() {
        return Err("AI returned no usable analysis".to_owned());
    }
    Ok(Draft {
        chain,
        data_analysis,
        market_outlook,
        risks,
    })
}

/// Every object in the response that could be the briefing: the payload itself, or the
/// contents of an OpenAI envelope.
fn briefing_candidates(response_text: &str) -> Vec<Value> {
    let mut candidates: Vec<Value> = Vec::new();
    for root in values(response_text) {
        let direct: Vec<Value> = match &root {
            Value::Object(_) => vec![root.clone()],
            Value::Array(rows) => rows.iter().filter(|row| row.is_object()).cloned().collect(),
            _ => Vec::new(),
        };
        let from_contents: Vec<Value> = contents(&root)
            .iter()
            .flat_map(|text| objects(text))
            .collect();
        for candidate in direct.into_iter().chain(from_contents) {
            if !candidates.contains(&candidate) {
                candidates.push(candidate);
            }
        }
    }
    candidates
}

/// Rejects an echo of the prompt schema, whose fields hold placeholders instead of a result.
fn usable_briefing(payload: &Value) -> bool {
    let text = first_string(
        payload,
        &[
            "dataAnalysis",
            "data_analysis",
            "dataRead",
            "data_read",
            "analysis",
            "marketOutlook",
            "market_outlook",
            "outlook",
        ],
    )
    .unwrap_or_default()
    .trim()
    .to_owned();
    if text.is_empty() {
        return false;
    }
    !PLACEHOLDER_TOKENS.iter().any(|token| text.contains(token))
}

fn parse_chain(rows: Option<&Vec<Value>>) -> Vec<TransmissionStep> {
    rows.into_iter()
        .flatten()
        .filter_map(|row| {
            let from = first_string(row, &["from", "fromNode", "from_node", "source", "cause"])
                .unwrap_or_default()
                .trim()
                .to_owned();
            let to = first_string(row, &["to", "toNode", "to_node", "target", "effect"])
                .unwrap_or_default()
                .trim()
                .to_owned();
            if from.is_empty() || to.is_empty() {
                return None;
            }
            Some(TransmissionStep {
                from,
                to,
                direction: normalize_direction(
                    first_string(row, &["direction", "dir", "sign"]).as_deref(),
                ),
                rationale: first_string(row, &["rationale", "reason", "why", "explanation"])
                    .unwrap_or_default()
                    .trim()
                    .to_owned(),
                verdict: normalize_verdict(
                    first_string(row, &["verdict", "status", "result", "validation"]).as_deref(),
                ),
            })
        })
        .collect()
}

fn normalize_verdict(raw: Option<&str>) -> Option<String> {
    let value = raw?.trim().to_lowercase();
    match value.as_str() {
        "" => None,
        "confirmed" | "confirm" | "validated" | "holds" | "held" | "in line" | "✓" => {
            Some("confirmed".to_owned())
        }
        "contradicted" | "contradiction" | "invalidated" | "refuted" | "failed" | "✗" => {
            Some("contradicted".to_owned())
        }
        "unobserved" | "unknown" | "unclear" | "n/a" | "na" | "not observed" | "no data" => {
            Some("unobserved".to_owned())
        }
        _ => None,
    }
}

fn normalize_direction(raw: Option<&str>) -> String {
    let value = raw.unwrap_or_default().trim().to_lowercase();
    if value.is_empty() {
        return "flat".to_owned();
    }
    if value.starts_with("up")
        || matches!(
            value.as_str(),
            "higher" | "rise" | "rising" | "increase" | "bullish" | "↑" | "+"
        )
    {
        "up".to_owned()
    } else if value.starts_with("down")
        || matches!(
            value.as_str(),
            "lower" | "fall" | "falling" | "decrease" | "bearish" | "↓" | "-"
        )
    {
        "down".to_owned()
    } else {
        "flat".to_owned()
    }
}

fn first_string(value: &Value, names: &[&str]) -> Option<String> {
    names.iter().find_map(|name| {
        value.get(*name).and_then(|field| match field {
            Value::String(text) => Some(text.clone()),
            Value::Number(number) => Some(number.to_string()),
            Value::Bool(flag) => Some(flag.to_string()),
            _ => None,
        })
    })
}

fn first_array<'a>(value: &'a Value, names: &[&str]) -> Option<&'a Vec<Value>> {
    names
        .iter()
        .find_map(|name| value.get(*name).and_then(Value::as_array))
}

fn strip_fences(raw: &str) -> String {
    crate::openai_compat::strip_fences(raw)
}

/// Balanced `{...}` / `[...]` spans. Truncated tails are dropped instead of mis-sliced.
fn slices(text: &str) -> Vec<String> {
    let bytes = text.as_bytes();
    let mut found = Vec::new();
    let mut index = 0;
    while index < bytes.len() {
        let character = bytes[index];
        if (character == b'{' || character == b'[')
            && let Some(end) = matching_end(text, index)
        {
            found.push(text[index..=end].to_owned());
            index = end + 1;
            continue;
        }
        index += 1;
    }
    found
}

fn matching_end(text: &str, start: usize) -> Option<usize> {
    let bytes = text.as_bytes();
    let open = bytes[start];
    let close = if open == b'{' { b'}' } else { b']' };
    let mut depth: i32 = 0;
    let mut in_string = false;
    let mut escaped = false;
    for (offset, character) in bytes[start..].iter().enumerate() {
        if in_string {
            if escaped {
                escaped = false;
            } else if *character == b'\\' {
                escaped = true;
            } else if *character == b'"' {
                in_string = false;
            }
            continue;
        }
        if *character == b'"' {
            in_string = true;
        } else if *character == open {
            depth += 1;
        } else if *character == close {
            depth -= 1;
            if depth == 0 {
                return Some(start + offset);
            }
        }
    }
    None
}

/// `serde_json` rejects raw control characters inside strings, but models emit literal
/// newlines. Escaping only those characters keeps valid escapes untouched.
fn sanitize_control_characters(text: &str) -> String {
    let mut output = String::with_capacity(text.len());
    let mut in_string = false;
    let mut escaped = false;
    for character in text.chars() {
        if in_string {
            if escaped {
                escaped = false;
                output.push(character);
                continue;
            }
            match character {
                '\\' => {
                    escaped = true;
                    output.push(character);
                }
                '"' => {
                    in_string = false;
                    output.push(character);
                }
                '\n' => output.push_str("\\n"),
                '\r' => output.push_str("\\r"),
                '\t' => output.push_str("\\t"),
                other if (other as u32) < 0x20 => {
                    output.push_str(&format!("\\u{:04x}", other as u32));
                }
                other => output.push(other),
            }
            continue;
        }
        if character == '"' {
            in_string = true;
        }
        output.push(character);
    }
    output
}

fn parse(json: &str) -> Option<Value> {
    let sanitized = sanitize_control_characters(json);
    serde_json::from_str::<Value>(&sanitized)
        .ok()
        .filter(|value| value.is_object() || value.is_array())
}

/// Fragments of the schema echo; a real briefing never contains them.
const PLACEHOLDER_TOKENS: &[&str] = &["up|down|flat", "...", "short node names", "2-4 sentences"];

#[cfg(test)]
mod tests {
    use super::*;

    const BRIEFING: &str = r#"{"chain":[{"from":"核心CPI超预期","to":"美债收益率","direction":"up","rationale":"收益率上行"}],"dataAnalysis":"核心CPI月率0.3%高于预期0.2%，读数偏鹰。","marketOutlook":"美元与短端收益率短期偏强。","risks":"若后续数据走弱，该判断失效。"}"#;

    #[test]
    fn accepts_plain_fenced_prose_and_multiline_payloads() {
        assert!(values(BRIEFING).first().unwrap().is_object());
        assert!(
            values(&format!("```json\n{BRIEFING}\n```"))
                .first()
                .unwrap()
                .is_object()
        );
        assert!(
            values(&format!(
                "Here is the briefing:\n{BRIEFING}\nHope this helps."
            ))
            .first()
            .unwrap()
            .is_object()
        );
        let multiline =
            "{\"dataAnalysis\":\"first line\nsecond line\",\"marketOutlook\":\"ok for now\"}";
        let parsed = values(multiline).first().unwrap().clone();
        assert_eq!(parsed["dataAnalysis"], "first line\nsecond line");
        assert!(values("{\"chain\":[{\"from\":\"a\"").is_empty());
        assert!(values("no json here at all").is_empty());
    }

    #[test]
    fn reasoning_echo_is_rejected_in_favour_of_the_real_answer() {
        let response = format!(
            "I need to write the JSON only. Structure:\n{}\nFinal answer:\n{}",
            r#"{"chain":[{"from":"...","to":"...","direction":"up|down|flat","rationale":"..."}],"dataAnalysis":"...","marketOutlook":"...","risks":"..."}"#,
            BRIEFING
        );
        let draft = parse_draft(&response).unwrap();
        assert_eq!(
            draft.data_analysis,
            "核心CPI月率0.3%高于预期0.2%，读数偏鹰。"
        );
        assert_eq!(draft.chain.len(), 1);
    }

    #[test]
    fn reasoning_only_replies_fail_with_an_actionable_message() {
        let truncated = r#"The chain should be ordered from surprise to final asset reaction.
{"chain":[{"from":"...","to":"...","direction":"up|down|flat","rationale":"..."}],"dataAnalysis":"...","marketOutlook":"...","risks":"..."}
Let me design the chain:
1. {"from":"核心CPI超预期(0.3% vs 0.2%)","to":"美联储降息预期","direction":"down","rationale":"市场下修降"#;
        let error = parse_draft(truncated).unwrap_err();
        assert!(error.contains("usable analysis JSON"), "{error}");
    }

    #[test]
    fn envelope_and_bare_payload_agree() {
        let envelope = serde_json::json!({
            "choices": [{ "message": { "content": format!("```json\n{BRIEFING}\n```") } }]
        })
        .to_string();
        assert_eq!(
            parse_draft(&envelope).unwrap().data_analysis,
            parse_draft(BRIEFING).unwrap().data_analysis
        );
    }
}
