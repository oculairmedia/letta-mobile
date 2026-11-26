//! The canonical JSON view of a raw Automerge document (catalog-v1 §6.1), and its inverse for
//! writing a materialised view into a fresh document (rollover).
//!
//! - Map keys are sorted; `str`, `bool` and `null` map to themselves; lists map to arrays.
//! - `int` is a JSON number within ±(2^53 - 1), else `{"$int": "<decimal>"}`. The other scalars are
//!   `{"$uint": "<decimal>"}`, `{"$f64": n}`, `{"$bytes": "<base64url, no padding>"}`,
//!   `{"$counter": n}`, `{"$timestamp": n}` and `{"$unknown": {"type": n, "bytes": "<base64url>"}}`.
//!   A `text` object is `{"$text": "<string>"}`.
//! - A key holding concurrent values is `{"$conflict": [winner, ...others in op id order]}`.
//!
//! Typed views ([`crate::view`]) are computed from this value, so the app conflict rules see every
//! conflicting value, and a binding that renders the same JSON gets the same typed view.

use std::collections::BTreeMap;

use automerge::transaction::Transactable;
use automerge::{ObjId, ObjType, Prop, ReadDoc, ScalarValue, Value as AmValue};
use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde_json::{Map, Value, json};

use crate::error::CatalogError;

/// The largest integer a JSON number carries exactly.
pub const MAX_SAFE_INT: i64 = (1 << 53) - 1;

/// The canonical JSON view of the whole document (its root map).
#[must_use]
pub fn canonical_view<D: ReadDoc>(doc: &D) -> Value {
    object(doc, &automerge::ROOT, ObjType::Map)
}

fn object<D: ReadDoc>(doc: &D, obj: &ObjId, kind: ObjType) -> Value {
    match kind {
        ObjType::Map | ObjType::Table => {
            let mut keys: Vec<String> = doc.keys(obj).collect();
            keys.sort();
            let mut out = Map::new();
            for key in keys {
                out.insert(key.clone(), slot(doc, obj, Prop::Map(key)));
            }
            Value::Object(out)
        }
        ObjType::List => Value::Array(
            (0..doc.length(obj))
                .map(|index| slot(doc, obj, Prop::Seq(index)))
                .collect(),
        ),
        ObjType::Text => json!({ "$text": doc.text(obj).unwrap_or_default() }),
    }
}

/// Every value at one key or index: one value, or a `$conflict` with the winner first.
fn slot<D: ReadDoc>(doc: &D, obj: &ObjId, prop: Prop) -> Value {
    let all = doc.get_all(obj, prop).unwrap_or_default();
    let mut rendered: Vec<Value> = all
        .iter()
        .map(|(value, id)| render(doc, value, id))
        .collect();
    match rendered.len() {
        0 => Value::Null,
        1 => rendered.remove(0),
        _ => {
            // Automerge's winner is the value with the greatest op id, last in `get_all`.
            let winner = rendered.pop().expect("at least two values");
            let mut ordered = vec![winner];
            ordered.extend(rendered);
            json!({ "$conflict": ordered })
        }
    }
}

fn render<D: ReadDoc>(doc: &D, value: &AmValue<'_>, id: &ObjId) -> Value {
    match value {
        AmValue::Object(kind) => object(doc, id, *kind),
        AmValue::Scalar(scalar) => scalar_json(scalar),
    }
}

/// The canonical JSON of one scalar.
#[must_use]
pub fn scalar_json(scalar: &ScalarValue) -> Value {
    match scalar {
        ScalarValue::Str(text) => Value::String(text.to_string()),
        ScalarValue::Int(n) if (-MAX_SAFE_INT..=MAX_SAFE_INT).contains(n) => json!(n),
        ScalarValue::Int(n) => json!({ "$int": n.to_string() }),
        ScalarValue::Uint(n) => json!({ "$uint": n.to_string() }),
        ScalarValue::F64(n) => json!({ "$f64": n }),
        ScalarValue::Counter(counter) => json!({ "$counter": i64::from(counter) }),
        ScalarValue::Timestamp(n) => json!({ "$timestamp": n }),
        ScalarValue::Boolean(b) => Value::Bool(*b),
        ScalarValue::Bytes(bytes) => json!({ "$bytes": URL_SAFE_NO_PAD.encode(bytes) }),
        ScalarValue::Unknown { type_code, bytes } => {
            json!({ "$unknown": { "type": type_code, "bytes": URL_SAFE_NO_PAD.encode(bytes) } })
        }
        ScalarValue::Null => Value::Null,
    }
}

