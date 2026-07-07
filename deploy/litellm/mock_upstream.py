"""TEST-ONLY 最小 OpenAI 兼容上游，供 deploy/smoke-failover.sh 使用。

每个实例通过环境变量 UPSTREAM_ID 标识自己，并把该标识回写进
/v1/chat/completions 响应的 message.content（reply-from-<id>），
好让 smoke 脚本据此判断到底是主上游还是 fallback 上游服务了本次请求。
不是生产组件、不进默认 CI。
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

UPSTREAM_ID = os.environ.get("UPSTREAM_ID", "unknown")


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.rstrip("/")
        if path.endswith("/models"):
            self._send(200, {"object": "list", "data": [{"id": f"mock-{UPSTREAM_ID}", "object": "model"}]})
        else:
            self._send(200, {"status": "ok", "upstream": UPSTREAM_ID})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)
        self._send(200, {
            "id": f"chatcmpl-{UPSTREAM_ID}",
            "object": "chat.completion",
            "created": 0,
            "model": f"mock-{UPSTREAM_ID}",
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": f"reply-from-{UPSTREAM_ID}"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
