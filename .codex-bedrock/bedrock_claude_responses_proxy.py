#!/usr/bin/env python3

import datetime as dt
import hmac
import json
import os
import subprocess
import sys
import tempfile
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 4141
DEFAULT_MAX_TOKENS = 8192
DEFAULT_REGION = "us-east-1"


def env(name, default=None):
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    return value


def get_region():
    return env("AWS_REGION") or env("AWS_DEFAULT_REGION") or DEFAULT_REGION


def get_proxy_token():
    return env("CODEX_BEDROCK_PROXY_TOKEN")


def get_default_model():
    return env("ANTHROPIC_BEDROCK_MODEL_ID", "global.anthropic.claude-opus-4-6-v1")


def normalize_model(model_name):
    sonnet_model = env("ANTHROPIC_BEDROCK_SONNET_MODEL_ID", "global.anthropic.claude-sonnet-4-6")
    aliases = {
        "opus": get_default_model(),
        "claude-opus-4-6": get_default_model(),
        "sonnet": sonnet_model,
        "claude-sonnet-4-6": sonnet_model,
    }
    return aliases.get(model_name, model_name)


def get_max_tokens(openai_request):
    for key in ("max_output_tokens", "max_tokens"):
        raw_value = openai_request.get(key)
        if raw_value is None:
            continue
        try:
            value = int(raw_value)
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"{key} must be an integer") from exc
        if value <= 0:
            raise RuntimeError(f"{key} must be greater than zero")
        return value

    raw_value = env("CODEX_BEDROCK_MAX_TOKENS")
    if not raw_value:
        return DEFAULT_MAX_TOKENS
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise RuntimeError("CODEX_BEDROCK_MAX_TOKENS must be an integer") from exc
    if value <= 0:
        raise RuntimeError("CODEX_BEDROCK_MAX_TOKENS must be greater than zero")
    return value


def run_command(command):
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        error_text = result.stderr.strip() or result.stdout.strip() or "command failed"
        raise RuntimeError(error_text)
    return result.stdout.strip()


def aws_command(*args):
    command = ["aws"]
    region = get_region()
    if region:
        command.extend(["--region", region])
    command.extend(args)
    return command


def get_aws_identity():
    output = run_command(
        aws_command("sts", "get-caller-identity", "--output", "json", "--no-cli-pager")
    )
    return json.loads(output)


def get_inference_profile(model_id):
    output = run_command(
        aws_command(
            "bedrock",
            "get-inference-profile",
            "--inference-profile-identifier",
            model_id,
            "--output",
            "json",
            "--no-cli-pager",
        )
    )
    return json.loads(output)


def error_payload(message, code="bad_request"):
    return {"error": {"message": message, "type": code}}


def extract_bedrock_content_blocks(content_items):
    blocks = []
    for item in content_items or []:
        item_type = item.get("type")
        if item_type in {"input_text", "output_text", "text"}:
            text = item.get("text", "")
            if text:
                blocks.append({"text": text})
        elif item_type == "input_image":
            blocks.append({"text": "[Unsupported image input omitted]"})
    return blocks


def serialize_tool_output(output):
    if isinstance(output, str):
        return output
    return json.dumps(output, ensure_ascii=False)


def parse_tool_result_content(output):
    if isinstance(output, (dict, list, int, float, bool)) or output is None:
        return [{"json": output}]

    if isinstance(output, str):
        stripped = output.strip()
        if not stripped:
            return [{"text": ""}]
        try:
            return [{"json": json.loads(stripped)}]
        except json.JSONDecodeError:
            return [{"text": output}]

    return [{"text": serialize_tool_output(output)}]


def translate_tool_choice(tool_choice):
    if tool_choice == "auto":
        return {"auto": {}}
    if tool_choice == "required":
        return {"any": {}}
    if tool_choice == "none":
        return None
    if isinstance(tool_choice, dict):
        if tool_choice.get("type") == "function" and tool_choice.get("name"):
            return {"tool": {"name": tool_choice["name"]}}
        if tool_choice.get("type") == "auto":
            return {"auto": {}}
    return {"auto": {}}


