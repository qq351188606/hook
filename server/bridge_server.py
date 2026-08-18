#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""美团众包订单Hook - Python桥接服务

架构:
  [美团众包] --Hook--> [Broadcaster] --HTTP--> [本服务]
                                                        |
                                                        v
                                                [脚本平台API]
                                                        | should_grab?
                                                        v
                                                   [ADB抢单]

用法:
  python bridge_server.py --port 8888
  手机: HTTP URL = http://<电脑IP>:8888/order
  ADB: adb connect <手机IP>:5555
"""
import json, time, threading, subprocess, argparse, os, sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

ADB_PATH = "adb"
GRAB_COOLDOWN = 3
CONFIG_FILE = "grab_config.json"

class LocalOrderFilter:
    def __init__(self, cfg=None):
        self.cfg = cfg or self._load_cfg()
    def _load_cfg(self):
        try:
            if os.path.exists(CONFIG_FILE):
                with open(CONFIG_FILE, "r", encoding="utf-8") as f: return json.load(f)
        except: pass
        return {"min_income": 8.0, "max_distance": 5000, "min_tip": 0, "include_keywords": [], "exclude_keywords": [], "merchant_filter": []}
    def should_grab(self, order):
        try:
            d = order.get("data", order)
            if not isinstance(d, dict): d = order
            cfg = self.cfg
            fee = float(d.get("deliveryFee", d.get("fee", d.get("income", d.get("price", 0))) or 0)
            tip = float(d.get("tip", d.get("gratuity", 0)) or 0)
            subsidy = float(d.get("subsidy", d.get("allowance", 0)) or 0)
            total = fee + tip + subsidy
            dist = int(d.get("deliveryDistance", d.get("distance", d.get("totalDistance", 0)) or 0)
            text = " ".join(str(d.get(f, "")) for f in ["poiName","shopName","poiAddress","pickupAddress","deliveryAddress","receiverName","goodsName","merchantName"] if d.get(f)).lower()
            for kw in cfg.get("exclude_keywords", []):
                if kw and kw.lower() in text: return False, ["排除关键词:"+kw]
            inc = cfg.get("include_keywords", [])
            if inc and not any(k and k.lower() in text for k in inc): return False, ["未命中白名单"]
            if total < cfg["min_income"]: return False, ["金额不足:"+str(round(total,2))]
            if dist > cfg["max_distance"]: return False, ["距离过远:"+str(dist)+"m"]
            return True, ["金额达标:"+str(round(total,2))+"元 距离:"+str(dist)+"m"]
        except Exception as e: return False, ["异常:"+str(e)]

class GrabExecutor:
    def __init__(self, adb="adb"):
        self.adb = adb
        self._lock = threading.Lock()
        self._last = 0
    def grab(self, order):
        with self._lock:
            if time.time() - self._last < GRAB_COOLDOWN: return False
            self._last = time.time()
        try:
            d = order.get("data", order) if isinstance(order, dict) else {}
            oid = d.get("orderId", d.get("waybillId", "?")) if isinstance(d, dict) else "?"
            print(f"[抢单] orderId={oid}")
            for cmd in ["shell input keyevent 4", "shell input tap 540 1850", "shell input tap 540 800", "shell input tap 540 1800"]:
                self._adb(cmd); time.sleep(0.5)
            os.makedirs("grab_records", exist_ok=True)
            with open(f"grab_records/{int(time.time())}_{oid}.json", "w", encoding="utf-8") as f:
                json.dump({"order_id": oid, "order": order}, f, ensure_ascii=False, indent=2)
            print("[抢单] ✓ 完成"); return True
        except Exception as e: print(f"[抢单] ✗ {e}"); return False
    def _adb(self, cmd):
        try: subprocess.run(f'"{self.adb}" {cmd}', shell=True, capture_output=True, timeout=10)
        except Exception as e: print(f"  ADB错误: {e}")
    def check_device(self):
        try:
            r = subprocess.run(f'"{self.adb}" devices', shell=True, capture_output=True, text=True, timeout=5)
            return [l.strip() for l in r.stdout.split("\n")[1:] if "device" in l.lower()]
        except: return []

class OrderPipeline:
    def __init__(self, flt, grab):
        self.flt = flt; self.grab = grab
        self.stats = {"total": 0, "grabbed": 0, "skipped": 0}
    def process(self, order):
        self.stats["total"] += 1
        ok, reasons = self.flt.should_grab(order)
        if ok:
            self.stats["grabbed"] += 1
            threading.Thread(target=self.grab.grab, args=(order,), daemon=True).start()
        else:
            self.stats["skipped"] += 1
        return {"should_grab": ok, "reason": reasons[0] if reasons else "", "source": "local"}

pipeline = None

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        p = urlparse(self.path)
        if p.path == "/status": self._r(200, {"status":"running","stats":pipeline.stats,"config":pipeline.flt.cfg})
        else: self._r(404, {"error":"not found"})
    def do_POST(self):
        p = urlparse(self.path); n = int(self.headers.get("Content-Length", 0))
        try: data = json.loads(self.rfile.read(n).decode("utf-8") or "{}")
        except: self._r(400, {"error":"bad json"}); return
        if p.path == "/order":
            meta = data.get("meta", {}); oj = data.get("order", "")
            try: o = json.loads(oj) if isinstance(oj, str) else oj
            except: o = {}
            if not isinstance(o, dict): self._r(400, {"error":"invalid order"}); return
            r = pipeline.process(o)
            print(f"[订单] tag={meta.get('tag')} grab={r['should_grab']} reason={r['reason']}")
            self._r(200, {"status":"ok", "should_grab":r["should_grab"], "reason":r["reason"]})
        elif p.path == "/grab":
            o = data.get("order", data)
            if isinstance(o, str):
                try: o = json.loads(o)
                except: o = {}
            threading.Thread(target=pipeline.grab.grab, args=(o,), daemon=True).start()
            self._r(200, {"ok":True})
        elif p.path == "/config":
            for k in ["min_income","max_distance","min_tip","include_keywords","exclude_keywords","merchant_filter"]:
                if k in data: pipeline.flt.cfg[k] = data[k]
            try:
                with open(CONFIG_FILE, "w", encoding="utf-8") as f: json.dump(pipeline.flt.cfg, f, ensure_ascii=False, indent=2)
            except: pass
            self._r(200, {"ok":True, "config":pipeline.flt.cfg})
        elif p.path == "/reset":
            pipeline.stats = {"total":0,"grabbed":0,"skipped":0}
            self._r(200, {"ok":True})
        else: self._r(404, {"error":"not found"})
    def _r(self, c, d):
        b = json.dumps(d, ensure_ascii=False).encode("utf-8")
        self.send_response(c); self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*"); self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)
    def do_OPTIONS(self): self._r(204, {})
    def log_message(self, *a): pass

def main():
    global pipeline, ADB_PATH, CONFIG_FILE
    ap = argparse.ArgumentParser(description="美团众包Hook桥接服务")
    ap.add_argument("--port", type=int, default=8888)
    ap.add_argument("--min-income", type=float, default=8.0)
    ap.add_argument("--max-distance", type=int, default=5000)
    ap.add_argument("--include", nargs="*", default=[])
    ap.add_argument("--exclude", nargs="*", default=[])
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--no-adb", action="store_true")
    ap.add_argument("--config", default=CONFIG_FILE)
    args = ap.parse_args()
    ADB_PATH = args.adb; CONFIG_FILE = args.config
    cfg = {"min_income":args.min_income, "max_distance":args.max_distance, "min_tip":0, "include_keywords":args.include, "exclude_keywords":args.exclude, "merchant_filter":[]}
    flt = LocalOrderFilter(cfg)
    grab = GrabExecutor(args.adb) if not args.no_adb else None
    pipeline = OrderPipeline(flt, grab)
    if not args.no_adb:
        devs = grab.check_device()
        print(f"[ADB] 设备: {devs if devs else '未检测到'}")
    srv = HTTPServer(("0.0.0.0", args.port), H)
    print(f"[HTTP] http://0.0.0.0:{args.port}")
    print(f"  POST /order  POST /grab  POST /config  GET /status  POST /reset")
    print("手机: HTTP URL = http://<电脑IP>:" + str(args.port) + "/order")
    print("等待订单... (Ctrl+C 退出)")
    try: srv.serve_forever()
    except KeyboardInterrupt: srv.server_close(); print("\n已退出")

if __name__ == "__main__": main()