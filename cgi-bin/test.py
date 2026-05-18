#!/usr/bin/env python3
import json
import os
import sys

body = sys.stdin.buffer.read()

print("Content-Type: application/json")
print()
print(json.dumps({
    "method": os.environ.get("REQUEST_METHOD", ""),
    "path_info": os.environ.get("PATH_INFO", ""),
    "query_string": os.environ.get("QUERY_STRING", ""),
    "content_length": len(body),
    "body": body.decode("utf-8", errors="replace")
}))
