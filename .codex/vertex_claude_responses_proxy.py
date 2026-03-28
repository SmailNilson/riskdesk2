#!/usr/bin/env python3

import datetime as dt
import hmac
import json
import os
import ssl
import subprocess
import sys
import threading
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 4141
DEFAULT_MAX_TOKENS = 8192
DEFAULT_REGION = "global"
ANTHROPIC_VERSION = "2023-06-01"

_TOKEN_CACHE = {"value": None, "expires_at": None, "quota_project_id": None}
_TOKEN_LOCK = threading.Lock()


def env(name, default=None):
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    return value


def normalize_model(model_name):
    aliases = {
        "opus": env("ANTHROPIC_DEFAULT_OPUS_MODEL", "claude-opus-4-6"),
        "sonnet": env("ANTHROPIC_DEFAULT_SONNET_MODEL", "claude-sonnet-4-6"),
    }
    return aliases.get(model_name, model_name)


def run_command(command):
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "command failed")
    return result.stdout.strip()


def get_gcloud_project():
    for candidate in (
        env("ANTHROPIC_VERTEX_PROJECT_ID"),
        env("GOOGLE_CLOUD_PROJECT"),
        env("GCLOUD_PROJECT"),
    ):
        if candidate:
            return candidate
    try:
        project = run_command(["gcloud", "config", "get-value", "project"])
        if project and project != "(unset)":
            return project
    except RuntimeError:
        return None
    return None


def get_vertex_region():
    return env("CLOUD_ML_REGION", DEFAULT_REGION)


def get_proxy_token():
    return env("CODEX_VERTEX_PROXY_TOKEN")


def get_max_tokens():
    raw_value = env("CODEX_VERTEX_MAX_TOKENS")
    if not raw_value:
        return DEFAULT_MAX_TOKENS
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise RuntimeError("CODEX_VERTEX_MAX_TOKENS must be an integer") from exc
    if value <= 0:
        raise RuntimeError("CODEX_VERTEX_MAX_TOKENS must be greater than zero")
    return value


def build_ssl_context():
    ca_candidates = []
    explicit_ca = env("CODEX_VERTEX_CA_FILE")
    if explicit_ca:
        ca_candidates.append(explicit_ca)
    ca_candidates.append(env("SSL_CERT_FILE"))
    ca_candidates.append("/etc/ssl/cert.pem")
    try:
        import certifi  # type: ignore

        ca_candidates.append(certifi.where())
    except Exception:
        pass

    for candidate in ca_candidates:
        if candidate and os.path.exists(candidate):
            return ssl.create_default_context(cafile=candidate)
    return ssl.create_default_context()


def _load_access_token():
    output = run_command(
        ["gcloud", "auth", "application-default", "print-access-token", "--format=json"]
    )
    payload = json.loads(output)
    token = payload.get("token")
    expiry = payload.get("expiry", {}).get("datetime")
    if not token or not expiry:
        raise RuntimeError("gcloud did not return a usable ADC access token")
    expires_at = dt.datetime.fromisoformat(expiry)
    return {
        "value": token,
        "expires_at": expires_at,
        "quota_project_id": payload.get("quota_project_id"),
    }


def get_access_token():
    with _TOKEN_LOCK:
        cached_value = _TOKEN_CACHE["value"]
        expires_at = _TOKEN_CACHE["expires_at"]
        if cached_value and expires_at:
            if expires_at - dt.timedelta(seconds=60) > dt.datetime.now():
                return dict(_TOKEN_CACHE)
        fresh = _load_access_token()
        _TOKEN_CACHE.update(fresh)
        return dict(_TOKEN_CACHE)


def build_vertex_url(model_name, stream):
    project_id = get_gcloud_project()
    if not project_id:
        raise RuntimeError(
            "No Vertex project found. Set ANTHROPIC_VERTEX_PROJECT_ID or configure a gcloud project."
        )
    region = get_vertex_region()
    base_url = env("ANTHROPIC_VERTEX_BASE_URL")
    if base_url:
        api_base = base_url.rstrip("/")
    elif region == "global":
        api_base = "https://aiplatform.googleapis.com/v1"
    else:
        api_base = f"https://{region}-aiplatform.googleapis.com/v1"
    method = "streamRawPredict" if stream else "rawPredict"
    return (
        f"{api_base}/projects/{project_id}/locations/{region}"
        f"/publishers/anthropic/models/{normalize_model(model_name)}:{method}"
    )


def error_payload(message, code="bad_request"):
    return {"error": {"message": message, "type": code}}


