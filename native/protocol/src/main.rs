//! Writes `Protocol.kt`: Kotlin types for hanab.live's wire protocol, from the website's own contracts.
//!
//! The website's server owns every JSON payload in its `hanabi_protocol` crate. Its generator turns the crate's
//! JSON Schemas into Zod for the website's client; this does the same for the app, in Kotlin (kotlinx.serialization).
//! Like that generator it fails closed: a schema keyword it does not translate stops it, rather than being dropped,
//! so a change to the protocol shows up as a build error or a diff, never as silently wrong decoding.
//!
//! Run `scripts/protocol.ps1 gen` (it writes `app/src/main/java/live/hanab/client/protocol/Protocol.kt`); with no
//! argument this prints the file.

use anyhow::{bail, ensure, Context, Result};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write as _;

const REV: &str = env!("HANABI_PROTOCOL_REV");

/// What a named schema becomes in Kotlin.
enum Kind {
    /// `typealias Name = <type>` (a number, a string, a list, a map).
    Alias(String),
    /// `enum class Name` with these string values.
    Enum(Vec<String>),
    /// `data class Name`, or a sealed interface for a tagged union.
    Class,
}

struct Gen {
    defs: BTreeMap<String, Value>,
    /// Named schemas found inside others (an object written inline in a field), by the name given to them.
    hoisted: BTreeMap<String, Value>,
    kinds: BTreeMap<String, Kind>,
    out: String,
}

const KEYWORDS: &[&str] = &[
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
    "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "when", "while",
];

fn pascal(text: &str) -> String {
    let mut out = String::new();
    let mut up = true;
    for c in text.chars() {
        if c.is_ascii_alphanumeric() {
            if up {
                out.extend(c.to_uppercase());
            } else {
                out.push(c);
            }
            up = false;
        } else {
            up = true;
        }
    }
    if out.starts_with(|c: char| c.is_ascii_digit()) {
        out.insert(0, 'V');
    }
    out
}

fn ident(name: &str) -> String {
    if KEYWORDS.contains(&name) {
        format!("`{name}`")
    } else {
        name.to_owned()
    }
}