def convert_request_to_bedrock(openai_request):
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

    input_items = openai_request.get("input", [])
    if isinstance(input_items, str):
        input_items = [
            {
                "type": "message",
                "role": "user",
                "content": [{"type": "input_text", "text": input_items}],
            }
        ]

    for item in input_items:
        item_type = item.get("type")
        if item_type == "message":
            flush_pending()
            role = item.get("role", "user")
            content_blocks = extract_bedrock_content_blocks(item.get("content", []))
            if role in {"developer", "system"}:
                text_content = "\n\n".join(
                    block["text"] for block in content_blocks if block.get("text")
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
                    "toolUse": {
                        "toolUseId": item["call_id"],
                        "name": item["name"],
                        "input": parsed_arguments,
                    }
                }
            )
        elif item_type == "function_call_output":
            flush_assistant_tool_uses()
            pending_user_tool_results.append(
                {
                    "toolResult": {
                        "toolUseId": item["call_id"],
                        "content": parse_tool_result_content(item.get("output", "")),
                        "status": "success",
                    }
                }
            )

    flush_pending()

    if not messages:
        messages = [{"role": "user", "content": [{"text": ""}]}]

    bedrock_request = {
        "modelId": normalize_model(openai_request.get("model", "claude-opus-4-6")),
        "messages": messages,
        "inferenceConfig": {"maxTokens": get_max_tokens(openai_request)},
    }

    system_prompt = "\n\n".join(part for part in system_parts if part)
    if system_prompt:
        bedrock_request["system"] = [{"text": system_prompt}]

    tools = []
    for tool in openai_request.get("tools", []):
        if tool.get("type") != "function":
            continue
        tools.append(
            {
                "toolSpec": {
                    "name": tool["name"],
                    "description": tool.get("description", ""),
                    "inputSchema": {
                        "json": tool.get("parameters", {"type": "object", "properties": {}})
                    },
                }
            }
        )

    if tools:
        tool_config = {"tools": tools}
        translated_tool_choice = translate_tool_choice(openai_request.get("tool_choice"))
        if translated_tool_choice:
            tool_config["toolChoice"] = translated_tool_choice
        bedrock_request["toolConfig"] = tool_config

    return bedrock_request


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


def bedrock_response_to_openai(bedrock_response, requested_model):
    output_items = []
    content_blocks = bedrock_response.get("output", {}).get("message", {}).get("content", [])
    for block in content_blocks:
        if "text" in block:
            output_items.append(openai_message_item(f"msg_{uuid.uuid4().hex[:8]}", block["text"]))
        elif "toolUse" in block:
            tool_use = block["toolUse"]
            output_items.append(
                openai_function_call_item(
                    f"fc_{uuid.uuid4().hex[:8]}",
                    tool_use.get("toolUseId", f"call_{uuid.uuid4().hex[:8]}"),
                    tool_use.get("name", "tool"),
                    json.dumps(tool_use.get("input", {}), ensure_ascii=False),
                )
            )

    return {
        "id": f"resp_{uuid.uuid4().hex[:8]}",
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


def stream_openai_response(handler, bedrock_response, requested_model):
    response_id = f"resp_{uuid.uuid4().hex[:8]}"
    created_at = int(dt.datetime.now().timestamp())
    output_items = []

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

    content_blocks = bedrock_response.get("output", {}).get("message", {}).get("content", [])
    for output_index, block in enumerate(content_blocks):
        if "text" in block:
            item_id = f"msg_{uuid.uuid4().hex[:8]}"
            text = block["text"]
            final_item = openai_message_item(item_id, text)
            output_items.append(final_item)

            sse_event(
                handler,
                {
                    "type": "response.output_item.added",
                    "response_id": response_id,
                    "output_index": output_index,
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
                    "output_index": output_index,
                    "item_id": item_id,
                    "content_index": 0,
                    "part": {"type": "output_text", "text": "", "annotations": []},
                },
            )
            sse_event(
                handler,
                {
                    "type": "response.output_text.delta",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item_id": item_id,
                    "content_index": 0,
                    "delta": text,
                },
            )
            sse_event(
                handler,
                {
                    "type": "response.output_text.done",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item_id": item_id,
                    "content_index": 0,
                    "text": text,
                },
            )
            sse_event(
                handler,
                {
                    "type": "response.content_part.done",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item_id": item_id,
                    "content_index": 0,
                    "part": {"type": "output_text", "text": text, "annotations": []},
                },
            )
            sse_event(
                handler,
                {
                    "type": "response.output_item.done",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item": final_item,
                },
            )
        elif "toolUse" in block:
            tool_use = block["toolUse"]
            item_id = f"fc_{uuid.uuid4().hex[:8]}"
            arguments = json.dumps(tool_use.get("input", {}), ensure_ascii=False)
            final_item = openai_function_call_item(
                item_id,
                tool_use.get("toolUseId", f"call_{uuid.uuid4().hex[:8]}"),
                tool_use.get("name", "tool"),
                arguments,
            )
            output_items.append(final_item)

            sse_event(
                handler,
                {
                    "type": "response.output_item.added",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item": {
                        "id": item_id,
                        "type": "function_call",
                        "status": "in_progress",
                        "call_id": final_item["call_id"],
                        "name": final_item["name"],
                        "arguments": "",
                    },
                },
            )
            sse_event(
                handler,
                {
                    "type": "response.function_call_arguments.delta",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item_id": item_id,
                    "delta": arguments,
                },
            )
            sse_event(
                handler,
                {
                    "type": "response.function_call_arguments.done",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item_id": item_id,
                    "arguments": arguments,
                },
            )
            sse_event(
                handler,
                {
                    "type": "response.output_item.done",
                    "response_id": response_id,
                    "output_index": output_index,
                    "item": final_item,
                },
            )

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
    handler.wfile.write(b"data: [DONE]\n\n")
    handler.wfile.flush()


