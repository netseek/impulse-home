import json
import urllib.request
import websocket

pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json"))
ws = websocket.create_connection(pages[0]["webSocketDebuggerUrl"])
expr = r"""(function(){
  var v = document.createElement('video');
  var types = [
    'video/mp4',
    'video/mp4; codecs="avc1.42E01E"',
    'video/mp4; codecs="avc1.4D401E"',
    'video/mp4; codecs="avc1.64001E"',
    'video/mp4; codecs="avc1.42E01E, mp4a.40.2"',
    'video/webm',
    'video/webm; codecs="vp8"',
    'video/webm; codecs="vp9"',
    'video/x-matroska'
  ];
  var can = {};
  types.forEach(function(t){ can[t] = v.canPlayType(t); });
  var mc = navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo
    ? 'present' : 'absent';
  return {
    ua: navigator.userAgent,
    canPlayType: can,
    mediaCapabilities: mc,
    w: window.innerWidth,
    h: window.innerHeight,
    dpr: window.devicePixelRatio
  };
})()"""
ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": expr, "returnByValue": True}}))
while True:
    msg = json.loads(ws.recv())
    if msg.get("id") == 1:
        print(json.dumps(msg.get("result"), indent=2))
        break
ws.close()