/// A Kotlin string literal.
fn string_literal(text: &str) -> String {
    let mut out = String::from("\"");
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '$' => out.push_str("\\$"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn kdoc(schema: &Value, indent: &str) -> String {
    let Some(text) = schema.get("description").and_then(Value::as_str) else {
        return String::new();
    };
    if text.trim().is_empty() {
        return String::new();
    }
    let lines: Vec<String> = text
        .replace("*/", "*\\/")
        .replace("/*", "/\\*")
        .lines()
        .map(|line| {
            let line = line.strip_prefix(' ').unwrap_or(line);
            if line.is_empty() {
                format!("{indent} *")
            } else {
                format!("{indent} * {line}")
            }
        })
        .collect();
    format!("{indent}/**\n{}\n{indent} */\n", lines.join("\n"))
}

fn is_ref(schema: &Value) -> Option<&str> {
    schema["$ref"].as_str().and_then(|r| r.strip_prefix("#/$defs/"))
}

fn check_keywords(schema: &Value) -> Result<()> {
    let Some(object) = schema.as_object() else {
        return Ok(());
    };
    for key in object.keys() {
        ensure!(
            [
                "$schema", "$defs", "$ref", "title", "description", "type", "format", "properties", "required",
                "additionalProperties", "items", "enum", "const", "oneOf", "minimum", "maximum", "minLength",
                "maxLength", "minItems", "maxItems", "default", "x-brand", "x-date", "pattern",
            ]
            .contains(&key.as_str()),
            "Unsupported schema keyword: {key}"
        );
    }
    Ok(())
}

impl Gen {
    fn int_type(schema: &Value) -> &'static str {
        match schema["maximum"].as_i64() {
            Some(max) if max <= i64::from(i32::MAX) && schema["minimum"].as_i64().is_some_and(|m| m >= i64::from(i32::MIN)) => "Int",
            _ => "Long",
        }
    }

    /// Classifies a named schema, translating its parts, and emits what it needs.
    fn classify(&mut self, name: &str, schema: &Value) -> Result<Kind> {
        check_keywords(schema)?;
        if schema.get("oneOf").is_some() {
            return Ok(Kind::Class);
        }
        if let Some(values) = schema["enum"].as_array() {
            if values.iter().all(Value::is_string) {
                return Ok(Kind::Enum(values.iter().map(|v| v.as_str().unwrap().to_owned()).collect()));
            }
            ensure!(values.iter().all(Value::is_i64), "{name}: only string and integer enums are supported");
            return Ok(Kind::Alias(Self::int_type(schema).into()));
        }
        if schema["type"] == "object" && schema["properties"].is_object() {
            return Ok(Kind::Class);
        }
        if schema["type"] == "object" && schema["properties"].is_null() && !schema["additionalProperties"].is_object() {
            // An empty object, such as a message with no data.
            return Ok(Kind::Class);
        }
        Ok(Kind::Alias(self.type_of(schema, name)?))
    }

    /// The Kotlin type of a schema used where a type is needed (a field, a list item); `hint` names anything that
    /// has to be given a name of its own.
    fn type_of(&mut self, schema: &Value, hint: &str) -> Result<String> {
        check_keywords(schema)?;
        // Any JSON value (a field whose schema says nothing beyond its description).
        if *schema == Value::Bool(true)
            || schema.as_object().is_some_and(|o| !o.is_empty() && o.keys().all(|k| matches!(k.as_str(), "description" | "title")))
        {
            return Ok("JsonElement".into());
        }
        if let Some(name) = is_ref(schema) {
            ensure!(self.defs.contains_key(name) || self.hoisted.contains_key(name), "Missing schema definition: {name}");
            return Ok(name.to_owned());
        }
        if schema.get("oneOf").is_some() || schema["type"] == "object" && schema["properties"].is_object() {
            // Written inline: give it a name and treat it as a definition of its own.
            let name = pascal(hint);
            if let Some(existing) = self.hoisted.get(&name) {
                ensure!(existing == schema, "Two different inline schemas are both named {name}");
            }
            self.hoisted.insert(name.clone(), schema.clone());
            return Ok(name);
        }
        if let Some(values) = schema["enum"].as_array() {
            if values.iter().all(Value::is_string) {
                let name = pascal(hint);
                self.hoisted.insert(name.clone(), schema.clone());
                return Ok(name);
            }
            ensure!(values.iter().all(Value::is_i64), "Only string and integer enums are supported");
            return Ok(Self::int_type(schema).into());
        }
        ensure!(!schema["type"].is_array(), "Only single types are supported");
        match schema["type"].as_str().context("Missing schema type")? {
            "string" => Ok("String".into()),
            "integer" => Ok(Self::int_type(schema).into()),
            "number" => Ok("Double".into()),
            "boolean" => Ok("Boolean".into()),
            "array" => Ok(format!("List<{}>", self.type_of(&schema["items"], &format!("{hint}Item"))?)),
            "object" if schema["additionalProperties"].is_object() => {
                ensure!(schema.get("properties").is_none(), "Maps cannot also have named fields");
                Ok(format!("Map<String, {}>", self.type_of(&schema["additionalProperties"], &format!("{hint}Value"))?))
            }
            "object" => {
                ensure!(schema["additionalProperties"] == false, "Wire objects must reject unknown fields");
                // No fields at all.
                let name = pascal(hint);
                self.hoisted.insert(name.clone(), schema.clone());
                Ok(name)
            }
            other => bail!("Unsupported schema type: {other}"),
        }
    }

    /// What a default value looks like in Kotlin, for a field of Kotlin type `ty`.
    fn default_expr(&self, default: &Value, ty: &str) -> Result<String> {
        if let (Some(Kind::Enum(values)), Value::String(v)) = (self.kinds.get(ty), default) {
            ensure!(values.contains(v), "Default {v} is not a value of {ty}");
            return Ok(format!("{ty}.{}", pascal(v)));
        }
        let resolved = self.resolve_alias(ty);
        Ok(match (resolved.as_str(), default) {
            ("Boolean", Value::Bool(b)) => b.to_string(),
            ("Int", Value::Number(n)) if n.is_i64() => n.to_string(),
            ("Long", Value::Number(n)) if n.is_i64() => format!("{n}L"),
            ("Double", Value::Number(n)) => format!("{}", n.as_f64().unwrap()),
            ("String", Value::String(s)) => string_literal(s),
            _ => {
                // An object or a list: read it from its JSON, as the server would.
                let json = default.to_string().replace('$', "${'$'}");
                format!("ProtocolJson.decodeFromString<{ty}>(\"\"\"{json}\"\"\")")
            }
        })
    }

    fn resolve_alias(&self, ty: &str) -> String {
        match self.kinds.get(ty) {
            Some(Kind::Alias(target)) => self.resolve_alias(target),
            _ => ty.to_owned(),
        }
    }

    fn emit_class_fields(&mut self, owner: &str, schema: &Value, skip: &str, indent: &str) -> Result<String> {
        ensure!(schema["additionalProperties"] == false, "{owner}: wire objects must reject unknown fields");
        let required: BTreeSet<&str> =
            schema["required"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();
        let mut fields = Vec::new();
        let properties = schema["properties"].as_object().cloned().unwrap_or_default();
        for (name, property) in &properties {
            if name == skip {
                continue;
            }
            check_keywords(property)?;
            let ty = self.type_of(property, &format!("{owner}{}", pascal(name)))?;
            let escaped = ident(name);
            let serial = if escaped != *name { format!("@SerialName({}) ", string_literal(name)) } else { String::new() };
            let doc = kdoc(property, indent);
            let declaration = if let Some(default) = property.get("default") {
                ensure!(!required.contains(name.as_str()), "{owner}.{name}: a required field has a default");
                format!("{serial}val {escaped}: {ty} = {}", self.default_expr(default, &ty)?)
            } else if required.contains(name.as_str()) {
                format!("{serial}val {escaped}: {ty}")
            } else {
                format!("{serial}val {escaped}: {ty}? = null")
            };
            fields.push(format!("{doc}{indent}{declaration}"));
        }
        Ok(fields.join(",\n"))
    }

    fn emit_class(&mut self, name: &str, schema: &Value) -> Result<()> {
        let doc = kdoc(schema, "");
        if let Some(variants) = schema["oneOf"].as_array() {
            ensure!(variants.len() >= 2, "{name}: a union needs several variants");
            // Serde's internally tagged enums give every variant the same required constant string.
            let first = variants[0]["properties"].as_object().context("A union variant is an object")?;
            let discriminator = first
                .keys()
                .find(|key| {
                    variants.iter().all(|v| {
                        v["properties"][key.as_str()]["const"].is_string()
                            && v["required"].as_array().is_some_and(|r| r.iter().any(|x| x == *key))
                    })
                })
                .cloned()
                .with_context(|| format!("{name}: only tagged unions are supported"))?;
            let mut body = String::new();
            let mut tags = BTreeSet::new();
            for variant in variants {
                check_keywords(variant)?;
                let tag = variant["properties"][&discriminator]["const"].as_str().unwrap().to_owned();
                ensure!(tags.insert(tag.clone()), "{name}: duplicate tag {tag}");
                // A variant is named for its union and tag; a schema of that name already (the `Clue` union's `rank` and
                // the `ClueRank` number) makes it `...Variant`.
                let mut class = format!("{name}{}", pascal(&tag));
                while self.defs.contains_key(&class) || self.hoisted.contains_key(&class) {
                    class.push_str("Variant");
                }
                let fields = self.emit_class_fields(&class, variant, &discriminator, "    ")?;
                let doc = kdoc(variant, "");
                if fields.is_empty() {
                    let _ = writeln!(body, "{doc}@Serializable\n@SerialName({})\ndata object {class} : {name}\n", string_literal(&tag));
                } else {
                    let _ = writeln!(
                        body,
                        "{doc}@Serializable\n@SerialName({})\ndata class {class}(\n{fields},\n) : {name}\n",
                        string_literal(&tag)
                    );
                }
            }
            let _ = writeln!(self.out, "{doc}@Serializable");
            if discriminator != "type" {
                let _ = writeln!(self.out, "@JsonClassDiscriminator({})", string_literal(&discriminator));
            }
            let _ = writeln!(self.out, "sealed interface {name}\n");
            self.out.push_str(&body);
            return Ok(());
        }
        let fields = self.emit_class_fields(name, schema, "", "    ")?;
        if fields.is_empty() {
            let _ = writeln!(self.out, "{doc}@Serializable\nclass {name}\n");
        } else {
            let _ = writeln!(self.out, "{doc}@Serializable\ndata class {name}(\n{fields},\n)\n");
        }
        Ok(())
    }

    fn emit(&mut self, name: &str, schema: &Value) -> Result<()> {
        match self.kinds.get(name) {
            Some(Kind::Alias(target)) => {
                let _ = writeln!(self.out, "{}typealias {name} = {target}\n", kdoc(schema, ""));
            }
            Some(Kind::Enum(values)) => {
                let _ = writeln!(self.out, "{}@Serializable\nenum class {name} {{", kdoc(schema, ""));
                for value in values {
                    let _ = writeln!(self.out, "    @SerialName({}) {},", string_literal(value), pascal(value));
                }
                let _ = writeln!(self.out, "}}\n");
            }
            Some(Kind::Class) => self.emit_class(name, schema)?,
            None => bail!("Unclassified schema: {name}"),
        }
        Ok(())
    }
}

/// The website's limits and rules that the app uses by name. The crate has no list of its constants, so this one is ours;
/// a constant the website renames or removes stops the build here.
fn constants() -> String {
    let mut out = String::from("/** The website's constants that the app uses (its protocol crate's `pub const`s). */
object ProtocolConstants {
");
    macro_rules! numbers {
        ($($name:ident),+ $(,)?) => {$(
            let value = i64::try_from(hanabi_protocol::$name).expect("a constant is a small number");
            if i32::try_from(value).is_ok() {
                let _ = writeln!(out, "    const val {} = {value}", stringify!($name));
            } else {
                let _ = writeln!(out, "    const val {} = {value}L", stringify!($name));
            }
        )+};
    }
    numbers!(
        MIN_PLAYERS, MAX_PLAYERS, DEFAULT_MAX_PLAYERS, MAX_SUITS, MIN_RANK, MAX_RANK, START_CARD_RANK, MAX_CLUES,
        PANICKY_MAX_CLUES, MAX_STRIKES, MAX_DECK_SIZE, MAX_SEED_LENGTH, MAX_CHAT_LENGTH, MAX_NOTE_LENGTH,
        MAX_TABLE_NAME_LENGTH, DEFAULT_PAGE_LIMIT, MAX_PAGE_LIMIT, DEFAULT_TIME_BASE_SECONDS,
        DEFAULT_TIME_PER_TURN_SECONDS, MAX_TIME_BASE_SECONDS, MAX_TIME_PER_TURN_SECONDS,
    );
    for (name, value) in [
        ("DEFAULT_VARIANT_NAME", hanabi_protocol::DEFAULT_VARIANT_NAME),
        ("DEFAULT_TABLE_NAME_SUFFIX", hanabi_protocol::DEFAULT_TABLE_NAME_SUFFIX),
    ] {
        let _ = writeln!(out, "    const val {name} = {}", string_literal(value));
    }
    out.push_str("}

");
    out
}

fn main() -> Result<()> {
    // Every schema, with the ones nested in each (`$defs`), by name; the same schema may appear more than once.
    let mut defs: BTreeMap<String, Value> = BTreeMap::new();
    for (name, schema) in hanabi_protocol::schemas() {
        let mut entries = BTreeMap::new();
        let mut clean = schema.clone();
        let object = clean.as_object_mut().context("A schema is an object")?;
        for key in ["$schema", "$defs", "title"] {
            object.remove(key);
        }
        entries.insert(name, clean);
        for (nested, value) in schema["$defs"].as_object().into_iter().flatten() {
            let mut value = value.clone();
            value.as_object_mut().context("A schema is an object")?.remove("title");
            entries.insert(nested.clone(), value);
        }
        for (name, value) in entries {
            if let Some(existing) = defs.insert(name.clone(), value.clone()) {
                ensure!(existing == value, "Conflicting wire schema definition: {name}");
            }
        }
    }

    let mut gen = Gen { defs: defs.clone(), hoisted: BTreeMap::new(), kinds: BTreeMap::new(), out: String::new() };
    // Classify every definition first (that finds the inline ones), then keep going until nothing new turns up.
    let mut pending: Vec<(String, Value)> = defs.into_iter().collect();
    let mut order: Vec<(String, Value)> = Vec::new();
    while let Some((name, schema)) = pending.pop() {
        if gen.kinds.contains_key(&name) {
            continue;
        }
        let kind = gen.classify(&name, &schema).with_context(|| format!("Cannot translate the {name} schema"))?;
        gen.kinds.insert(name.clone(), kind);
        order.push((name, schema));
        // Anything written inline inside it is found while its fields are translated below; do that now, so those
        // get classified too.
        let mut probe = Gen { defs: gen.defs.clone(), hoisted: BTreeMap::new(), kinds: BTreeMap::new(), out: String::new() };
        probe.kinds.extend(gen.kinds.iter().map(|(k, v)| (k.clone(), match v {
            Kind::Alias(t) => Kind::Alias(t.clone()),
            Kind::Enum(e) => Kind::Enum(e.clone()),
            Kind::Class => Kind::Class,
        })));
        let (_, schema) = order.last().unwrap().clone();
        let last = order.last().unwrap().0.clone();
        if matches!(gen.kinds[&last], Kind::Class) {
            probe.emit_class(&last, &schema).with_context(|| format!("Cannot translate the {last} schema"))?;
        }
        for (hoisted, value) in probe.hoisted {
            if !gen.kinds.contains_key(&hoisted) {
                gen.defs.insert(hoisted.clone(), value.clone());
                pending.push((hoisted, value));
            }
        }
    }
    order.sort_by(|a, b| a.0.cmp(&b.0));

    let mut header = String::new();
    let _ = writeln!(header, "// Generated by native/protocol from the website's wire contracts. Do not edit:");
    let _ = writeln!(header, "// run `scripts/protocol.ps1 gen`. Pinned to Hanabi-Live/hanabi-live {REV}.");
    header.push_str(
        "@file:OptIn(ExperimentalSerializationApi::class)\n@file:Suppress(\"unused\", \"RedundantVisibilityModifier\")\n\npackage live.hanab.client.protocol\n\n\
         import kotlinx.serialization.ExperimentalSerializationApi\nimport kotlinx.serialization.SerialName\nimport kotlinx.serialization.Serializable\n\
         import kotlinx.serialization.decodeFromString\nimport kotlinx.serialization.json.Json\nimport kotlinx.serialization.json.JsonClassDiscriminator\n\
         import kotlinx.serialization.json.JsonElement\nimport kotlinx.serialization.json.decodeFromJsonElement\nimport kotlinx.serialization.json.encodeToJsonElement\n\n",
    );
    let _ = writeln!(header, "/** The commit of the website's repository that these types come from. */\nconst val PROTOCOL_REV = \"{REV}\"\n");
    header.push_str(
        "/**\n * How the protocol is read and written: extra fields from a newer server are ignored, but a missing or\n * mistyped one is an error (so a change shows up as an [InInvalid] message, never as wrong data).\n */\n\
         val ProtocolJson = Json {\n    ignoreUnknownKeys = true\n    explicitNulls = false\n    classDiscriminator = \"type\"\n    encodeDefaults = true\n}\n\n",
    );
    gen.out.push_str(&header);
    gen.out.push_str(&constants());

    // Emit the named schemas: first those found inline, then the rest, each once.
    let all: Vec<(String, Value)> = order;
    for (name, schema) in &all {
        gen.emit(name, schema).with_context(|| format!("Cannot translate the {name} schema"))?;
    }

    // The commands: which payload each message carries.
    let client = hanabi_protocol::request_contracts();
    let server = hanabi_protocol::event_contracts();
    let mut out = String::new();
    out.push_str("/** A message of the server. */\nsealed interface Inbound\n\n");
    out.push_str("/** A message of the server with a name this version of the protocol does not have. */\nclass InUnknown(val command: String, val data: JsonElement?) : Inbound\n\n");
    out.push_str("/** A message of the server that does not read as what its name promises: the protocol has changed. */\nclass InInvalid(val command: String, val data: JsonElement?, val error: String) : Inbound\n\n");
    for (command, payload) in &server {
        let _ = writeln!(out, "class In{}(val data: {payload}) : Inbound\n", pascal(command));
    }
    out.push_str("/** Reads a frame's command and data (the part after the first space; null if there is none). */\nfun decodeInbound(command: String, data: JsonElement?): Inbound {\n    val body = data ?: kotlinx.serialization.json.JsonObject(emptyMap())\n    return try {\n        when (command) {\n");
    for (command, payload) in &server {
        let _ = writeln!(out, "            {} -> In{}(ProtocolJson.decodeFromJsonElement<{payload}>(body))", string_literal(command), pascal(command));
    }
    out.push_str("            else -> InUnknown(command, data)\n        }\n    } catch (e: Exception) {\n        InInvalid(command, data, e.message ?: e.toString())\n    }\n}\n\n");
    out.push_str("/** A message to the server. */\nsealed interface Outbound {\n    /** The command and its data, as they go on the wire. */\n    fun encode(): Pair<String, JsonElement>\n}\n\n");
    for (command, payload) in &client {
        let _ = writeln!(
            out,
            "class Out{}(val data: {payload}) : Outbound {{\n    override fun encode(): Pair<String, JsonElement> = {} to ProtocolJson.encodeToJsonElement<{payload}>(data)\n}}\n",
            pascal(command),
            string_literal(command)
        );
    }
    out.push_str("/** Reads a command of the client (the server never sends these; the tests do, to check the types). Throws if it is not one. */
fun decodeOutbound(command: String, data: JsonElement): Outbound = when (command) {
");
    for (command, payload) in &client {
        let _ = writeln!(out, "    {} -> Out{}(ProtocolJson.decodeFromJsonElement<{payload}>(data))", string_literal(command), pascal(command));
    }
    out.push_str("    else -> throw IllegalArgumentException(\"Not a command of the client: $command\")
}

");
    // The replies that carry data, by the name of the request they answer.
    let replies = hanabi_protocol::reply_contracts();
    out.push_str("/** The reply of the server to a request. Only some requests are answered with data; the rest with an empty reply. */
sealed interface Reply

");
    out.push_str("/** A reply that does not read as what its request promises: the protocol has changed. */
class ReplyInvalid(val command: String, val data: JsonElement?, val error: String) : Reply

");
    for (command, payload) in &replies {
        let _ = writeln!(out, "class Reply{}(val data: {payload}) : Reply
", pascal(command));
    }
    out.push_str("/** Reads the data of the reply to a request called [command]; null if that request has no reply with data. */
fun decodeReply(command: String, data: JsonElement?): Reply? {
    return try {
        when (command) {
");
    for (command, payload) in &replies {
        let _ = writeln!(out, "            {} -> Reply{}(ProtocolJson.decodeFromJsonElement<{payload}>(data ?: kotlinx.serialization.json.JsonObject(emptyMap())))", string_literal(command), pascal(command));
    }
    out.push_str("            else -> null
        }
    } catch (e: Exception) {
        ReplyInvalid(command, data, e.message ?: e.toString())
    }
}

");
    out.push_str("/** The commands of the server, with the payload each carries. */\nval SERVER_COMMANDS: Set<String> = setOf(\n");
    for command in server.keys() {
        let _ = writeln!(out, "    {},", string_literal(command));
    }
    out.push_str(")\n\n/** The commands of the client. */\nval CLIENT_COMMANDS: Set<String> = setOf(\n");
    for command in client.keys() {
        let _ = writeln!(out, "    {},", string_literal(command));
    }
    out.push_str(")\n");
    gen.out.push_str(&out);

    match std::env::args().nth(1) {
        // Written here, not through a shell redirect, so the file is UTF-8 with Unix line ends on every machine.
        Some(path) => std::fs::write(&path, gen.out).with_context(|| format!("Cannot write {path}"))?,
        None => print!("{}", gen.out),
    }
    Ok(())
}