def invoke_bedrock_converse(bedrock_request):
    request_path = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
            json.dump(bedrock_request, handle, ensure_ascii=False)
            request_path = handle.name

        output = run_command(
            aws_command(
                "bedrock-runtime",
                "converse",
                "--cli-input-json",
                f"file://{request_path}",
                "--output",
                "json",
                "--no-cli-pager",
                "--cli-read-timeout",
                "300",
            )
        )
        return json.loads(output)
    finally:
        if request_path and os.path.exists(request_path):
            os.unlink(request_path)


class ProxyHandler(BaseHTTPRequestHandler):
    server_version = "RiskDeskCodexBedrockProxy/1.0"

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
            bedrock_request = convert_request_to_bedrock(openai_request)
            requested_model = openai_request.get("model", "claude-opus-4-6")
            stream = bool(openai_request.get("stream", True))
            bedrock_response = invoke_bedrock_converse(bedrock_request)

            if stream:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "keep-alive")
                self.end_headers()
                stream_openai_response(self, bedrock_response, requested_model)
                return

            self.respond_json(200, bedrock_response_to_openai(bedrock_response, requested_model))
        except json.JSONDecodeError:
            self.respond_json(400, error_payload("Request body must be valid JSON"))
        except RuntimeError as exc:
            self.respond_json(500, error_payload(str(exc), "server_error"))
        except Exception as exc:  # pragma: no cover - defensive path
            self.respond_json(500, error_payload(f"Unexpected proxy error: {exc}", "server_error"))

    def handle_health(self):
        issues = []

        if not get_proxy_token():
            issues.append("CODEX_BEDROCK_PROXY_TOKEN is not set")

        try:
            identity = get_aws_identity()
            aws_ok = True
        except RuntimeError as exc:
            identity = {}
            aws_ok = False
            issues.append(str(exc))

        try:
            profile = get_inference_profile(get_default_model())
            model_ok = True
            profile_status = profile.get("status")
        except RuntimeError as exc:
            profile = {}
            model_ok = False
            profile_status = None
            issues.append(str(exc))

        payload = {
            "ok": len(issues) == 0,
            "aws_ok": aws_ok,
            "aws_account": identity.get("Account"),
            "aws_arn": identity.get("Arn"),
            "region": get_region(),
            "default_model": get_default_model(),
            "inference_profile_ok": model_ok,
            "inference_profile_status": profile_status,
            "issues": issues,
        }
        self.respond_json(200 if payload["ok"] else 503, payload)

    def handle_models(self):
        if not self.authorize_request(optional=True):
            return
        models = [
            get_default_model(),
            env("ANTHROPIC_BEDROCK_SONNET_MODEL_ID", "global.anthropic.claude-sonnet-4-6"),
        ]
        data = [
            {
                "id": model_name,
                "object": "model",
                "created": 0,
                "owned_by": "aws-bedrock-anthropic",
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
                    "CODEX_BEDROCK_PROXY_TOKEN is not set on the proxy process.",
                    "server_error",
                ),
            )
            return False
        actual = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if not actual or not hmac.compare_digest(actual, expected):
            self.respond_json(401, error_payload("Invalid proxy bearer token", "unauthorized"))
            return False
        return True

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
    host = env("CODEX_BEDROCK_PROXY_HOST", DEFAULT_HOST)
    port = int(env("CODEX_BEDROCK_PROXY_PORT", str(DEFAULT_PORT)))
    server = ThreadingHTTPServer((host, port), ProxyHandler)
    print(
        f"Bedrock Claude Responses proxy listening on http://{host}:{port}",
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