def extract_text_blocks(content_items):
    blocks = []
    for item in content_items or []:
        item_type = item.get("type")
        if item_type in {"input_text", "output_text", "text"}:
            text = item.get("text", "")
            if text:
                blocks.append({"type": "text", "text": text})
        elif item_type == "input_image":
            image_url = item.get("image_url")
            if image_url and image_url.startswith("data:"):
                try:
                    header, encoded = image_url.split(",", 1)
                    media_type = header.split(";")[0].split(":", 1)[1]
                    blocks.append(
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": media_type,
                                "data": encoded,
                            },
                        }
                    )
                except (IndexError, ValueError):
                    blocks.append({"type": "text", "text": "[Unsupported image input omitted]"})
            else:
                blocks.append({"type": "text", "text": "[Unsupported image input omitted]"})
    return blocks


def serialize_tool_output(output):
    if isinstance(output, str):
        return output
    return json.dumps(output, ensure_ascii=False)


def convert_request_to_anthropic(openai_request):
    system_parts = []
    instructions = openai_request.get("instructions")
    if instructions:
        system_parts.append(instructions)

    messages = []
    pending_assistant_tool_uses = []
    pending_user_tool_results = []

    def flush_assistant_tool_uses():
        nonlocal pending_assistant_tool_uses
        if pending_assistant_tool_uses:
            messages.append({"role": "assistant", "content": pending_assistant_tool_uses})
            pending_assistant_tool_uses = []

    def flush_user_tool_results():
        nonlocal pending_user_tool_results
        if pending_user_tool_results:
            messages.append({"role": "user", "content": pending_user_tool_results})
            pending_user_tool_results = []

    def flush_pending():
        flush_assistant_tool_uses()
        flush_user_tool_results()

    for item in openai_request.get("input", []):
        item_type = item.get("type")
        if item_type == "message":
            flush_pending()
            role = item.get("role", "user")
            content_blocks = extract_text_blocks(item.get("content", []))
            if role in {"developer", "system"}:
                text_content = "\n\n".join(
                    block["text"] for block in content_blocks if block["type"] == "text"
                )
                if text_content:
                    system_parts.append(text_content)
                continue
            if not content_blocks:
                continue
            if role == "assistant":
                messages.append({"role": "assistant", "content": content_blocks})
            else:
                messages.append({"role": "user", "content": content_blocks})
        elif item_type == "function_call":
            flush_user_tool_results()
            raw_arguments = item.get("arguments", "") or "{}"
            try:
                parsed_arguments = json.loads(raw_arguments)
            except json.JSONDecodeError:
                parsed_arguments = {"raw_arguments": raw_arguments}
            pending_assistant_tool_uses.append(
                {
                    "type": "tool_use",
                    "id": item["call_id"],
                    "name": item["name"],
                    "input": parsed_arguments,
                }
            )
        elif item_type == "function_call_output":
            flush_assistant_tool_uses()
            pending_user_tool_results.append(
                {
                    "type": "tool_result",
                    "tool_use_id": item["call_id"],
                    "content": serialize_tool_output(item.get("output", "")),
                }
            )
    flush_pending()

    if not messages:
        messages = [{"role": "user", "content": [{"type": "text", "text": ""}]}]

    anthropic_request = {
        "anthropic_version": ANTHROPIC_VERSION,
        "model": normalize_model(openai_request.get("model", "claude-opus-4-6")),
        "max_tokens": get_max_tokens(),
        "messages": messages,
    }

    system_prompt = "\n\n".join(part for part in system_parts if part)
    if system_prompt:
        anthropic_request["system"] = system_prompt

    tools = []
    for tool in openai_request.get("tools", []):
        if tool.get("type") != "function":
            continue
        tools.append(
            {
                "name": tool["name"],
                "description": tool.get("description", ""),
                "input_schema": tool.get("parameters", {"type": "object", "properties": {}}),
            }
        )
    if tools:
        anthropic_request["tools"] = tools

    tool_choice = openai_request.get("tool_choice")
    if tools and tool_choice:
        translated_tool_choice = translate_tool_choice(tool_choice, openai_request)
        if translated_tool_choice:
            anthropic_request["tool_choice"] = translated_tool_choice

    return anthropic_request


