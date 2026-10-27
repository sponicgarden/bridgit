#!/usr/bin/env python3
"""Bridget device acceptance checks (spec TST-03). Run through tools/acceptance.sh.

Runs from the Mac against the debug build on the Pixel, over Tailscale (HTTP)
and ADB (setup hook). It creates its own test agents through the debug-only
setup hook, redeems their pairing codes like an agent would, runs every check,
writes a results file, and removes the test agents.

Keys stay in this process's memory only. They are never printed, written to a
file or passed on a command line. The results file never contains them.

Each check is PASS, FAIL or UNVERIFIED (could not be run, e.g. the phone was
locked for a camera check). UNVERIFIED is never counted as a pass.
"""
import argparse, concurrent.futures, datetime, json, os, re, secrets, subprocess, sys, time
import urllib.error, urllib.request

KEY_RE = re.compile(r"brg_[A-Za-z0-9_-]{43}")


class Ctx:
    def __init__(self, a):
        self.serial, self.host, self.port, self.pkg = a.device, a.host, a.port, a.pkg
        self.base = f"http://{self.host}:{self.port}/v1"
        self.results = []      # (id, name, status, note)
        self.keys = {}         # agent name -> key (memory only)
        self.suffix = secrets.token_hex(2)

    # ---- device -----------------------------------------------------------------------------
    def adb(self, *args, check=False):
        p = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=60)
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args[:2])} failed: {p.stderr.strip()[:200]}")
        return p.stdout

    def hook(self, **extras):
        """Sends the debug-only setup broadcast. Returns the pairing code (if any)."""
        cmd = ["shell", "am", "broadcast", "-f", "0x20", "-a", "net.finleg.bridget.debug.SETUP",
               "-n", f"{self.pkg}/net.finleg.bridget.DebugSetupReceiver"]
        for k, v in extras.items():
            # adb joins arguments into one shell command: quote values so spaces survive.
            cmd += (["--ez", k, "true" if v else "false"] if isinstance(v, bool) else ["--es", k, "'" + str(v).replace("'", "'\\''") + "'"])
        out = self.adb(*cmd)
        m = re.search(r'data="(\d{6})"', out)
        return m.group(1) if m else None

    def locked(self):
        out = self.adb("shell", "dumpsys", "window")
        return "isKeyguardShowing=true" in out or "mDreamingLockscreen=true" in out

    def run_as(self, *args):
        return subprocess.run(["adb", "-s", self.serial, "exec-out", "run-as", self.pkg, *args],
                              capture_output=True, timeout=60).stdout

    # ---- HTTP -------------------------------------------------------------------------------
    def call(self, method, path, agent=None, body=None, headers=None, timeout=70, raw_key=None):
        h = dict(headers or {})
        if agent:
            h["Authorization"] = "Bearer " + self.keys[agent]
        if raw_key is not None:
            h["Authorization"] = "Bearer " + raw_key
        data = None
        if body is not None:
            data = json.dumps(body).encode() if not isinstance(body, (bytes, str)) else (body if isinstance(body, bytes) else body.encode())
            h.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return Resp(r.status, dict(r.headers), r.read(), time.time() - t0)
        except urllib.error.HTTPError as e:
            return Resp(e.code, dict(e.headers), e.read(), time.time() - t0)

    def pair(self, name, connection="direct"):
        code = self.hook(agent=name, connection=connection)
        if not code:
            raise RuntimeError(f"setup hook returned no pairing code for {name}")
        r = self.call("POST", "/pair", body={"code": code})
        if r.status != 200:
            raise RuntimeError(f"pairing {name} failed: HTTP {r.status} {r.json().get('error')}")
        self.keys[name] = r.json()["key"]

    def wait_auth_window(self):
        """Waits until this address may fail a sign-in again (SEC-06 allows 5 per minute)."""
        r = self.call("GET", "/health", raw_key="brg_" + secrets.token_urlsafe(32)[:43])
        if r.status == 429:
            time.sleep(int(r.json().get("retry_after_s", 60)) + 1)
        else:
            time.sleep(61)  # that probe was a failure too; let the window empty fully

    def wait_logged(self, agent, request_id, timeout=10):
        """The log is written asynchronously: poll until the entry is readable."""
        end = time.time() + timeout
        while time.time() < end:
            r = self.call("GET", "/log?agent=me&limit=50", agent)
            if r.status == 200 and any(e.get("request_id") == request_id for e in r.json()["entries"]):
                return True
            time.sleep(0.5)
        return False

    # ---- results ----------------------------------------------------------------------------
    def check(self, cid, name):
        def deco(fn):
            try:
                out = fn()
                status, note = (out if isinstance(out, tuple) else ("PASS", out or ""))
            except Unverified as u:
                status, note = "UNVERIFIED", str(u)
            except AssertionError as e:
                status, note = "FAIL", str(e) or "assertion failed"
            except Exception as e:
                status, note = "FAIL", f"{type(e).__name__}: {e}"
            note = KEY_RE.sub("brg_[redacted]", note)
            self.results.append((cid, name, status, note))
            print(f"  {status:<10} {cid:<5} {name}" + (f"  ({note})" if note else ""), flush=True)
            return fn
        return deco


class Resp:
    def __init__(self, status, headers, body, secs):
        self.status, self.headers, self.body, self.secs = status, {k.lower(): v for k, v in headers.items()}, body, secs

    def json(self):
        try:
            return json.loads(self.body)
        except Exception:
            return {}


class Unverified(Exception):
    pass


