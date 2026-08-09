"""MCP elicitation ⇄ Remotex user-input translation.

When an MCP server calls `elicitation/create`, codex forwards it to us as
the `mcpServer/elicitation/request` **server request** — it expects a
JSON-RPC reply. Shape (codex 0.147, `McpServerElicitationRequestParams`):

    {serverName, threadId, message,
     mode: "form" | "openai/form" | "url",
     requestedSchema?: {properties: {<name>: <primitive schema>},
                        required: [<name>]},   # form modes
     url?, elicitationId?}                     # url mode

Reply (`McpServerElicitationRequestResponse`):

    {action: "accept" | "decline" | "cancel", content?: {<name>: <typed>}}

We render it through the `user-input-request` / `user-input-response`
dialog all three clients already implement, so this needs no client work.
Before this existed the request fell through to
`_reject_unsupported_server_request`, which answered -32601 *and* failed
the user's turn.
"""
from __future__ import annotations

FORM_MODES = ("form", "openai/form")


def _const_options(variants: list) -> list[dict]:
    return [
        {"label": str(v["const"]), "description": str(v.get("title") or "")}
        for v in variants
        if isinstance(v, dict) and v.get("const")
    ]


def _enum_options(enum: list, names) -> list[dict]:
    titled = isinstance(names, list) and len(names) == len(enum)
    return [
        {"label": str(value), "description": str(names[i]) if titled else ""}
        for i, value in enumerate(enum)
    ]


def _options(schema: dict) -> list[dict]:
    """Enum-ish field → the `{label, description}` options our dialog draws.

    `label` is what the client sends back, so it must be the wire value,
    not the pretty name — a titled enum puts the title in `description`.

    Covers every enum shape codex 0.147 can send: single-select
    (`oneOf[{const,title}]`, `enum` with optional `enumNames`),
    multi-select (`items.anyOf[{const,title}]`, `items.enum`) and booleans.
    """
    # Multi-select puts its choices under `items`. Our dialog is single-pick,
    # so the user still chooses from the right set — the answer just comes
    # back as a one-element array (see `_coerce`, type "array").
    items = schema.get("items")
    if isinstance(items, dict):
        any_of = items.get("anyOf")
        if isinstance(any_of, list):
            return _const_options(any_of)
        enum = items.get("enum")
        if isinstance(enum, list):
            return _enum_options(enum, items.get("enumNames"))
    one_of = schema.get("oneOf")
    if isinstance(one_of, list):
        return _const_options(one_of)
    enum = schema.get("enum")
    if isinstance(enum, list):
        return _enum_options(enum, schema.get("enumNames"))
    if schema.get("type") == "boolean":
        return [{"label": "true", "description": ""},
                {"label": "false", "description": ""}]
    # ponytail: nested objects have no dialog equivalent — they degrade to a
    # free-text box, which still round-trips as a string.
    return []


def _ack_question(header: str, text: str) -> dict:
    return {
        "id": "acknowledged",
        "header": header,
        "question": text,
        "isOther": False,
        "isSecret": False,
        "options": [{"label": "Continue", "description": ""}],
    }


def elicitation_questions(params: dict) -> tuple[list[dict], dict]:
    """Return (questions, spec).

    `questions` is the `user-input-request` payload. `spec` is the field
    name → schema map, kept so the answers can be coerced back to the
    types the MCP server asked for.
    """
    message = str(params.get("message") or "").strip()
    server = str(params.get("serverName") or "MCP server")

    if params.get("mode") == "url":
        url = str(params.get("url") or "")
        return [_ack_question(server, f"{message}\n\n{url}".strip())], {}

    requested = params.get("requestedSchema")
    requested = requested if isinstance(requested, dict) else {}
    props = requested.get("properties")
    props = props if isinstance(props, dict) else {}
    required = requested.get("required")
    required = required if isinstance(required, list) else []

    questions: list[dict] = []
    for name, schema in props.items():
        schema = schema if isinstance(schema, dict) else {}
        text = str(schema.get("description") or message or name)
        if name not in required:
            text = f"{text} (optional)"
        questions.append({
            "id": str(name),
            "header": str(schema.get("title") or name),
            "question": text,
            "isOther": False,
            "isSecret": False,
            "options": _options(schema),
        })

    if not questions:
        # Empty form, or a schema shape we couldn't read. Ask for a plain
        # acknowledgement so the turn keeps moving instead of stalling on a
        # request the user never sees.
        questions.append(_ack_question(server, message or "Continue?"))
    return questions, props


def _coerce(schema: dict, picked: list[str]):
    kind = schema.get("type")
    # Multi-select schemas carry no `type` at all in codex 0.147 — the giveaway
    # is `items`. Either way the MCP server wants an array back, not a string.
    if kind == "array" or isinstance(schema.get("items"), dict):
        return list(picked)
    raw = picked[0]
    if kind == "boolean":
        return str(raw).strip().lower() in {"true", "1", "yes", "y", "on"}
    if kind in ("number", "integer"):
        try:
            return int(raw) if kind == "integer" else float(raw)
        except (TypeError, ValueError):
            # Let the MCP server reject a bad value; never crash the turn.
            return raw
    return raw


def elicitation_result(spec: dict, answers: dict) -> dict:
    """Normalized client answers (`{qid: {"answers": [str]}}`) → codex reply.

    No answers at all means the user dismissed the dialog, which is a
    decline — an `accept` with empty content would look to the MCP server
    like the user submitted a blank form.
    """
    content: dict = {}
    for qid, value in (answers or {}).items():
        picked = (value or {}).get("answers") or []
        if not picked:
            continue
        field = spec.get(qid)
        content[str(qid)] = _coerce(field if isinstance(field, dict) else {}, picked)
    if not content:
        return {"action": "decline"}
    return {"action": "accept", "content": content}