def translate_tool_choice(tool_choice, openai_request):
    parallel = openai_request.get("parallel_tool_calls", False)
    if tool_choice == "auto":
        return {"type": "auto", "disable_parallel_tool_use": not parallel}
    if tool_choice == "required":
        return {"type": "any", "disable_parallel_tool_use": not parallel}
    if tool_choice == "none":
        return None
    if isinstance(tool_choice, dict):
        if tool_choice.get("type") == "function" and tool_choice.get("name"):
            return {
                "type": "tool",
                "name": tool_choice["name"],
                "disable_parallel_tool_use": not parallel,
            }
        if tool_choice.get("type") == "auto":
            return {"type": "auto", "disable_parallel_tool_use": not parallel}
    return {"type": "auto", "disable_parallel_tool_use": not parallel}


def openai_message_item(item_id, text):
    return {
        "id": item_id,
        "type": "message",
        "status": "completed",
        "role": "assistant",
        "content": [{"type": "output_text", "text": text, "annotations": []}],
    }


def openai_function_call_item(item_id, call_id, name, arguments):
    return {
        "id": item_id,
        "type": "function_call",
        "status": "completed",
        "call_id": call_id,
        "name": name,
        "arguments": arguments,
    }


def anthropic_response_to_openai(anthropic_response, requested_model):
    output_items = []
    for block in anthropic_response.get("content", []):
        block_type = block.get("type")
        if block_type == "text":
            output_items.append(openai_message_item(f"msg_{uuid.uuid4().hex[:8]}", block.get("text", "")))
        elif block_type == "tool_use":
            arguments = json.dumps(block.get("input", {}), ensure_ascii=False)
            output_items.append(
                openai_function_call_item(
                    f"fc_{uuid.uuid4().hex[:8]}",
                    block.get("id", f"call_{uuid.uuid4().hex[:8]}"),
                    block.get("name", "tool"),
                    arguments,
                )
            )
    return {
        "id": anthropic_response.get("id", f"resp_{uuid.uuid4().hex[:8]}"),
        "object": "response",
        "created_at": int(dt.datetime.now().timestamp()),
        "status": "completed",
        "model": requested_model,
        "output": output_items,
        "parallel_tool_calls": False,
        "store": False,
    }


def sse_event(handler, payload):
    encoded = json.dumps(payload, ensure_ascii=False)
    handler.wfile.write(f"data: {encoded}\n\n".encode("utf-8"))
    handler.wfile.flush()