/// Decodes `{"$bytes": ...}`.
#[must_use]
pub fn bytes_of(value: &Value) -> Option<Vec<u8>> {
    let text = value.as_object()?.get("$bytes")?.as_str()?;
    URL_SAFE_NO_PAD.decode(text).ok()
}

/// `{"$bytes": ...}` for `bytes`.
#[must_use]
pub fn bytes_json(bytes: &[u8]) -> Value {
    json!({ "$bytes": URL_SAFE_NO_PAD.encode(bytes) })
}

/// The values of a slot: the winner first, then the others (one element when there is no
/// conflict).
#[must_use]
pub fn values_of(slot: &Value) -> Vec<&Value> {
    match slot
        .as_object()
        .and_then(|object| object.get("$conflict"))
        .and_then(Value::as_array)
    {
        Some(all) => all.iter().collect(),
        None => vec![slot],
    }
}

/// The winner of a slot.
#[must_use]
pub fn winner(slot: &Value) -> &Value {
    values_of(slot)[0]
}

/// Whether a value is a plain JSON object standing for a map (not a `$`-tagged scalar).
#[must_use]
pub fn is_map(value: &Value) -> bool {
    value
        .as_object()
        .is_some_and(|object| !object.keys().any(|key| key.starts_with('$')) || object.is_empty())
}

/// Writes `value` (a canonical JSON map with conflicts already resolved) into `obj` of `doc`,
/// creating maps and lists as it goes. Used to put a materialised view into a new document.
///
/// # Errors
///
/// [`CatalogError::Schema`] for a `$conflict` (resolve first) or an unknown `$` form; an Automerge
/// error otherwise.
pub fn write_map<T: Transactable>(
    doc: &mut T,
    obj: &ObjId,
    value: &Map<String, Value>,
) -> Result<(), CatalogError> {
    let sorted: BTreeMap<&String, &Value> = value.iter().collect();
    for (key, item) in sorted {
        write_at(doc, obj, Prop::Map(key.clone()), item)?;
    }
    Ok(())
}

fn write_at<T: Transactable>(
    doc: &mut T,
    obj: &ObjId,
    prop: Prop,
    value: &Value,
) -> Result<(), CatalogError> {
    match value {
        Value::Array(items) => {
            let list = put_object(doc, obj, prop, ObjType::List)?;
            for (index, item) in items.iter().enumerate() {
                insert_at(doc, &list, index, item)?;
            }
            Ok(())
        }
        Value::Object(map) if is_map(value) => {
            let child = put_object(doc, obj, prop, ObjType::Map)?;
            write_map(doc, &child, map)
        }
        other => {
            let scalar = scalar_of(other)?;
            match prop {
                Prop::Map(key) => doc.put(obj, key, scalar)?,
                Prop::Seq(index) => doc.put(obj, index, scalar)?,
            }
            Ok(())
        }
    }
}

fn insert_at<T: Transactable>(
    doc: &mut T,
    list: &ObjId,
    index: usize,
    value: &Value,
) -> Result<(), CatalogError> {
    match value {
        Value::Array(items) => {
            let child = doc.insert_object(list, index, ObjType::List)?;
            for (inner, item) in items.iter().enumerate() {
                insert_at(doc, &child, inner, item)?;
            }
        }
        Value::Object(map) if is_map(value) => {
            let child = doc.insert_object(list, index, ObjType::Map)?;
            write_map(doc, &child, map)?;
        }
        other => doc.insert(list, index, scalar_of(other)?)?,
    }
    Ok(())
}

