use serde_json::{json, Value};

const MAX_EVENTS: usize = 300;
const MAX_EVIDENCE: usize = 500;
const MAX_CHECKS: usize = 300;

fn phase(state: &Value) -> &str {
    state.get("phase").and_then(Value::as_str).unwrap_or("IDLE")
}

fn allowed(current: &str, next: &str) -> bool {
    match current {
        "IDLE" | "FAILED" | "PAUSED" => next == "STUDY",
        "SOURCE_READY" | "READY" => matches!(next, "STUDY" | "REFINE"),
        "STUDY" => matches!(next, "BUILD" | "FAILED" | "PAUSED"),
        "BUILD" => matches!(next, "CHECK" | "FAILED" | "PAUSED"),
        "CHECK" => matches!(next, "REFINE" | "SOURCE_READY" | "READY" | "FAILED" | "PAUSED"),
        "REFINE" => matches!(next, "CHECK" | "FAILED" | "PAUSED"),
        _ => false,
    }
}

fn push_event(state: &mut Value, kind: &str, message: &str) {
    // Preserve monotonic sequence numbers after old events are trimmed.
    let previous = state.get("eventSequence").and_then(Value::as_u64)
        .or_else(|| state["events"].as_array().and_then(|items| {
            items.iter().filter_map(|item| item["sequence"].as_u64()).max()
        })).unwrap_or(0);
    let sequence = previous.saturating_add(1);
    state["eventSequence"] = json!(sequence);
    if !state["events"].is_array() { state["events"] = json!([]); }
    let events = state["events"].as_array_mut().expect("events array initialized");
    events.push(json!({
        "sequence": sequence,
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
    let checks = state["checks"].as_array_mut().expect("checks array initialized");
    checks.push(json!({
        "id": id.chars().take(100).collect::<String>(),
        "passed": passed,
        "score": score.filter(|v| v.is_finite()).map(|v| v.clamp(0.0, 100.0)),
        "summary": summary.chars().take(4000).collect::<String>(),
        "executed": executed
    }));
    if checks.len() > MAX_CHECKS {
        let excess = checks.len() - MAX_CHECKS;
        checks.drain(0..excess);
    }
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

    #[test]
    fn event_sequences_remain_monotonic_after_history_is_trimmed() {
        let mut value = state("STUDY");
        for index in 0..(MAX_EVENTS + 7) {
            push_event(&mut value, "TEST", &index.to_string());
        }
        assert_eq!(value["events"].as_array().unwrap().len(), MAX_EVENTS);
        assert_eq!(value["events"][0]["sequence"], 8);
        assert_eq!(value["events"][MAX_EVENTS - 1]["sequence"], MAX_EVENTS as u64 + 7);
        assert_eq!(value["eventSequence"], MAX_EVENTS as u64 + 7);
    }

    #[test]
    fn check_history_is_bounded_and_keeps_the_latest_result() {
        let mut value = state("CHECK");
        for index in 0..(MAX_CHECKS + 5) {
            value = record_check(value, &index.to_string(), None, None, "not executed", false);
        }
        assert_eq!(value["checks"].as_array().unwrap().len(), MAX_CHECKS);
        assert_eq!(value["checks"][0]["id"], "5");
        assert_eq!(value["checks"][MAX_CHECKS - 1]["id"], (MAX_CHECKS + 4).to_string());
    }
}

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jdouble, jint, jstring, JNI_FALSE};
use jni::JNIEnv;

fn java_string(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_craftanything_NativeBridge_nativeWorkflowTransition(
    mut env: JNIEnv,
    _class: JClass,
    jstate: JString,
    jnext: JString,
    jstatus: JString,
    jdetail: JString,
) -> jstring {
    let read = |env: &mut JNIEnv, value: &JString| -> Result<String, String> {
        env.get_string(value).map(|s| s.to_string_lossy().into_owned()).map_err(|e| e.to_string())
    };
    let result = (|| -> Result<serde_json::Value, String> {
        let state_text = read(&mut env, &jstate)?;
        let next = read(&mut env, &jnext)?;
        let status = read(&mut env, &jstatus)?;
        let detail = read(&mut env, &jdetail)?;
        if state_text.len() > 512 * 1024 { return Err("Workflow state exceeds 512 KiB".into()); }
        let state: serde_json::Value = serde_json::from_str(&state_text).map_err(|e| e.to_string())?;
        transition(state, &next, &status, &detail)
    })();
    let payload = match result {
        Ok(state) => json!({"ok":true,"state":state}).to_string(),
        Err(error) => json!({"ok":false,"error":error}).to_string(),
    };
    java_string(&mut env, &payload)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_craftanything_NativeBridge_nativeWorkflowRecordCheck(
    mut env: JNIEnv,
    _class: JClass,
    jstate: JString,
    jid: JString,
    jpassed: jint,
    jscore: jdouble,
    jsummary: JString,
    jexecuted: jboolean,
) -> jstring {
    let read = |env: &mut JNIEnv, value: &JString| -> Result<String, String> {
        env.get_string(value).map(|s| s.to_string_lossy().into_owned()).map_err(|e| e.to_string())
    };
    let result = (|| -> Result<serde_json::Value, String> {
        let state_text = read(&mut env, &jstate)?;
        let id = read(&mut env, &jid)?;
        let summary = read(&mut env, &jsummary)?;
        if state_text.len() > 512 * 1024 { return Err("Workflow state exceeds 512 KiB".into()); }
        let state: serde_json::Value = serde_json::from_str(&state_text).map_err(|e| e.to_string())?;
        let passed = match jpassed { -1 => None, 0 => Some(false), 1 => Some(true), _ => return Err("Invalid check pass value".into()) };
        let score = if jscore.is_finite() { Some(jscore) } else { None };
        Ok(record_check(state, &id, passed, score, &summary, jexecuted != JNI_FALSE))
    })();
    let payload = match result {
        Ok(state) => json!({"ok":true,"state":state}).to_string(),
        Err(error) => json!({"ok":false,"error":error}).to_string(),
    };
    java_string(&mut env, &payload)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_craftanything_NativeBridge_nativeWorkflowAddEvidence(
    mut env: JNIEnv,
    _class: JClass,
    jstate: JString,
    jkind: JString,
    jsource: JString,
    jsummary: JString,
    jconfidence: jdouble,
) -> jstring {
    let read = |env: &mut JNIEnv, value: &JString| -> Result<String, String> {
        env.get_string(value).map(|s| s.to_string_lossy().into_owned()).map_err(|e| e.to_string())
    };
    let result = (|| -> Result<serde_json::Value, String> {
        let state_text = read(&mut env, &jstate)?;
        let kind = read(&mut env, &jkind)?;
        let source = read(&mut env, &jsource)?;
        let summary = read(&mut env, &jsummary)?;
        if state_text.len() > 512 * 1024 { return Err("Workflow state exceeds 512 KiB".into()); }
        let state: serde_json::Value = serde_json::from_str(&state_text).map_err(|e| e.to_string())?;
        Ok(add_evidence(state, &kind, &source, &summary, jconfidence))
    })();
    let payload = match result {
        Ok(state) => json!({"ok":true,"state":state}).to_string(),
        Err(error) => json!({"ok":false,"error":error}).to_string(),
    };
    java_string(&mut env, &payload)
}
