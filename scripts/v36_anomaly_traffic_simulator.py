#!/usr/bin/env python3
"""
v36_anomaly_traffic_simulator.py
================================
V3.6.x targeted anomaly live audit-trail simulator for the Spring Boot dataprocessor.

Purpose
-------
Emit anomalous Kafka audit events matching the V3.6 dataprocessor raw contract.
This script generates full sessions tagged by a requested anomaly family.

Supported canonical V3.6 anomaly tags:
  credential_stuffing
  session_hijacking
  data_exfiltration
  api_scraping
  off_hours_compromise
  behavioral_sequence_anomaly
  all

Legacy tag aliases are also accepted:
  repeated_fail -> credential_stuffing
  distributed_brute_force -> credential_stuffing
  geo_jump -> session_hijacking
  impossible_device_switch -> session_hijacking
  unusual_hour -> off_hours_compromise
  rapid_fire -> behavioral_sequence_anomaly
  impossible_seq -> behavioral_sequence_anomaly
  ping_pong_loop -> behavioral_sequence_anomaly
  zombie_session -> behavioral_sequence_anomaly

Dry run:
  python v36_anomaly_traffic_simulator.py --tag all --dry-run --session-count 2

Kafka run:
  python v36_anomaly_traffic_simulator.py --tag data_exfiltration --session-count 20
"""
from __future__ import annotations

import argparse
import json
import random
import re
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from itertools import cycle
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

DEFAULT_BACKEND_APIS = Path(__file__).with_name("backend-apis-actions.json")
DEFAULT_ACTIONS_ORDER = Path(__file__).with_name("actions_order-v2.json")

CANONICAL_TAGS = [
    "credential_stuffing",
    "session_hijacking",
    "data_exfiltration",
    "api_scraping",
    "off_hours_compromise",
    "behavioral_sequence_anomaly",
]

ALIASES = {
    "repeated_fail": "credential_stuffing",
    "distributed_brute_force": "credential_stuffing",
    "geo_jump": "session_hijacking",
    "impossible_device_switch": "session_hijacking",
    "unusual_hour": "off_hours_compromise",
    "rapid_fire": "behavioral_sequence_anomaly",
    "impossible_seq": "behavioral_sequence_anomaly",
    "ping_pong_loop": "behavioral_sequence_anomaly",
    "zombie_session": "behavioral_sequence_anomaly",
}

ENV_IDS = ["prod-espace-assure-01", "prod-espace-assure-02", "prod-espace-assure-03", "preprod-espace-assure-01"]
REGIONS = [("FR", "IDF"), ("FR", "ARA"), ("FR", "NAQ"), ("FR", "OCC"), ("FR", "HDF")]
FOREIGN_COUNTRIES = [("MA", "CAS"), ("TN", "TUN"), ("BR", "SP"), ("NG", "LA"), ("RU", "MOW"), ("CN", "BJ")]