fn put_object<T: Transactable>(
    doc: &mut T,
    obj: &ObjId,
    prop: Prop,
    kind: ObjType,
) -> Result<ObjId, CatalogError> {
    Ok(match prop {
        Prop::Map(key) => doc.put_object(obj, key, kind)?,
        Prop::Seq(index) => doc.put_object(obj, index, kind)?,
    })
}

/// The scalar a canonical JSON value stands for.
///
/// # Errors
///
/// [`CatalogError::Schema`] for an object that is not a known `$` form.
pub fn scalar_of(value: &Value) -> Result<ScalarValue, CatalogError> {
    let bad = || CatalogError::Schema(format!("not a canonical scalar: {value}"));
    Ok(match value {
        Value::Null => ScalarValue::Null,
        Value::Bool(b) => ScalarValue::Boolean(*b),
        Value::String(text) => ScalarValue::Str(text.as_str().into()),
        Value::Number(n) => match n.as_i64() {
            Some(int) => ScalarValue::Int(int),
            None => ScalarValue::F64(n.as_f64().ok_or_else(bad)?),
        },
        Value::Object(object) => {
            let (tag, inner) = object.iter().next().ok_or_else(bad)?;
            match tag.as_str() {
                "$int" => ScalarValue::Int(
                    inner
                        .as_str()
                        .and_then(|t| t.parse().ok())
                        .ok_or_else(bad)?,
                ),
                "$uint" => ScalarValue::Uint(
                    inner
                        .as_str()
                        .and_then(|t| t.parse().ok())
                        .ok_or_else(bad)?,
                ),
                "$f64" => ScalarValue::F64(inner.as_f64().ok_or_else(bad)?),
                "$counter" => ScalarValue::counter(inner.as_i64().ok_or_else(bad)?),
                "$timestamp" => ScalarValue::Timestamp(inner.as_i64().ok_or_else(bad)?),
                "$bytes" => ScalarValue::Bytes(bytes_of(value).ok_or_else(bad)?),
                _ => return Err(bad()),
            }
        }
        Value::Array(_) => return Err(bad()),
    })
}

#[cfg(test)]
mod tests {
    use automerge::transaction::Transactable as _;
    use automerge::{ActorId, AutoCommit};

    use super::*;

    #[test]
    fn concurrent_puts_render_as_a_conflict_with_the_winner_first() {
        let mut a = AutoCommit::new().with_actor(ActorId::from([1_u8; 16].as_slice()));
        a.put(automerge::ROOT, "k", "base").expect("put");
        let mut b = a.fork().with_actor(ActorId::from([2_u8; 16].as_slice()));
        a.put(automerge::ROOT, "k", "from a").expect("put");
        b.put(automerge::ROOT, "k", "from b").expect("put");
        a.merge(&mut b).expect("merge");
        let view = canonical_view(&a);
        let winner = a.get(automerge::ROOT, "k").expect("get").expect("set").0;
        let slot = &view["k"];
        assert_eq!(values_of(slot).len(), 2);
        assert_eq!(
            winner_json(&winner),
            *self::winner(slot),
            "the first conflict value is Automerge's winner"
        );
    }

    fn winner_json(value: &AmValue<'_>) -> Value {
        match value {
            AmValue::Scalar(scalar) => scalar_json(scalar),
            AmValue::Object(_) => Value::Null,
        }
    }

    #[test]
    fn scalars_round_trip_through_their_canonical_form() {
        for scalar in [
            ScalarValue::Int(5),
            ScalarValue::Int(i64::MAX),
            ScalarValue::Uint(9),
            ScalarValue::F64(1.5),
            ScalarValue::counter(3),
            ScalarValue::Timestamp(7),
            ScalarValue::Boolean(true),
            ScalarValue::Bytes(vec![0, 255]),
            ScalarValue::Str("x".into()),
            ScalarValue::Null,
        ] {
            assert_eq!(scalar_of(&scalar_json(&scalar)).expect("decodes"), scalar);
        }
    }
}