def expect(r, status, code=None):
    assert r.status == status, f"HTTP {r.status} (want {status}) {r.json().get('error', '')} {r.json().get('message', '')[:80]}"
    if code:
        assert r.json().get("error") == code, f"error {r.json().get('error')} (want {code})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="pixel-10-pro-xl.tail9c9221.ts.net:5555")
    ap.add_argument("--host", default="100.94.27.79")
    ap.add_argument("--port", type=int, default=8735)
    ap.add_argument("--pkg", default="net.finleg.bridget.debug")
    ap.add_argument("--out", default=None, help="results file (default docs/acceptance/<date>-<version>-<commit>.md)")
    ap.add_argument("--skip-captures", action="store_true")
    ap.add_argument("--apk", default=None, help="debug APK; reinstalls it to test the MY_PACKAGE_REPLACED restart (A29)")
    a = ap.parse_args()
    c = Ctx(a)
    T, P = f"Accept{c.suffix}", f"Peer{c.suffix}"
    started = datetime.datetime.now().astimezone()

    print(f"Bridget acceptance: {c.base} via adb {c.serial}")
    # Launching the activity once takes a fresh install out of Android's "stopped" state,
    # so the debug hook's broadcast is delivered.
    c.adb("shell", "am", "start", "-n", f"{c.pkg}/net.finleg.bridget.ui.MainActivity")
    time.sleep(2)
    c.hook(camera=True, mic=True, gallery=True, log_visibility="all")
    c.pair(T)
    c.pair(P)
    time.sleep(2)
    h = c.call("GET", "/health", T).json()
    version = h.get("app_version", "?")
    commit = subprocess.run(["git", "rev-parse", "--short=12", "HEAD"], capture_output=True, text=True).stdout.strip()
    print(f"  build {c.pkg} {version}, repo HEAD {commit}\n")

    @c.check("A01", "health answers with the calling agent, version and port")
    def _():
        r = c.call("GET", "/health", T); expect(r, 200); j = r.json()
        assert j["agent"] == T and j["port"] == c.port, j
        assert re.match(r"^1\.[0-9]+\.[0-9]+$", j["app_version"]), j["app_version"]
        assert r.headers.get("x-request-id") == j["request_id"], "X-Request-Id differs from body"
        return f"{r.secs*1000:.0f} ms, base_url {j['base_url']}"

    @c.check("A02", "no key / malformed key / unknown brg_ key / old 64-hex key -> 401")
    def _():
        expect(c.call("GET", "/health"), 401, "unauthorized")
        expect(c.call("GET", "/health", raw_key="not-a-key"), 401, "unauthorized")
        expect(c.call("GET", "/health", raw_key="brg_" + "A" * 43), 401, "unauthorized")
        expect(c.call("GET", "/health", raw_key=secrets.token_hex(32)), 401, "unauthorized")

    @c.check("A03", "listener is bound only to Tailscale (Wi-Fi address refused)")
    def _():
        ip = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/\d+ .*wlan0", c.adb("shell", "ip", "-4", "addr", "show", "wlan0"))
        if not ip:
            raise Unverified("phone has no Wi-Fi address")
        import socket
        s = socket.socket(); s.settimeout(3)
        try:
            s.connect((ip.group(1), c.port)); s.close()
            assert False, f"port {c.port} answered on Wi-Fi {ip.group(1)}"
        except (ConnectionRefusedError, socket.timeout, OSError) as e:
            if isinstance(e, AssertionError):
                raise
            return f"Wi-Fi {ip.group(1)}:{c.port} -> {type(e).__name__}"

    @c.check("A04", "capabilities: enabled / ready / blocked_reason per module")
    def _():
        r = c.call("GET", "/capabilities", T); expect(r, 200); caps = r.json()["capabilities"]
        for k in ("camera", "mic", "gallery"):
            assert set(caps[k]) >= {"enabled", "ready", "blocked_reason", "details"}, caps[k]
        assert caps["gallery"]["details"]["access_mode"] in ("full", "selected", "none")
        return ", ".join(f"{k}: {'ready' if v['ready'] else v['blocked_reason']}" for k, v in caps.items())

    @c.check("A05", "guide is Markdown, names the agent, lists endpoints and errors")
    def _():
        r = c.call("GET", "/guide", T); expect(r, 200)
        t = r.body.decode()
        assert r.headers["content-type"].startswith("text/markdown")
        assert f"# Bridget guide for {T}" in t and "/v1/image/last" in t and "capability_disabled" in t
        return f"{len(t)} chars"

    @c.check("A06", "openapi.json is OpenAPI 3.1 with every endpoint")
    def _():
        r = c.call("GET", "/openapi.json", T); expect(r, 200); j = r.json()
        assert j["openapi"] == "3.1.0"
        for p in ("/v1/health", "/v1/log", "/v1/log/{id}", "/v1/image/last", "/v1/camera/snapshot", "/v1/tools/bridget"):
            assert p in j["paths"], p
        return f"{len(j['paths'])} paths"

    @c.check("A07", "GET /v1/tools/bridget needs no key and matches tools/bridget")
    def _():
        r = c.call("GET", "/tools/bridget"); expect(r, 200)
        here = open(os.path.join(os.path.dirname(__file__), "bridget"), "rb").read()
        assert r.body == here, "served helper differs from tools/bridget"
        return f"{len(r.body)} bytes"

    @c.check("A08", "switch off -> 403 capability_disabled (camera)")
    def _():
        c.hook(camera=False); time.sleep(0.5)
        try:
            expect(c.call("POST", "/camera/snapshot", T, {"camera": "rear"}), 403, "capability_disabled")
        finally:
            c.hook(camera=True)

    @c.check("A09", "bad values -> 422 invalid_parameter (mic 121 s, camera 'side', size 'huge', log limit 0)")
    def _():
        expect(c.call("POST", "/mic/record", T, {"duration_seconds": 121}), 422, "invalid_parameter")
        expect(c.call("POST", "/camera/snapshot", T, {"camera": "side"}), 422, "invalid_parameter")
        expect(c.call("GET", "/image/last?size=huge", T), 422, "invalid_parameter")
        expect(c.call("GET", "/log?limit=0", T), 422, "invalid_parameter")
        expect(c.call("POST", "/log", T, b"not json"), 400, "bad_request")

    gallery_ok = c.call("GET", "/capabilities", T).json()["capabilities"]["gallery"]["ready"]

    @c.check("A10", "image/last: agent size by default, thumb preset, raw=1 bytes with X-Bridget headers")
    def _():
        if not gallery_ok:
            raise Unverified("no photo permission on the phone")
        r = c.call("GET", "/image/last", T); expect(r, 200); j = r.json()
        assert max(j["width_px"], j["height_px"]) <= 1568 and j["format"] == "jpeg", j.get("width_px")
        r2 = c.call("GET", "/image/last?size=thumb", T); expect(r2, 200)
        assert max(r2.json()["width_px"], r2.json()["height_px"]) <= 512
        r3 = c.call("GET", "/image/last?raw=1&type=any", T); expect(r3, 200)
        assert r3.body[:2] == b"\xff\xd8" and "x-bridget-id" in r3.headers and "x-bridget-width" in r3.headers
        return f"{j['width_px']}x{j['height_px']}, {j['size_bytes']//1024} KB, {r.secs:.2f} s; thumb {r2.json()['width_px']}px; raw {len(r3.body)//1024} KB"

    @c.check("A11", "image/last since=now&wait=3 waits and answers new:false")
    def _():
        if not gallery_ok:
            raise Unverified("no photo permission on the phone")
        now = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        r = c.call("GET", "/image/last?since=" + urllib.request.quote(now) + "&wait=3", T); expect(r, 200)
        assert r.json().get("new") is False and r.secs >= 2.5, (r.json(), r.secs)
        return f"answered after {r.secs:.1f} s"

    @c.check("A12", "gallery recent + photo with size preset")
    def _():
        if not gallery_ok:
            raise Unverified("no photo permission on the phone")
        r = c.call("GET", "/gallery/recent?limit=3", T); expect(r, 200)
        photos = r.json()["photos"]
        if not photos:
            raise Unverified("gallery is empty")
        r2 = c.call("GET", f"/gallery/photo/{photos[0]['id']}?size=thumb", T); expect(r2, 200)
        assert max(r2.json()["width_px"], r2.json()["height_px"]) <= 512
        expect(c.call("GET", "/gallery/photo/999999999999", T), 404, "photo_not_found")

    @c.check("A13", "every request is logged under the agent (request_id matches)")
    def _():
        r = c.call("GET", "/health", T); rid = r.json()["request_id"]
        assert c.wait_logged(T, rid), "health request not in the log within 10 s"
        e = [x for x in c.call("GET", "/log?agent=me&limit=50", T).json()["entries"] if x["request_id"] == rid][0]
        for k in ("id", "ts", "channel", "level", "kind", "agent", "agent_id", "source_ip", "method", "endpoint",
                  "params", "http", "result", "bytes_in", "bytes_out", "duration_ms", "request_id", "message", "data"):
            assert k in e, f"entry lacks {k}"
        assert e["agent"] == T and e["endpoint"] == "/v1/health" and e["http"] == 200
        return f"entry {e['id']}"

    @c.check("A14", "log entry by id, stats, export (jsonl and csv), cursor paging")
    def _():
        r = c.call("GET", "/log?agent=me&limit=2", T); expect(r, 200); j = r.json()
        assert len(j["entries"]) == 2 and j["next_cursor"], j.get("next_cursor")
        r2 = c.call("GET", "/log?agent=me&limit=2&cursor=" + j["next_cursor"], T); expect(r2, 200)
        assert r2.json()["entries"][0]["id"] < j["entries"][-1]["id"], "cursor did not page"
        expect(c.call("GET", f"/log/{j['entries'][0]['id']}", T), 200)
        s = c.call("GET", "/log/stats", T); expect(s, 200)
        assert any(x["key"] == T for x in s.json()["by_agent"])
        ej = c.call("GET", "/log/export?format=jsonl&agent=me", T); expect(ej, 200)
        lines = [json.loads(l) for l in ej.body.decode().splitlines() if l]
        ec = c.call("GET", "/log/export?format=csv&agent=me", T); expect(ec, 200)
        assert ec.body.decode().splitlines()[0].startswith("id,ts,channel")
        return f"{len(lines)} jsonl lines"

    @c.check("A15", "POST /v1/log note: written to the agent channel; 1 KB limit")
    def _():
        msg = f"acceptance note {secrets.token_hex(3)}"
        r = c.call("POST", "/log", T, {"message": msg, "data": {"k": 1}}); expect(r, 201)
        end = time.time() + 10
        while time.time() < end:
            es = c.call("GET", "/log?channel=agent&agent=me&limit=5", T).json()["entries"]
            if any(e["message"] == msg for e in es):
                break
            time.sleep(0.5)
        else:
            assert False, "note not found"
        expect(c.call("POST", "/log", T, {"message": "x" * 1100}), 422, "invalid_parameter")

    @c.check("A16", "tail mode returns as soon as a new matching entry arrives")
    def _():
        latest = c.call("GET", "/log?limit=1", T).json()["latest_id"]
        with concurrent.futures.ThreadPoolExecutor(1) as ex:
            fut = ex.submit(c.call, "GET", f"/log?order=asc&since={latest}&wait=20&agent={P}", T)
            time.sleep(1.5)
            c.call("GET", "/health", P)
            r = fut.result(30)
        expect(r, 200)
        assert r.json()["entries"] and r.secs < 10, (len(r.json()["entries"]), r.secs)
        return f"returned after {r.secs:.1f} s"

    @c.check("A17", "visibility 'own': a normal agent sees only its own entries; a developer agent sees all")
    def _():
        c.hook(log_visibility="own"); time.sleep(0.5)
        try:
            es = c.call("GET", "/log?limit=200", P).json()["entries"]
            others = [e for e in es if e.get("agent_id") and e["agent"] != P]
            assert not others, f"{len(others)} entries of other agents visible"
            sys_entries = [e for e in es if e.get("agent_id") is None]
            assert not sys_entries, "Bridget's own entries visible"
            tid = c.call("GET", "/log?agent=me&limit=1", T).json()["entries"][0]["id"]
            expect(c.call("GET", f"/log/{tid}", P), 404, "not_found")
            ex = c.call("GET", "/log/export", P).body.decode()
            assert T not in ex, "export leaks another agent"
            c.hook(developer=f"{P}=true"); time.sleep(0.5)
            es = c.call("GET", f"/log?agent={T}&limit=5", P).json()["entries"]
            assert es, "developer agent can't see others"
        finally:
            c.hook(log_visibility="all", developer=f"{P}=false")

    @c.check("A18", "/v1/audit alias returns LOG-01 entries (no 1.2 field names)")
    def _():
        r = c.call("GET", "/audit?limit=5", T); expect(r, 200)
        e = r.json()["entries"][0]
        assert "endpoint" in e and "http" in e and not ({"source", "bytes", "detail"} & set(e)), sorted(e)
        assert all(x["channel"] == "audit" for x in r.json()["entries"])

    @c.check("A19", "Idempotency-Key: a repeat returns the first result without doing it twice")
    def _():
        k = secrets.token_hex(8); msg = f"idem {k}"
        r1 = c.call("POST", "/log", T, {"message": msg}, {"Idempotency-Key": k}); expect(r1, 201)
        r2 = c.call("POST", "/log", T, {"message": msg}, {"Idempotency-Key": k}); expect(r2, 201)
        assert r2.headers.get("idempotent-replay") == "true", "no replay header"
        assert r2.json().get("idempotent_replay_of") == r1.json()["request_id"], "replay doesn't point at the first request"
        assert r2.json()["request_id"] == r2.headers.get("x-request-id") != r1.json()["request_id"], "replay request_id must match its own header"
        time.sleep(2)
        n = sum(1 for e in c.call("GET", "/log?channel=agent&agent=me&limit=50", T).json()["entries"] if e["message"] == msg)
        assert n == 1, f"note written {n} times"
        expect(c.call("POST", "/log", T, {"message": "x"}, {"Idempotency-Key": "bad key!"}), 422, "invalid_parameter")

    @c.check("A20", "too_many_waits: a 5th concurrent wait from one agent is refused")
    def _():
        latest = c.call("GET", "/log?limit=1", T).json()["latest_id"]
        with concurrent.futures.ThreadPoolExecutor(4) as ex:
            futs = [ex.submit(c.call, "GET", f"/log?order=asc&since={latest + 10**9}&wait=6", T) for _ in range(4)]
            time.sleep(1.5)
            r = c.call("GET", f"/log?order=asc&since={latest + 10**9}&wait=2", T)
            [f.result(30) for f in futs]
        expect(r, 429, "too_many_waits")

    @c.check("A21", "rotated key -> 401 reason key_rotated; new key works")
    def _():
        old = c.keys[P]
        code = c.hook(rotate=P)
        r = c.call("POST", "/pair", body={"code": code}); expect(r, 200)
        c.keys[P] = r.json()["key"]
        o = c.call("GET", "/health", raw_key=old); expect(o, 401, "unauthorized")
        assert o.json().get("reason") == "key_rotated", o.json()
        expect(c.call("GET", "/health", P), 200)

    @c.check("A22", "pairing: wrong code -> 401, right code -> key once, then 403 pairing_closed")
    def _():
        # Earlier checks used this address's failed sign-ins; pairing obeys the same block (SEC-06).
        c.wait_auth_window()
        code = c.hook(rotate=T)
        wrong = "000000" if code != "000000" else "111111"
        expect(c.call("POST", "/pair", body={"code": wrong}), 401, "unauthorized")
        r = c.call("POST", "/pair", body={"code": code}); expect(r, 200)
        c.keys[T] = r.json()["key"]
        expect(c.call("POST", "/pair", body={"code": code}), 403, "pairing_closed")
        expect(c.call("GET", "/health", T), 200)

    @c.check("V01", "API revisions: every reply has X-Bridget-API-Revision; a new agent sees what's new until it reads /v1/changes")
    def _():
        r = c.call("GET", "/health", P); expect(r, 200)
        rev = int(r.headers.get("x-bridget-api-revision", "0"))
        assert rev >= 3 and r.json()["api_revision"] == rev, (rev, r.json().get("api_revision"))
        assert r.headers.get("x-bridget-whats-new", "").startswith("/v1/changes?since="), "no X-Bridget-Whats-New for a new agent"
        assert r.json()["whats_new"] and r.json()["links"]["changes"] == "/v1/changes", r.json().get("whats_new")
        ch = c.call("GET", "/changes?since=0", P); expect(ch, 200)
        assert [x["revision"] for x in ch.json()["changes"]] == list(range(1, rev + 1)), ch.json()["changes"]
        r2 = c.call("GET", "/health", P)
        assert r2.json()["whats_new"] is None and "x-bridget-whats-new" not in r2.headers, "still nagging after reading the changes"
        return f"revision {rev}"

    # ---- 1.5: interactions (8.13, D26) ----------------------------------------------------------------
    def question(agent, title, **extra):
        body = {"kind": "question", "title": title, "controls": [
            {"type": "button", "id": "yes", "label": "Yes", "style": "primary"}, {"type": "button", "id": "no", "label": "No"}]}
        body.update(extra)
        return c.call("POST", "/interactions", agent, body)

    @c.check("I01", "a question reaches the phone; the waiting agent gets the answer about 1 s after the tap (stand-in tap)")
    def _():
        c.hook(interactions=True); time.sleep(0.5)
        r = question(T, "Acceptance: pay the test invoice?"); expect(r, 201)
        iid = r.json()["interaction_id"]
        with concurrent.futures.ThreadPoolExecutor(1) as ex:
            fut = ex.submit(c.call, "GET", f"/interactions/{iid}?wait=30", T)
            time.sleep(2)
            t_tap = time.time()
            c.hook(answer=f"{iid}:yes")
            w = fut.result(40)
        expect(w, 200)
        j = w.json(); lag = time.time() - t_tap
        assert j["status"] == "answered" and j["answer"]["button"] == "yes", j
        return f"answer arrived {lag:.2f} s after the tap (includes the adb broadcast)"

    @c.check("I02", "ownership: another agent can't read or cancel it (404); bad links and notice buttons are refused")
    def _():
        r = question(T, "Acceptance: ownership"); expect(r, 201); iid = r.json()["interaction_id"]
        expect(c.call("GET", f"/interactions/{iid}", P), 404, "not_found")
        expect(c.call("DELETE", f"/interactions/{iid}", P), 404, "not_found")
        assert all(x["interaction_id"] != iid for x in c.call("GET", "/interactions", P).json()["interactions"])
        expect(c.call("DELETE", f"/interactions/{iid}", T), 200)
        expect(c.call("POST", "/interactions", T, {"kind": "question", "title": "x", "controls": [
            {"type": "button", "id": "a", "label": "A"}, {"type": "link", "id": "l", "label": "L", "url": "javascript:alert(1)"}]}), 422, "invalid_parameter")
        expect(c.call("POST", "/interactions", T, {"kind": "notice", "title": "x", "controls": [{"type": "button", "id": "a", "label": "A"}]}), 422, "invalid_parameter")

    @c.check("I03", "Rahul closes it: the agent sees dismissed and gets an interaction.dismissed event")
    def _():
        cur = c.call("GET", "/events?types=interaction.dismissed", T).json()["next_cursor"]
        r = question(T, "Acceptance: close me"); expect(r, 201); iid = r.json()["interaction_id"]
        c.hook(dismiss=str(iid))
        g = c.call("GET", f"/interactions/{iid}?wait=10", T).json()
        assert g["status"] == "dismissed", g
        ev = c.call("GET", f"/events?since={cur}&wait=10&types=interaction.dismissed", T, timeout=20).json()
        assert any(e["data"]["interaction_id"] == iid for e in ev["events"]), ev

    @c.check("I04", "at most 5 open questions per agent (429 too_many_open)")
    def _():
        ids = []
        codes = []
        for n in range(6):
            r = question(P, f"Acceptance: open {n}"); codes.append(r.status)
            if r.status == 201: ids.append(r.json()["interaction_id"])
        for i in ids: c.call("DELETE", f"/interactions/{i}", P)
        assert codes[-1] == 429 and codes.count(201) == 5, codes
        return f"statuses {codes}"

    # ---- 1.5: location, speaker, City tour (D30) ------------------------------------------------------
    @c.check("L01", "location: a fix with latitude, longitude, altitude, accuracy, and the compass heading")
    def _():
        c.hook(location=True); time.sleep(0.5)
        r = c.call("GET", "/location?fresh=false", T, timeout=40)
        if r.status == 504:
            raise Unverified("no location fix available on the phone")
        expect(r, 200); j = r.json()
        assert -90 <= j["latitude"] <= 90 and -180 <= j["longitude"] <= 180 and j["precision"] in ("precise", "approximate"), j
        comp = j.get("compass")
        return f"accuracy {j.get('accuracy_m')} m, altitude {j.get('altitude_m')}, heading " + (f"{comp['azimuth_deg']}° {comp['direction']}" if comp else "none")

    @c.check("S01", "speaker: say queues speech on the phone; unsafe audio URLs are refused; stop clears it")
    def _():
        c.hook(speaker=True, speaker_ceiling="30"); time.sleep(0.5)
        for bad in ("http://example.com/a.mp3", "https://192.168.1.1/a.mp3", "https://user:pw@example.com/a.mp3", "https://localhost/a.mp3"):
            expect(c.call("POST", "/speaker/play", T, {"url": bad}, timeout=40), 422, "invalid_parameter")
        r = c.call("POST", "/speaker/say", T, {"text": "Bridget test."}, timeout=40)
        expect(r, 200)
        st = c.call("GET", "/speaker", T); expect(st, 200)
        expect(c.call("DELETE", "/speaker", T), 200)
        return f"say -> {r.json()['status']}, quiet mode {st.json()['quiet']}"

    @c.check("TOUR", "City tour: start sends tour.started, a tick carries location + compass, a question arrives, the agent ends it")
    def _():
        c.hook(location=True, speaker=True); time.sleep(0.5)
        start = c.call("GET", "/events", T).json()
        cur = start["events"][-1]["id"] if start["events"] else "0"
        c.hook(tour_start=f"{T}:history,architecture:30")
        t = c.call("GET", "/tour", T); expect(t, 200)
        tour = t.json()["tour"]; assert tour and tour["interval_s"] == 30, t.json()
        got, deadline = {}, time.time() + 45
        while time.time() < deadline and not {"tour.started", "tour.tick"} <= set(got):
            ev = c.call("GET", f"/events?since={cur}&wait=20", T, timeout=30).json()
            for e in ev["events"]:
                got.setdefault(e["type"], e)
            cur = ev["next_cursor"]
        assert "tour.started" in got and "tour.tick" in got, sorted(got)
        tick = got["tour.tick"]["data"]
        c.hook(tour_ask="What is that tower?")
        ev = c.call("GET", f"/events?since={cur}&wait=15&types=tour.question", T, timeout=25).json()
        assert ev["events"] and ev["events"][0]["data"]["question"] == "What is that tower?", ev
        expect(c.call("POST", "/speaker/say", T, {"text": "That is the town hall.", "tour_id": tour["tour_id"], "urgent": True}, timeout=40), 200)
        c.call("DELETE", "/speaker", T)
        expect(c.call("DELETE", "/tour", T), 200)
        end = c.call("GET", f"/events?since={ev['next_cursor']}&wait=10&types=tour.ended", T, timeout=20).json()
        assert end["events"], "no tour.ended"
        loc = tick.get("location") or {}
        return f"tick: lat {loc.get('latitude')}, heading {(tick.get('compass') or {}).get('direction')}, {len(tick['new_photos'])} new photos"

    # ---- P1b: feedback (section 11) ----------------------------------------------------------------
    fb = {}

    @c.check("F01", "any agent files a report (201); non-developers can't read reports (403 developer_only)")
    def _():
        r = c.call("POST", "/feedback", T, {"kind": "bug", "description": "acceptance: image/last was slow"}); expect(r, 201)
        fb["id"] = r.json()["id"]
        assert r.json()["number"].startswith("#") and r.json()["status"] == "new"
        f = c.call("POST", "/feedback", T, {"kind": "feature", "description": "acceptance: feature"}); expect(f, 201)
        assert f.json()["no_autofix"] is True, "features must always be held for review"
        for path in ("/feedback", f"/feedback/{fb['id']}"):
            x = c.call("GET", path, T); expect(x, 403, "forbidden")
            assert x.json().get("reason") == "developer_only", x.json()
        expect(c.call("POST", f"/feedback/{fb['id']}/status", T, {"status": "fixed"}), 403, "forbidden")
        expect(c.call("POST", "/feedback", T, {"kind": "bug", "description": "x", "images": [{"data_base64": "AAAA"}]}), 403, "forbidden")
        expect(c.call("POST", "/feedback", T, {"kind": "nope", "description": "x"}), 422, "invalid_parameter")
        expect(c.call("POST", "/feedback", T, {"kind": "bug", "description": "  "}), 422, "invalid_parameter")
        return f"report {r.json()['number']}"

    @c.check("F02", "a developer agent lists and reads a report; the first read marks it seen; Bridget attaches context and log")
    def _():
        c.hook(developer=f"{P}=true"); time.sleep(0.5)
        lst = c.call("GET", "/feedback?status=new", P); expect(lst, 200)
        assert any(x["id"] == fb["id"] for x in lst.json()["reports"]), "new report not listed"
        d = c.call("GET", f"/feedback/{fb['id']}", P); expect(d, 200); j = d.json()
        assert j["status"] == "seen" and j["capture_source"] == "agent" and j["filed_by"] == T, (j["status"], j["capture_source"])
        assert re.match(r"^1\.\d+\.\d+", j["context"]["app_version"]) and len(j["log"]) > 0, "context or log missing"
        assert [h["status"] for h in j["history"]] == ["new", "seen"], j["history"]
        assert set(j["allowed_next"]) == {"in_progress", "duplicate", "wont_fix"}, j["allowed_next"]
        return f"{len(j['log'])} log entries attached"

    @c.check("F03", "status moves follow FBK-11a: in_progress -> fixed; illegal moves are 422")
    def _():
        i = fb["id"]
        expect(c.call("POST", f"/feedback/{i}/status", P, {"status": "confirmed"}), 422, "invalid_parameter")
        expect(c.call("POST", f"/feedback/{i}/status", P, {"status": "in_progress", "note": "Looking at it"}), 200)
        expect(c.call("POST", f"/feedback/{i}/status", P, {"status": "duplicate"}), 422, "invalid_parameter")
        r = c.call("POST", f"/feedback/{i}/status", P, {"status": "fixed", "note": "Fix in PR #3"}); expect(r, 200)
        assert r.json()["status"] == "fixed" and r.json()["last_updated_by"] == P
        expect(c.call("GET", "/feedback/999999", P), 404, "not_found")

    @c.check("F04", "developer agents may attach images; they come back downscaled")
    def _():
        import base64, zlib, struct
        def png(w, h):  # tiny solid PNG, no dependencies
            raw = b"".join(b"\x00" + b"\x40\x80\xc0" * w for _ in range(h))
            def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
            return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
        img = base64.b64encode(png(2400, 1200)).decode()
        r = c.call("POST", "/feedback", P, {"kind": "bug", "description": "acceptance: with image", "images": [{"data_base64": img}]}); expect(r, 201)
        d = c.call("GET", f"/feedback/{r.json()['id']}?size=thumb", P); expect(d, 200)
        im = d.json()["images"]
        assert len(im) == 1 and max(im[0]["width_px"], im[0]["height_px"]) <= 512, im[:1]
        return f"stored and returned {im[0]['width_px']}x{im[0]['height_px']}"

    @c.check("F06", "report text and status notes never reach the log other agents can read")
    def _():
        time.sleep(1.5)  # async log writer
        since = urllib.request.quote(started.isoformat(timespec="seconds"))  # this run only: the log outlives reinstalls
        blob = c.call("GET", f"/log/export?format=jsonl&since={since}", T).body.decode()
        assert len(blob) > 1000, "export for this run is empty"
        for secret_text in ("acceptance: image/last was slow", "Looking at it", "Fix in PR #3", "acceptance: with image"):
            assert secret_text not in blob, f"log contains report text: {secret_text!r}"

    @c.check("F05", "an agent may file at most 5 reports an hour (429)")
    def _():
        codes = [c.call("POST", "/feedback", T, {"kind": "bug", "description": f"acceptance flood {n}"}).status for n in range(5)]
        assert codes[-1] == 429 and 201 in codes, codes  # F01 already filed 2
        c.hook(developer=f"{P}=false")
        return f"statuses {codes}"

    captures_note = None
    if a.skip_captures:
        captures_note = "skipped with --skip-captures"
    elif c.locked():
        captures_note = "phone was locked; captures need it unlocked (TST-03)"

    @c.check("A23", "camera snapshot (rear, 1024 px), raw=1, and zoom (CAP-05)")
    def _():
        if captures_note:
            raise Unverified(captures_note)
        c.adb("shell", "am", "start", "-n", f"{c.pkg}/net.finleg.bridget.ui.MainActivity"); time.sleep(3)
        r = c.call("POST", "/camera/snapshot", T, {"camera": "rear", "max_width_px": 1024}, timeout=40); expect(r, 200)
        j = r.json(); assert j["width_px"] <= 1024 and j["format"] == "jpeg"
        r2 = c.call("POST", "/camera/snapshot", T, {"camera": "rear", "size": "thumb", "raw": 1}, timeout=40); expect(r2, 200)
        assert r2.body[:2] == b"\xff\xd8"
        r3 = c.call("POST", "/camera/snapshot", T, {"camera": "rear", "zoom": 2, "size": "thumb"}, timeout=40); expect(r3, 200)
        assert r3.json().get("zoom") == 2, r3.json().get("zoom")
        bad = c.call("POST", "/camera/snapshot", T, {"camera": "rear", "zoom": 50, "size": "thumb"}, timeout=40)
        expect(bad, 422, "invalid_parameter")
        zmin, zmax = bad.json().get("min_zoom"), bad.json().get("max_zoom")
        assert zmin is not None and zmax is not None and zmax > 1, bad.json()
        return f"{j['width_px']}x{j['height_px']} in {r.secs:.1f} s; raw {len(r2.body)//1024} KB; zoom 2x ok, range {zmin:.1f}-{zmax:.1f}x"

    @c.check("A24", "mic clip (3 s)")
    def _():
        if captures_note:
            raise Unverified(captures_note)
        r = c.call("POST", "/mic/record", T, {"duration_seconds": 3}, timeout=40); expect(r, 200)
        assert r.json()["format"] == "m4a" and r.json()["size_bytes"] > 1000
        return f"{r.json()['size_bytes']//1024} KB in {r.secs:.1f} s"

    @c.check("A25", "capture limit: 11th capture in a minute -> 429 (per agent)")
    def _():
        if captures_note:
            raise Unverified(captures_note)
        codes = [c.call("POST", "/camera/snapshot", T, {"camera": "side"}).status for _ in range(12)]
        assert 429 in codes, codes
        return f"statuses {codes}"

    @c.check("A26", "removed agent -> 401 reason agent_removed")
    def _():
        c.hook(remove=P); time.sleep(0.5)
        r = c.call("GET", "/health", P)
        if r.status == 429:  # earlier checks used up this address's 5 failed sign-ins (SEC-06): wait it out
            time.sleep(int(r.json().get("retry_after_s", 60)) + 1)
            r = c.call("GET", "/health", P)
        expect(r, 401, "unauthorized")
        assert r.json().get("reason") == "agent_removed", r.json()

    @c.check("A27", "SEC-06: 5 bad keys block bad keys from this address, but valid keys still work")
    def _():
        for _ in range(6):
            r = c.call("GET", "/health", raw_key="brg_" + secrets.token_urlsafe(32)[:43])
        expect(r, 429, "rate_limited")
        expect(c.call("GET", "/health", T), 200)

    @c.check("A28", "SECRETS: no key value or Bearer header in the log DB, prefs, API log/guide output or logcat")
    def _():
        values = [v.encode() for v in c.keys.values()]
        api = {}
        for name, path in (("log export", "/log/export?format=jsonl"), ("guide", "/guide"),
                           ("openapi", "/openapi.json"), ("capabilities", "/capabilities")):
            r = c.call("GET", path, T); expect(r, 200)
            api[name] = r.body
        c.hook(developer=f"{T}=true"); time.sleep(0.5)
        try:
            for rep in c.call("GET", "/feedback?limit=100", T).json().get("reports", []):
                api[f"feedback {rep['id']}"] = c.call("GET", f"/feedback/{rep['id']}?size=thumb", T).body
        finally:
            c.hook(developer=f"{T}=false")
        assert len(api["log export"]) > 4096, "log export too small to be a real check"
        blobs = {
            **api,
            "log.db": c.run_as("cat", "databases/log.db") + c.run_as("cat", "databases/log.db-wal"),
            "feedback.db": c.run_as("cat", "databases/feedback.db") + c.run_as("cat", "databases/feedback.db-journal"),
            "shared_prefs": b"".join(c.run_as("cat", f"shared_prefs/{n}") for n in ("agents.xml", "settings.xml", "pending_key.xml")),
            "logcat": subprocess.run(["adb", "-s", c.serial, "logcat", "-d"], capture_output=True, timeout=60).stdout,
        }
        assert len(blobs["log.db"]) > 4096, "could not read log.db with run-as"
        bad = []
        for name, b in blobs.items():
            if any(v in b for v in values):
                bad.append(f"{name}: a test key value")
            if KEY_RE.search(b.decode("latin-1")):
                bad.append(f"{name}: a brg_ key pattern")
            if re.search(rb"Bearer brg_", b):
                bad.append(f"{name}: a Bearer header")
        assert not bad, "; ".join(bad)
        n_fb = sum(1 for k in blobs if k.startswith("feedback "))
        return "checked " + ", ".join(f"{k} ({len(v)//1024} KB)" for k, v in blobs.items() if not k.startswith("feedback ")) + f", {n_fb} feedback reports"

    @c.check("A29", "boot receiver path: reinstall (MY_PACKAGE_REPLACED) restarts the listener in the background")
    def _():
        if not a.apk:
            raise Unverified("pass --apk <debug apk> to run it")
        c.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        out = subprocess.run(["adb", "-s", c.serial, "install", "-r", "-g", a.apk], capture_output=True, text=True, timeout=180)
        assert "Success" in out.stdout, out.stdout[-200:] + out.stderr[-200:]
        t0 = time.time()
        while time.time() - t0 < 30:
            try:
                r = c.call("GET", "/health", T, timeout=3)
                if r.status == 200:
                    j = r.json()
                    return f"listening again {time.time() - t0:.1f} s after install, uptime {j['listener_uptime_seconds']} s"
            except Exception:
                pass
            time.sleep(1)
        assert False, "listener did not come back within 30 s"

    @c.check("A30", "after a background (re)start, opening the app gives the listener camera and mic again (ARC-03)")
    def _():
        if captures_note:
            raise Unverified(captures_note)
        c.adb("shell", "am", "start", "-n", f"{c.pkg}/net.finleg.bridget.ui.MainActivity"); time.sleep(4)
        l = c.call("GET", "/capabilities", T).json()["listener"]
        assert l["camera_ready"] and l["mic_ready"], l

    @c.check("U01", "published update manifest (latest.json on R2) is well-formed and its APK matches its SHA-256")
    def _():
        import hashlib
        url = "https://pub-3a79691572bc4abc93fce31910fe320b.r2.dev/bridget/latest.json"
        ua = {"User-Agent": "bridget-acceptance/1"}  # Cloudflare refuses Python's default User-Agent with 403
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=ua), timeout=20) as r:
                m = json.loads(r.read())
        except urllib.error.HTTPError as e:
            raise Unverified(f"no manifest published yet (HTTP {e.code})")
        assert m["package"] == "net.finleg.bridget" and m["url"].startswith("https://pub-3a79691572bc4abc93fce31910fe320b.r2.dev/bridget/"), m
        with urllib.request.urlopen(urllib.request.Request(m["url"], headers=ua), timeout=120) as r:
            apk = r.read()
        assert hashlib.sha256(apk).hexdigest() == m["sha256"].lower() and len(apk) == m["size_bytes"], "APK doesn't match the manifest"
        return f"{m['version_name']} ({m['version_code']}), {len(apk)//1024} KB, SHA-256 ok"

    # ---- cleanup and results --------------------------------------------------------------------------
    c.hook(remove=T)
    c.keys.clear()
    ended = datetime.datetime.now().astimezone()
    counts = {s: sum(1 for r in c.results if r[2] == s) for s in ("PASS", "FAIL", "UNVERIFIED")}
    out = a.out or os.path.join(os.path.dirname(__file__), "..", "docs", "acceptance",
                                f"{started:%Y-%m-%d-%H%M}-{version}-{c.pkg.rsplit('.', 1)[-1]}.md")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        f.write(f"# Bridget acceptance run, {started:%Y-%m-%d %H:%M %Z}\n\n")
        f.write("| | |\n|---|---|\n")
        f.write(f"| Build | `{c.pkg}` {version} |\n| Repo commit | `{commit}` |\n| Device | `{c.serial}` |\n")
        f.write(f"| API | `{c.base}` |\n| Script | `tools/acceptance.sh` |\n| Duration | {(ended - started).seconds} s |\n")
        f.write(f"| Result | **{counts['PASS']} passed, {counts['FAIL']} failed, {counts['UNVERIFIED']} unverified** |\n\n")
        f.write("UNVERIFIED means the check could not run (for example the phone was locked). It is not a pass.\n\n")
        f.write("| ID | Check | Result | Note |\n|---|---|---|---|\n")
        for cid, name, status, note in c.results:
            f.write(f"| {cid} | {name} | **{status}** | {note.replace('|', '/')} |\n")
    print(f"\n{counts['PASS']} passed, {counts['FAIL']} failed, {counts['UNVERIFIED']} unverified")
    print(f"results: {os.path.normpath(out)}")
    return 1 if counts["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
