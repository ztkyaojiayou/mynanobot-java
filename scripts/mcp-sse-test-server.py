#!/usr/bin/env python3
"""SSE MCP test server — GET /sse + POST /messages (threaded)"""
import json, sys, queue
from http.server import HTTPServer, BaseHTTPRequestHandler
from functools import partial

TOOLS = [{"name": "echo", "description": "Echo", "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}}}]
pending = queue.Queue()

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream"); self.end_headers()
        self.wfile.write(b"data: {}\n\n"); self.wfile.flush()
        while True:
            try:
                r = pending.get(timeout=30)
                self.wfile.write(r.encode()); self.wfile.flush()
            except queue.Empty:
                self.wfile.write(b": hb\n\n"); self.wfile.flush()
            except: break

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        b = json.loads(self.rfile.read(n)) if n else {}
        m, rid = b.get("method",""), b.get("id")
        p = b.get("params",{}) or {}
        if m == "initialize":
            r = {"jsonrpc":"2.0","id":rid,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":True},"serverInfo":{"name":"sse-test","version":"1.0"}}}
        elif m == "tools/list":
            r = {"jsonrpc":"2.0","id":rid,"result":{"tools":TOOLS}}
        elif m == "tools/call":
            a = p.get("arguments",{}) or {}
            r = {"jsonrpc":"2.0","id":rid,"result":{"content":[{"type":"text","text":f"SSE ECHO: {a.get('message','')}"}]}}
        elif m == "notifications/initialized":
            self.send_response(200); self.end_headers(); return
        else:
            r = {"jsonrpc":"2.0","id":rid,"error":{"code":-32601,"message":f"Unknown:{m}"}}
        pending.put(f"data: {json.dumps(r)}\n\n")
        self.send_response(200); self.end_headers()

    def log_message(self,*a): pass

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
    # Use ThreadingHTTPServer for concurrent GET+POST
    import socketserver
    class Threaded(socketserver.ThreadingMixIn, HTTPServer): daemon_threads = True
    s = Threaded(("127.0.0.1", port), Handler)
    print(f"SSE on http://127.0.0.1:{port}", flush=True)
    s.serve_forever()
