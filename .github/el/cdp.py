import json, sys, urllib.request
import websocket
t = [x for x in json.load(urllib.request.urlopen("http://127.0.0.1:9222/json")) if x.get("type") == "page" and "127.0.0.1" in x.get("url", "")]
ws = websocket.create_connection(t[0]["webSocketDebuggerUrl"], timeout=30, suppress_origin=True)
ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": sys.argv[1], "awaitPromise": True, "returnByValue": True}}))
while True:
    r = json.loads(ws.recv())
    if r.get("id") == 1:
        print(json.dumps(r.get("result", r), ensure_ascii=False)[:300]); break
