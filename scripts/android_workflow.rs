use serde_json::{json, Value};

const MAX_EVENTS: usize = 300;
const MAX_EVIDENCE: usize = 500;

fn phase(state: &Value) -> &str {
    state.get("phase").and_then(Value::as_str).unwrap_or("IDLE")
}

fn allowed(current: &str, next: &str) -> bool {
    match current {
        "IDLE" | "SOURCE_READY" | "READY" | "FAILED" | "PAUSED" =>
            next == "STUDY",
        "STUDY" => matches!(next, "BUILD" | "FAILED" | "PAUSED"),
        "BUILD" => matches!(next, "CHECK" | "FAILED" | "PAUSED"),
        "CHECK" => matches!(next, "REFINE" | "SOURCE_READY" | "READY" | "FAILED" | "PAUSED"),
        "REFINE" => matches!(next, "CHECK" | "FAILED" | "PAUSED"),
        _ => false,
    }
}

fn push_event(state: &mut Value, kind: &str, message: &str) {
    let events = state["events"].as_array_mut().expect("events array initialized");
    events.push(json!({
        "sequence": events.len() + 1,
        "type": kind,
        "message": message.chars().take(2000).collect::<String>(),
        "at": std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default().as_millis()
    }));
    if events.len() > MAX_EVENTS {
        let excess = events.len() - MAX_EVENTS;
        events.drain(0..excess);
    }
}

pub fn transition(mut state: Value, next: &str, status: &str, detail: &str) -> Result<Value, String> {
    let current = phase(&state).to_owned();
    if !allowed(&current, next) {
        return Err(format!("Invalid workflow transition: {current} -> {next}"));
    }
    state["phase"] = json!(next);
    state["status"] = json!(status.chars().take(1000).collect::<String>());
    push_event(&mut state, "PHASE_CHANGED", if detail.is_empty() { status } else { detail });
    Ok(state)
}

pub fn add_evidence(mut state: Value, kind: &str, source: &str, summary: &str, confidence: f64) -> Value {
    if !state["evidence"].is_array() { state["evidence"] = json!([]); }
    let evidence = state["evidence"].as_array_mut().expect("evidence array initialized");
    if evidence.len() < MAX_EVIDENCE {
        evidence.push(json!({
            "kind": kind.chars().take(100).collect::<String>(),
            "source": source.chars().take(500).collect::<String>(),
            "summary": summary.chars().take(12000).collect::<String>(),
            "confidence": confidence.clamp(0.0, 1.0)
        }));
    }
    state
}

pub fn record_check(mut state: Value, id: &str, passed: Option<bool>, score: Option<f64>, summary: &str, executed: bool) -> Value {
    if !state["checks"].is_array() { state["checks"] = json!([]); }
    state["checks"].as_array_mut().expect("checks array initialized").push(json!({
        "id": id.chars().take(100).collect::<String>(),
        "passed": passed,
        "score": score.filter(|v| v.is_finite()).map(|v| v.clamp(0.0, 100.0)),
        "summary": summary.chars().take(4000).collect::<String>(),
        "executed": executed
    }));
    push_event(&mut state, "CHECK_RECORDED", summary);
    state
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn state(phase: &str) -> Value {
        json!({"phase":phase,"events":[],"evidence":[],"checks":[]})
    }

    #[test]
    fn permits_only_valid_lifecycle_transitions() {
        let built = transition(state("STUDY"), "BUILD", "빌드 중", "").unwrap();
        let checked = transition(built, "CHECK", "검사 중", "").unwrap();
        let ready = transition(checked, "SOURCE_READY", "소스 준비", "").unwrap();
        assert_eq!(phase(&ready), "SOURCE_READY");
        assert!(transition(state("STUDY"), "READY", "완료", "").is_err());
    }

    #[test]
    fn records_checks_without_fabricating_execution() {
        let checked = record_check(state("CHECK"), "source", None, None, "정적 검사만 수행", false);
        assert_eq!(checked["checks"][0]["executed"], false);
        assert!(checked["checks"][0]["passed"].is_null());
    }

    #[test]
    fn bounds_evidence_and_event_text() {
        let state = add_evidence(state("STUDY"), "x", "y", &"a".repeat(13000), 2.0);
        assert_eq!(state["evidence"][0]["summary"].as_str().unwrap().len(), 12000);
        assert_eq!(state["evidence"][0]["confidence"], 1.0);
    }
}