def stream_vertex_to_openai(handler, upstream):
    requested_model = handler._openai_request["model"]
    response_id = f"resp_{uuid.uuid4().hex[:8]}"
    created_at = int(dt.datetime.now().timestamp())
    output_items = []
    active_blocks = {}
    next_output_index = 0

    def ensure_response_created():
        if getattr(handler, "_response_created", False):
            return
        handler._response_created = True
        sse_event(
            handler,
            {
                "type": "response.created",
                "response": {
                    "id": response_id,
                    "object": "response",
                    "created_at": created_at,
                    "status": "in_progress",
                    "model": requested_model,
                    "output": [],
                    "parallel_tool_calls": False,
                    "store": False,
                },
            },
        )

    event_name = None
    data_lines = []

    def dispatch_event():
        nonlocal event_name, data_lines, next_output_index
        if not data_lines:
            event_name = None
            return
        payload_text = "\n".join(data_lines)
        if payload_text == "[DONE]":
            event_name = None
            data_lines = []
            return
        payload = json.loads(payload_text)
        payload_type = payload.get("type") or event_name
        ensure_response_created()

        if payload_type == "content_block_start":
            block = payload["content_block"]
            block_index = payload["index"]
            if block["type"] == "text":
                item_id = f"msg_{uuid.uuid4().hex[:8]}"
                active_blocks[block_index] = {
                    "kind": "text",
                    "item_id": item_id,
                    "output_index": next_output_index,
                    "text": "",
                }
                sse_event(
                    handler,
                    {
                        "type": "response.output_item.added",
                        "response_id": response_id,
                        "output_index": next_output_index,
                        "item": {
                            "id": item_id,
                            "type": "message",
                            "status": "in_progress",
                            "role": "assistant",
                            "content": [],
                        },
                    },
                )
                sse_event(
                    handler,
                    {
                        "type": "response.content_part.added",
                        "response_id": response_id,
                        "output_index": next_output_index,
                        "item_id": item_id,
                        "content_index": 0,
                        "part": {"type": "output_text", "text": "", "annotations": []},
                    },
                )
                next_output_index += 1
            elif block["type"] == "tool_use":
                initial_arguments = ""
                if block.get("input"):
                    initial_arguments = json.dumps(block["input"], ensure_ascii=False)
                item_id = f"fc_{uuid.uuid4().hex[:8]}"
                active_blocks[block_index] = {
                    "kind": "tool_use",
                    "item_id": item_id,
                    "output_index": next_output_index,
                    "call_id": block.get("id", f"call_{uuid.uuid4().hex[:8]}"),
                    "name": block["name"],
                    "arguments": initial_arguments,
                }
                sse_event(
                    handler,
                    {
                        "type": "response.output_item.added",
                        "response_id": response_id,
                        "output_index": next_output_index,
                        "item": {
                            "id": item_id,
                            "type": "function_call",
                            "status": "in_progress",
                            "call_id": active_blocks[block_index]["call_id"],
                            "name": block["name"],
                            "arguments": initial_arguments,
                        },
                    },
                )
                next_output_index += 1
        elif payload_type == "content_block_delta":
            block_index = payload["index"]
            state = active_blocks.get(block_index)
            if not state:
                event_name = None
                data_lines = []
                return
            delta = payload["delta"]
            if state["kind"] == "text" and delta["type"] == "text_delta":
                state["text"] += delta.get("text", "")
                sse_event(
                    handler,
                    {
                        "type": "response.output_text.delta",
                        "response_id": response_id,
                        "output_index": state["output_index"],
                        "item_id": state["item_id"],
                        "content_index": 0,
                        "delta": delta.get("text", ""),
                    },
                )
            elif state["kind"] == "tool_use" and delta["type"] == "input_json_delta":
                partial_json = delta.get("partial_json", "")
                state["arguments"] += partial_json
                sse_event(
                    handler,
                    {
                        "type": "response.function_call_arguments.delta",
                        "response_id": response_id,
                        "output_index": state["output_index"],
                        "item_id": state["item_id"],
                        "delta": partial_json,
                    },
                )
        elif payload_type == "content_block_stop":
            block_index = payload["index"]
            state = active_blocks.pop(block_index, None)
            if not state:
                event_name = None
                data_lines = []
                return
            if state["kind"] == "text":
                final_item = openai_message_item(state["item_id"], state["text"])
                output_items.append(final_item)
                sse_event(
                    handler,
                    {
                        "type": "response.output_text.done",
                        "response_id": response_id,
                        "output_index": state["output_index"],
                        "item_id": state["item_id"],
                        "content_index": 0,
                        "text": state["text"],
                    },
                )
                sse_event(
                    handler,
                    {
                        "type": "response.content_part.done",
                        "response_id": response_id,
                        "output_index": state["output_index"],
                        "item_id": state["item_id"],
                        "content_index": 0,
                        "part": {"type": "output_text", "text": state["text"], "annotations": []},
                    },
                )
                sse_event(
                    handler,
                    {
                        "type": "response.output_item.done",
                        "response_id": response_id,
                        "output_index": state["output_index"],
                        "item": final_item,
                    },
                )
            elif state["kind"] == "tool_use":
                arguments = state["arguments"] or "{}"
                final_item = openai_function_call_item(
                    state["item_id"], state["call_id"], state["name"], arguments
                )
                output_items.append(final_item)
                sse_event(
                    handler,
                    {
                        "type": "response.function_call_arguments.done",
                        "response_id": response_id,
                        "output_index": state["output_index"],
                        "item_id": state["item_id"],
                        "arguments": arguments,
                    },
                )
                sse_event(
                    handler,
                    {
                        "type": "response.output_item.done",
                        "response_id": response_id,
                        "output_index": state["output_index"],
                        "item": final_item,
                    },
                )
        elif payload_type == "message_stop":
            sse_event(
                handler,
                {
                    "type": "response.completed",
                    "response": {
                        "id": response_id,
                        "object": "response",
                        "created_at": created_at,
                        "status": "completed",
                        "model": requested_model,
                        "output": output_items,
                        "parallel_tool_calls": False,
                        "store": False,
                    },
                },
            )
        event_name = None
        data_lines = []

    for raw_line in upstream:
        decoded_line = raw_line.decode("utf-8")
        if decoded_line in {"\n", "\r\n"}:
            dispatch_event()
            continue
        if decoded_line.startswith("event:"):
            event_name = decoded_line.split(":", 1)[1].strip()
        elif decoded_line.startswith("data:"):
            data_lines.append(decoded_line.split(":", 1)[1].lstrip())

    if data_lines:
        dispatch_event()
    handler.wfile.write(b"data: [DONE]\n\n")
    handler.wfile.flush()


