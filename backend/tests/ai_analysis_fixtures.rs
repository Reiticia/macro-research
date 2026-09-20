use std::fs;

use market_event_analyzer::ai_analysis::parse::parse_draft;
use serde_json::Value;

/// The model-reply parser exists twice: on the device (direct mode) and on the server
/// (backend mode). Both suites read this one corpus so the two implementations cannot drift.
const FIXTURES: &str = "../android/app/src/test/resources/ai-analysis";

#[test]
fn both_parsers_share_one_corpus() {
    let manifest = fs::read_to_string(format!("{FIXTURES}/manifest.json"))
        .expect("shared AI-analysis fixtures are present");
    let cases: Vec<Value> = serde_json::from_str(&manifest).unwrap();
    assert!(!cases.is_empty(), "the fixture manifest must not be empty");

    for case in cases {
        let name = case["name"].as_str().unwrap();
        let file = case["file"].as_str().unwrap();
        let response = fs::read_to_string(format!("{FIXTURES}/{file}"))
            .unwrap_or_else(|error| panic!("fixture {file} is readable: {error}"));
        let expected_ok = case["ok"].as_bool().unwrap();
        let parsed = parse_draft(&response);
        assert_eq!(
            parsed.is_ok(),
            expected_ok,
            "fixture {name}: unexpected outcome {parsed:?}"
        );
        let Ok(draft) = parsed else { continue };
        if let Some(expected) = case.get("dataAnalysis").and_then(Value::as_str) {
            assert_eq!(
                draft.data_analysis, expected,
                "fixture {name}: dataAnalysis"
            );
        }
        if let Some(expected) = case.get("chainLength").and_then(Value::as_u64) {
            assert_eq!(
                draft.chain.len() as u64,
                expected,
                "fixture {name}: chain length"
            );
        }
    }
}

#[test]
fn snake_case_fields_are_normalized_the_same_way_as_the_device_parser() {
    let response = fs::read_to_string(format!("{FIXTURES}/snake-case.txt")).unwrap();
    let draft = parse_draft(&response).unwrap();
    assert_eq!(draft.data_analysis, "d");
    assert_eq!(draft.market_outlook, "o");
    assert_eq!(draft.risks.as_deref(), Some("k"));
    assert_eq!(draft.chain[0].direction, "up");
    assert_eq!(draft.chain[0].verdict.as_deref(), Some("confirmed"));
}