# ------------------------------ shared helpers ------------------------------
def parse_dt(value: str) -> datetime:
    dt = datetime.fromisoformat(value)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def iso_ms(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.isoformat(timespec="milliseconds")


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def norm_page(x: Any) -> str:
    s = str(x or "unknown").strip().lower().replace(" ", "_")
    return {"personal_info": "personalinformation", "banking_info": "bankinginformation", "preferences": "globalpreferences", "tp_card_download": "tpcarddownload"}.get(s, s)


def api_family_from_template(path: str) -> str:
    clean = str(path or "/").split("?", 1)[0]
    parts = [p for p in clean.split("/") if p]
    if not parts:
        return "root"
    if parts[0].lower() in {"v1", "v2"} and len(parts) > 1:
        return parts[1].lower().replace("-", "_")
    return parts[0].lower().replace("-", "_")


def strip_audit_type(value: Optional[str]) -> str:
    if not value:
        return "UNTRACKED"
    s = str(value)
    return s.split(".")[-1] if "." in s else s


def build_api_lookup(backend: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    out = {}
    for e in backend:
        method = str(e.get("method") or "GET").upper()
        path = str(e.get("path") or "/")
        tr = e.get("actionTracking") or {}
        out[f"{method} {path}"] = {
            "type": strip_audit_type(tr.get("type")),
            "subType": tr.get("subType") or "NO_SUBTYPE",
            "value": tr.get("value"),
            "controller": e.get("controller") or "UNKNOWN_CONTROLLER",
            "api_template": path,
            "api_family": api_family_from_template(path),
        }
    return out


def match_api(lookup: Dict[str, Dict[str, Any]], method: str, path: str) -> Dict[str, Any]:
    key = f"{method.upper()} {path}"
    if key in lookup:
        return dict(lookup[key])
    for k, v in lookup.items():
        m, p = k.split(" ", 1)
        if m != method.upper():
            continue
        pat = re.escape(p).replace(r"\*", r"[^/]+")
        pat = re.sub(r"\\\{[^}]+\\\}", r"[^/]+", pat)
        if re.match("^" + pat + "$", path):
            return dict(v)
    return {"type": "UNTRACKED", "subType": "NO_SUBTYPE", "value": None, "controller": "UNKNOWN_CONTROLLER", "api_template": path, "api_family": api_family_from_template(path)}


def enrich_tracking(method: str, api_template: str, frontend_action_name: str, lookup: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    tr = match_api(lookup, method, api_template)
    subtype = tr.get("subType") or "NO_SUBTYPE"
    action_value = tr.get("value") or (subtype if subtype != "NO_SUBTYPE" else frontend_action_name) or f"{method.upper()} {api_template}"
    tr.update({"frontend_action_name": frontend_action_name, "action_value": str(action_value), "subType": subtype, "api_template": tr.get("api_template") or api_template, "api_family": tr.get("api_family") or api_family_from_template(api_template), "controller": tr.get("controller") or "UNKNOWN_CONTROLLER"})
    return tr


def extract_actions(nav: Dict[str, Any], lookup: Dict[str, Dict[str, Any]]) -> Dict[str, List[Tuple[str, str, str, Dict[str, Any]]]]:
    pages: Dict[str, List[Tuple[str, str, str, Dict[str, Any]]]] = {}
    def add(page: str, method: str, api: str, name: str) -> None:
        pages.setdefault(norm_page(page), []).append((method.upper(), api, name, enrich_tracking(method, api, name, lookup)))
    for step in nav.get("auth_flow", {}).get("steps", []):
        page = "mfa" if "mfa" in norm_page(step.get("state", "login")) else "login"
        for api in step.get("api", []) or []:
            method, path = api.split(" ", 1)
            add(page, method, path, step.get("action", page))
    for route, info in nav.get("routes", {}).items():
        page = norm_page(route)
        for act in info.get("user_actions", []) or []:
            for api in act.get("api", []) or []:
                method, path = api.split(" ", 1)
                add(page, method, path, act.get("name", page))
    return pages


def status_from_code(code: int) -> str:
    if 200 <= code < 300 or code == 304:
        return "SUCCESS"
    if code in {202, 206, 208}:
        return "PENDING"
    return "FAILURE"


def gen_ip(rng: random.Random, country: str) -> str:
    if country == "FR":
        return f"82.{rng.randint(120,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"
    return f"{rng.choice(['185','91','45','103','196','197'])}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"


def request_size(rng: random.Random, method: str, api: str) -> int:
    if "upload" in api.lower():
        return rng.randint(10_000, 500_000)
    if method == "GET":
        return rng.randint(4, 120)
    if method == "POST":
        return rng.randint(80, 2200)
    return rng.randint(10, 1600)


def response_size(rng: random.Random, method: str, api: str, code: int) -> int:
    if code >= 400:
        return rng.randint(50, 900)
    low = api.lower()
    if any(k in low for k in ["file", "download", "refund-resume", "tp-card", "adhesion-certificate", "payment-schedules"]):
        return rng.randint(10_000, 5_000_000)
    if "all-info" in low:
        return rng.randint(2_000, 50_000)
    if "heartbeat" in low:
        return rng.randint(10, 50)
    return rng.randint(200, 5000)

@dataclass
class UserProfile:
    insured_id: str
    country: str
    region: str
    device: str
    browser: str
    os: str
    ip: str
    envs: List[str]
    sessions_started: int = 0

@dataclass
class PlannedSession:
    user: UserProfile
    session_id: str
    anomaly_type: str
    events: List[Dict[str, Any]]
    next_index: int = 0
    @property
    def done(self) -> bool:
        return self.next_index >= len(self.events)
    @property
    def next_event(self) -> Dict[str, Any]:
        return self.events[self.next_index]

class Clock:
    def __init__(self, start: datetime, speed: float):
        self.current = start
        self.speed = speed
        self.last_wall = time.monotonic()
    def tick(self) -> datetime:
        now = time.monotonic()
        self.current += timedelta(seconds=(now - self.last_wall) * self.speed)
        self.last_wall = now
        return self.current

class AnomalySimulator:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.rng = random.Random(args.seed)
        backend = load_json(Path(args.backend_apis))
        nav = load_json(Path(args.actions_order))
        self.lookup = build_api_lookup(backend)
        self.pages = extract_actions(nav, self.lookup)
        if "login" not in self.pages:
            self.pages["login"] = [("POST", "/auth/login", "submit_credentials", enrich_tracking("POST", "/auth/login", "submit_credentials", self.lookup))]
        self.users = self.build_users(args.insured_count)
        self.clock = Clock(parse_dt(args.virtual_start), args.rate)
        self.active: List[PlannedSession] = []
        self.record_counter = 0
        self.completed_sessions = 0
        self.producer = None if args.dry_run else self.build_producer()
        tags = CANONICAL_TAGS if args.tag == "all" else [ALIASES.get(args.tag, args.tag)]
        self.tag_cycle = cycle(tags)

    def build_producer(self):
        if KafkaProducer is None:
            raise RuntimeError("kafka-python is not installed. Use --dry-run or pip install kafka-python")
        return KafkaProducer(
            bootstrap_servers=[s.strip() for s in self.args.bootstrap_servers.split(",")],
            value_serializer=lambda v: json.dumps(v, ensure_ascii=True).encode("utf-8"),
            key_serializer=lambda v: v.encode("utf-8") if v else None,
            api_version=self.args.api_version,
            acks=self.args.acks,
        )

    def build_users(self, n: int) -> List[UserProfile]:
        users = []
        for i in range(n):
            country, region = self.rng.choice(REGIONS)
            device = self.rng.choice(["desktop", "mobile", "tablet"])
            browser = self.rng.choice(["chrome", "firefox", "edge", "safari"])
            osn = "windows" if device == "desktop" else self.rng.choice(["android", "ios"])
            iid = f"insured-anom-{i:05d}-{uuid.uuid4().hex[:6]}"
            users.append(UserProfile(iid, country, region, device, browser, osn, gen_ip(self.rng, country), self.rng.sample(ENV_IDS, k=1)))
        return users

    def forced_tracking(self, method: str, api: str, frontend: str) -> Dict[str, Any]:
        return enrich_tracking(method, api, frontend, self.lookup)

    def make_event(self, user: UserProfile, sid: str, ts: datetime, method: str, api: str, tr: Dict[str, Any], code: int,
                   page: str, seq: int, prev: Optional[datetime], start: datetime, tag: str) -> Dict[str, Any]:
        self.record_counter += 1
        return {
            "record_id": f"anom-{self.record_counter:012d}",
            "insured_id": user.insured_id,
            "session_id": sid,
            "timestamp": iso_ms(ts),
            "date": ts.strftime("%Y-%m-%d"),
            "hour": ts.hour,
            "day_of_week": ts.weekday(),
            "month": ts.month,
            "is_weekend": int(ts.weekday() >= 5),
            "is_business_hours": int(8 <= ts.hour < 19),
            "http_method": method,
            "action_api": api,
            "api_template": tr.get("api_template") or api,
            "api_family": tr.get("api_family") or api_family_from_template(api),
            "controller": tr.get("controller") or "UNKNOWN_CONTROLLER",
            "frontend_action_name": tr.get("frontend_action_name") or "unknown_action",
            "action_value": tr.get("action_value") or tr.get("value") or tr.get("subType") or tr.get("frontend_action_name") or f"{method} {api}",
            "action_type": tr.get("type") or "UNTRACKED",
            "action_subtype": tr.get("subType") or "NO_SUBTYPE",
            "http_code": int(code),
            "status": status_from_code(int(code)),
            "page": norm_page(page),
            "device": user.device,
            "browser": user.browser,
            "os": user.os,
            "user_agent": f"Mozilla/5.0 {user.device}/{user.os}/{user.browser}",
            "ip": user.ip,
            "ip_country": user.country,
            "ip_region": user.region,
            "environment_id": self.rng.choice(user.envs),
            "session_action_seq": int(seq),
            "time_since_prev_action_ms": int((ts - prev).total_seconds() * 1000) if prev else 0,
            "session_duration_so_far_ms": int((ts - start).total_seconds() * 1000),
            "request_data_size_bytes": request_size(self.rng, method, api),
            "response_data_size_bytes": response_size(self.rng, method, api, code),
            "is_anomaly": 1,
            "anomaly_type": tag,
        }

    def add_login(self, user: UserProfile, sid: str, start: datetime, tag: str) -> Tuple[List[Dict[str, Any]], datetime, int]:
        tr = self.forced_tracking("POST", "/auth/login", "submit_credentials")
        ev = self.make_event(user, sid, start, "POST", "/auth/login", tr, 200, "login", 1, None, start, tag)
        return [ev], start, 2

    def plan_session(self, user: UserProfile, tag: str) -> PlannedSession:
        user.sessions_started += 1
        sid = f"sess-anom-{user.insured_id}-{user.sessions_started:04d}-{uuid.uuid4().hex[:6]}"
        start = self.anchor_for_tag(tag)
        events, prev, seq = self.add_login(user, sid, start, tag)

        if tag == "credential_stuffing":
            # Multiple very fast failed authentication attempts.
            for i in range(8 + self.rng.randint(0, 8)):
                ts = prev + timedelta(milliseconds=self.rng.randint(40, 180))
                tr = self.forced_tracking("POST", "/auth/login", "submit_credentials")
                ev = self.make_event(user, sid, ts, "POST", "/auth/login", tr, self.rng.choice([401, 401, 403, 429]), "login", seq, prev, start, tag)
                ev["request_data_size_bytes"] = self.rng.randint(300, 1800)
                ev["response_data_size_bytes"] = self.rng.randint(80, 800)
                events.append(ev); prev = ts; seq += 1

        elif tag == "session_hijacking":
            # Normal start then mid-session country/device switch.
            normal_steps = [("GET", "/all-info?actionKey=requests", "refresh_requests", "home"), ("GET", "/documents", "list_documents", "documents")]
            for method, api, front, page in normal_steps:
                ts = prev + timedelta(seconds=self.rng.randint(20, 90))
                tr = self.forced_tracking(method, api, front)
                events.append(self.make_event(user, sid, ts, method, api, tr, 200, page, seq, prev, start, tag)); prev = ts; seq += 1
            cc, rg = self.rng.choice(FOREIGN_COUNTRIES)
            user.country, user.region, user.ip = cc, rg, gen_ip(self.rng, cc)
            user.device = self.rng.choice(["desktop", "mobile"])
            user.browser = self.rng.choice(["chrome", "edge", "firefox"])
            user.os = "windows" if user.device == "desktop" else "android"
            for method, api, front, page in [("GET", "/documents/file", "download_document", "documents"), ("POST", "/insured/all-refund-resume", "download_all_refunds_pdf", "refunds")]:
                ts = prev + timedelta(seconds=self.rng.randint(5, 25))
                tr = self.forced_tracking(method, api, front)
                ev = self.make_event(user, sid, ts, method, api, tr, 200, page, seq, prev, start, tag)
                ev["response_data_size_bytes"] = self.rng.randint(800_000, 7_000_000)
                events.append(ev); prev = ts; seq += 1

        elif tag == "data_exfiltration":
            downloads = [
                ("GET", "/documents/file", "download_document", "documents"),
                ("GET", "/document-display/download/{id}", "document_display_download", "documents"),
                ("POST", "/insured/all-refund-resume", "download_all_refunds_pdf", "refunds"),
                ("POST", "/insured/refund-resume", "download_refund_pdf", "refunds"),
            ]
            for i in range(12 + self.rng.randint(0, 8)):
                method, api, front, page = self.rng.choice(downloads)
                ts = prev + timedelta(milliseconds=self.rng.randint(80, 350))
                tr = self.forced_tracking(method, api, front)
                ev = self.make_event(user, sid, ts, method, api, tr, 200, page, seq, prev, start, tag)
                ev["response_data_size_bytes"] = self.rng.randint(2_500_000, 12_000_000)
                ev["time_since_prev_action_ms"] = self.rng.randint(80, 350)
                events.append(ev); prev = ts; seq += 1

        elif tag == "api_scraping":
            scrape = [
                ("GET", "/documents", "list_documents", "documents"),
                ("GET", "/documents/file", "download_document", "documents"),
                ("GET", "/requests/messages/file", "download_request_doc", "requests"),
                ("GET", "/all-info?actionKey=requests", "refresh_requests", "requests"),
            ]
            for i in range(18 + self.rng.randint(0, 15)):
                method, api, front, page = self.rng.choice(scrape)
                ts = prev + timedelta(milliseconds=self.rng.randint(40, 220))
                tr = self.forced_tracking(method, api, front)
                ev = self.make_event(user, sid, ts, method, api, tr, 200, page, seq, prev, start, tag)
                ev["response_data_size_bytes"] = self.rng.randint(50_000, 900_000)
                ev["time_since_prev_action_ms"] = self.rng.randint(40, 220)
                events.append(ev); prev = ts; seq += 1

        elif tag == "off_hours_compromise":
            # Force 02:00-04:59 and add sensitive actions.
            if not (2 <= start.hour <= 4):
                start = start.replace(hour=self.rng.randint(2, 4), minute=self.rng.randint(0, 59), second=self.rng.randint(0, 59))
                events, prev, seq = self.add_login(user, sid, start, tag)
            cc, rg = self.rng.choice(FOREIGN_COUNTRIES)
            user.country, user.region, user.ip = cc, rg, gen_ip(self.rng, cc)
            sensitive = [
                ("PUT", "/insured/rib", "update_rib", "bankinginformation"),
                ("POST", "/insured/sign-file", "sign_rib", "bankinginformation"),
                ("GET", "/documents/file", "download_document", "documents"),
                ("POST", "/insured/all-refund-resume", "download_all_refunds_pdf", "refunds"),
            ]
            for i in range(6 + self.rng.randint(0, 5)):
                method, api, front, page = self.rng.choice(sensitive)
                ts = prev + timedelta(seconds=self.rng.randint(10, 60))
                tr = self.forced_tracking(method, api, front)
                ev = self.make_event(user, sid, ts, method, api, tr, 200, page, seq, prev, start, tag)
                ev["is_business_hours"] = 0
                ev["response_data_size_bytes"] = max(ev["response_data_size_bytes"], self.rng.randint(100_000, 5_000_000))
                events.append(ev); prev = ts; seq += 1

        elif tag == "behavioral_sequence_anomaly":
            # Real actions but weird order, rapid timing, and rare transitions.
            weird = [
                ("GET", "/documents/file", "download_document", "documents"),
                ("PUT", "/insured/rib", "update_rib", "bankinginformation"),
                ("POST", "/v2/teletransmission/update/{amoCode}", "teletransmission_update", "globalpreferences"),
                ("POST", "/help/debug-reports", "debug_report", "help"),
                ("GET", "/insured/tp-card", "download_tp_card_pdf", "tpcarddownload"),
                ("POST", "/documents/upload-documents", "upload_document", "documents"),
            ]
            for i in range(10 + self.rng.randint(0, 8)):
                method, api, front, page = weird[i % len(weird)] if i < len(weird) else self.rng.choice(weird)
                ts = prev + timedelta(milliseconds=self.rng.randint(100, 900))
                tr = self.forced_tracking(method, api, front)
                code = self.rng.choices([200, 200, 401, 403, 429], weights=[.70, .10, .08, .06, .06])[0]
                ev = self.make_event(user, sid, ts, method, api, tr, code, page, seq, prev, start, tag)
                ev["time_since_prev_action_ms"] = self.rng.randint(100, 900)
                events.append(ev); prev = ts; seq += 1

        else:
            raise ValueError(f"Unsupported anomaly tag: {tag}")

        return PlannedSession(user, sid, tag, sorted(events, key=lambda e: e["session_action_seq"]))

    def anchor_for_tag(self, tag: str) -> datetime:
        now = self.clock.current
        if tag == "off_hours_compromise":
            base = now.replace(hour=self.rng.randint(2, 4), minute=self.rng.randint(0, 59), second=self.rng.randint(0, 59), microsecond=0)
            if base > now:
                base -= timedelta(days=1)
            return base
        return now

    def start_session(self):
        busy = {s.user.insured_id for s in self.active}
        candidates = [u for u in self.users if u.insured_id not in busy]
        if not candidates:
            return
        tag = next(self.tag_cycle)
        self.active.append(self.plan_session(self.rng.choice(candidates), tag))

    def emit(self, session: PlannedSession, event: Dict[str, Any]):
        if self.args.dry_run:
            print(json.dumps(event, ensure_ascii=False))
        else:
            self.producer.send(self.args.topic, key=session.user.insured_id, value=event)
            if self.args.verbose:
                print(f"[{event['timestamp']}] ANOMALY {session.anomaly_type:28} {event['insured_id']} {event['frontend_action_name']} {event['api_template']}", flush=True)

    def run(self):
        print(f"Starting V3.6 anomaly simulator tag={self.args.tag} canonical={ALIASES.get(self.args.tag, self.args.tag)}", file=sys.stderr)
        try:
            while self.args.session_count == 0 or self.completed_sessions < self.args.session_count:
                now = self.clock.tick()
                while len(self.active) < self.args.concurrent_sessions:
                    self.start_session()
                    if len(self.active) >= self.args.insured_count:
                        break
                remaining = []
                for session in self.active:
                    if session.done:
                        self.completed_sessions += 1
                        continue
                    nxt = parse_dt(session.next_event["timestamp"])
                    if nxt <= now:
                        self.emit(session, session.next_event)
                        session.next_index += 1
                    if not session.done:
                        remaining.append(session)
                    else:
                        self.completed_sessions += 1
                self.active = remaining
                time.sleep(0.01)
        except KeyboardInterrupt:
            print("Stopped by user", file=sys.stderr)
        finally:
            if self.producer:
                self.producer.flush()
                self.producer.close()


def main():
    choices = ["all"] + CANONICAL_TAGS + sorted(ALIASES)
    parser = argparse.ArgumentParser(description="V3.6 targeted anomaly Kafka simulator")
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--backend-apis", default=str(DEFAULT_BACKEND_APIS))
    parser.add_argument("--actions-order", default=str(DEFAULT_ACTIONS_ORDER))
    parser.add_argument("--tag", choices=choices, required=True)
    parser.add_argument("--session-count", type=int, default=0, help="Total completed sessions to emit; 0=infinite")
    parser.add_argument("--concurrent-sessions", type=int, default=4)
    parser.add_argument("--insured-count", type=int, default=100)
    parser.add_argument("--rate", type=float, default=120.0)
    parser.add_argument("--virtual-start", default="2026-03-24T10:00:00+01:00")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--api-version", default="3.7.0")
    parser.add_argument("--acks", type=int, default=1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    AnomalySimulator(args).run()

if __name__ == "__main__":
    main()