class ProxyHandler(BaseHTTPRequestHandler):
    server_version = "RiskDeskCodexVertexProxy/1.0"

    def do_GET(self):
        if self.path == "/health":
            self.handle_health()
            return
        if self.path == "/v1/models":
            self.handle_models()
            return
        self.respond_json(404, error_payload(f"Unknown path {self.path}", "not_found"))

    def do_POST(self):
        if self.path != "/v1/responses":
            self.respond_json(404, error_payload(f"Unknown path {self.path}", "not_found"))
            return
        if not self.authorize_request():
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            openai_request = json.loads(body or "{}")
            self._openai_request = openai_request
            anthropic_request = convert_request_to_anthropic(openai_request)
            stream = bool(openai_request.get("stream", True))
            self.forward_to_vertex(anthropic_request, stream)
        except json.JSONDecodeError:
            self.respond_json(400, error_payload("Request body must be valid JSON"))
        except RuntimeError as exc:
            self.respond_json(500, error_payload(str(exc), "server_error"))
        except Exception as exc:  # pragma: no cover - defensive path
            self.respond_json(500, error_payload(f"Unexpected proxy error: {exc}", "server_error"))

    def handle_health(self):
        issues = []
        if not get_proxy_token():
            issues.append("CODEX_VERTEX_PROXY_TOKEN is not set")
        if not get_gcloud_project():
            issues.append("No Vertex project found from env or gcloud config")
        try:
            token_info = get_access_token()
            adc_ok = True
            adc_expiry = token_info["expires_at"].isoformat(sep=" ")
        except RuntimeError as exc:
            adc_ok = False
            adc_expiry = None
            issues.append(str(exc))
        payload = {
            "ok": len(issues) == 0,
            "project_id": get_gcloud_project(),
            "region": get_vertex_region(),
            "default_model": normalize_model("claude-opus-4-6"),
            "adc_ok": adc_ok,
            "adc_expiry": adc_expiry,
            "issues": issues,
        }
        self.respond_json(200 if payload["ok"] else 503, payload)

    def handle_models(self):
        if not self.authorize_request(optional=True):
            return
        models = [
            normalize_model("claude-opus-4-6"),
            normalize_model("claude-sonnet-4-6"),
        ]
        data = [
            {
                "id": model_name,
                "object": "model",
                "created": 0,
                "owned_by": "google-vertex-anthropic",
            }
            for model_name in dict.fromkeys(models)
        ]
        self.respond_json(200, {"object": "list", "data": data})

    def authorize_request(self, optional=False):
        expected = get_proxy_token()
        if not expected and optional:
            return True
        if not expected:
            self.respond_json(
                500,
                error_payload(
                    "CODEX_VERTEX_PROXY_TOKEN is not set on the proxy process.",
                    "server_error",
                ),
            )
            return False
        actual = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if not actual or not hmac.compare_digest(actual, expected):
            self.respond_json(401, error_payload("Invalid proxy bearer token", "unauthorized"))
            return False
        return True

    def forward_to_vertex(self, anthropic_request, stream):
        token_info = get_access_token()
        requested_model = anthropic_request["model"]
        vertex_url = build_vertex_url(requested_model, stream)
        request_body = dict(anthropic_request)
        request_body.pop("model", None)
        if stream:
            request_body["stream"] = True
        headers = {
            "Authorization": f"Bearer {token_info['value']}",
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "text/event-stream" if stream else "application/json",
        }
        if token_info.get("quota_project_id"):
            headers["x-goog-user-project"] = token_info["quota_project_id"]

        request = urllib.request.Request(
            vertex_url,
            data=json.dumps(request_body).encode("utf-8"),
            headers=headers,
            method="POST",
        )

        try:
            with urllib.request.urlopen(request, timeout=300, context=build_ssl_context()) as upstream:
                if stream:
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Cache-Control", "no-cache")
                    self.send_header("Connection", "keep-alive")
                    self.end_headers()
                    stream_vertex_to_openai(self, upstream)
                    return
                payload = json.loads(upstream.read().decode("utf-8"))
                self.respond_json(200, anthropic_response_to_openai(payload, requested_model))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            try:
                detail = json.loads(body)
            except json.JSONDecodeError:
                detail = {"raw": body}
            message = detail.get("error", {}).get("message") or body or exc.reason
            self.respond_json(exc.code, error_payload(f"Vertex request failed: {message}", "upstream_error"))

    def respond_json(self, status_code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string, *args):
        return


def main():
    host = env("CODEX_VERTEX_PROXY_HOST", DEFAULT_HOST)
    port = int(env("CODEX_VERTEX_PROXY_PORT", str(DEFAULT_PORT)))
    server = ThreadingHTTPServer((host, port), ProxyHandler)
    print(
        f"Vertex Claude Responses proxy listening on http://{host}:{port}",
        file=sys.stderr,
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
