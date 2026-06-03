#!/usr/bin/env python3
"""
v36_normal_traffic_simulator.py
================================
V3.6.x normal live audit-trail simulator for the Spring Boot dataprocessor.

Purpose
-------
Emit realistic NORMAL Kafka audit events that match the V3.6 dataprocessor raw
contract and the V3.2/V3.6 training feature schema.

This simulator does NOT use deprecated model artifacts. It only uses the action
catalog and navigation graph:
  - backend-apis-actions.json
  - actions_order-v2.json

Kafka topic default:
  topic-audit-trail

Dry run:
  python v36_normal_traffic_simulator.py --dry-run --duration 10

Kafka run:
  python v36_normal_traffic_simulator.py --bootstrap-servers localhost:9092 --duration 120

Output event contract includes:
  record_id, insured_id, session_id, timestamp, date, hour, day_of_week,
  is_weekend, is_business_hours, http_method, action_api, api_template,
  api_family, controller, frontend_action_name, action_value, action_type,
  action_subtype, http_code, status, page, device, browser, os, user_agent,
  ip, ip_country, ip_region, environment_id, session_action_seq,
  time_since_prev_action_ms, session_duration_so_far_ms,
  request_data_size_bytes, response_data_size_bytes, is_anomaly, anomaly_type
"""
from __future__ import annotations

import argparse
import json
import math
import random
import re
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

DEFAULT_BACKEND_APIS = Path(__file__).with_name("backend-apis-actions.json")
DEFAULT_ACTIONS_ORDER = Path(__file__).with_name("actions_order-v2.json")

ENV_IDS = [
    "prod-espace-assure-01",
    "prod-espace-assure-02",
    "prod-espace-assure-03",
    "preprod-espace-assure-01",
]

REGIONS = [
    ("FR", "IDF", "Ile-de-France"),
    ("FR", "ARA", "Auvergne-Rhone-Alpes"),
    ("FR", "NAQ", "Nouvelle-Aquitaine"),
    ("FR", "OCC", "Occitanie"),
    ("FR", "HDF", "Hauts-de-France"),
    ("FR", "PAC", "Provence-Alpes-Cote-d-Azur"),
    ("FR", "GES", "Grand Est"),
    ("FR", "PDL", "Pays-de-la-Loire"),
    ("FR", "BRE", "Bretagne"),
    ("FR", "NOR", "Normandie"),
]
REGION_W = [0.19, 0.12, 0.09, 0.09, 0.09, 0.08, 0.08, 0.06, 0.05, 0.05]

PERSONAS = {
    "power_user":          {"w": 0.08, "spw": (5, 14), "aps": (10, 30), "delay": (5, 60),   "hours": (7, 23), "wknd": 0.25, "devices": {"mobile": .30, "desktop": .55, "tablet": .15}},
    "regular_user":        {"w": 0.20, "spw": (2, 5),  "aps": (5, 13),  "delay": (15, 120), "hours": (8, 20), "wknd": 0.10, "devices": {"mobile": .45, "desktop": .45, "tablet": .10}},
    "document_focused":    {"w": 0.12, "spw": (1, 4),  "aps": (5, 12),  "delay": (20, 180), "hours": (9, 18), "wknd": 0.05, "devices": {"mobile": .20, "desktop": .65, "tablet": .15}},
    "contact_heavy":       {"w": 0.08, "spw": (1, 4),  "aps": (5, 10),  "delay": (30, 300), "hours": (9, 19), "wknd": 0.08, "devices": {"mobile": .40, "desktop": .50, "tablet": .10}},
    "banking_focused":     {"w": 0.06, "spw": (1, 3),  "aps": (4, 9),   "delay": (30, 180), "hours": (9, 18), "wknd": 0.03, "devices": {"mobile": .25, "desktop": .60, "tablet": .15}},
    "beneficiary_manager": {"w": 0.07, "spw": (1, 4),  "aps": (5, 12),  "delay": (20, 150), "hours": (9, 19), "wknd": 0.06, "devices": {"mobile": .40, "desktop": .50, "tablet": .10}},
    "security_conscious":  {"w": 0.05, "spw": (1, 3),  "aps": (4, 9),   "delay": (20, 120), "hours": (8, 22), "wknd": 0.15, "devices": {"mobile": .50, "desktop": .45, "tablet": .05}},
    "minimal_user":        {"w": 0.18, "spw": (0, 2),  "aps": (2, 5),   "delay": (30, 300), "hours": (9, 18), "wknd": 0.02, "devices": {"mobile": .55, "desktop": .35, "tablet": .10}},
    "mobile_only":         {"w": 0.10, "spw": (2, 7),  "aps": (3, 9),   "delay": (10, 90),  "hours": (7, 23), "wknd": 0.30, "devices": {"mobile": .85, "desktop": .10, "tablet": .05}},
    "new_explorer":        {"w": 0.06, "spw": (3, 7),  "aps": (6, 16),  "delay": (10, 60),  "hours": (9, 22), "wknd": 0.20, "devices": {"mobile": .45, "desktop": .45, "tablet": .10}},
}

TASK_FLOWS = {
    "document_flow": ["home", "documents", "documents", "requests", "documents", "home"],
    "refund_flow": ["home", "refunds", "documents", "refunds", "home"],
    "beneficiary_flow": ["home", "beneficiaries", "updatebeneficiaries", "bankinginformation", "home"],
    "profile_flow": ["home", "personalinformation", "globalpreferences", "home"],
    "support_flow": ["home", "help", "requests", "home"],
    "mobile_card_flow": ["home", "tpcarddownload", "globalpreferences", "home"],
    "explorer_flow": ["home", "documents", "refunds", "beneficiaries", "personalinformation", "requests", "home"],
}

PERSONA_TASKS = {
    "document_focused": {"document_flow": .60, "refund_flow": .15, "support_flow": .10, "explorer_flow": .15},
    "banking_focused": {"beneficiary_flow": .55, "profile_flow": .15, "support_flow": .10, "explorer_flow": .20},
    "beneficiary_manager": {"beneficiary_flow": .65, "document_flow": .10, "profile_flow": .10, "explorer_flow": .15},
    "contact_heavy": {"support_flow": .60, "document_flow": .15, "refund_flow": .10, "explorer_flow": .15},
    "mobile_only": {"mobile_card_flow": .35, "refund_flow": .20, "document_flow": .15, "explorer_flow": .30},
    "minimal_user": {"refund_flow": .30, "document_flow": .25, "profile_flow": .15, "explorer_flow": .30},
    "security_conscious": {"profile_flow": .45, "beneficiary_flow": .20, "document_flow": .15, "explorer_flow": .20},
    "power_user": {"document_flow": .25, "refund_flow": .20, "beneficiary_flow": .20, "support_flow": .10, "explorer_flow": .25},
    "regular_user": {"refund_flow": .25, "document_flow": .25, "support_flow": .15, "profile_flow": .10, "explorer_flow": .25},
    "new_explorer": {"explorer_flow": .55, "document_flow": .15, "refund_flow": .15, "beneficiary_flow": .10, "support_flow": .05},
}

# ------------------------------ helpers ------------------------------
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


def weighted_choice(rng: random.Random, weights: Dict[str, float]) -> str:
    keys = list(weights)
    vals = [float(weights[k]) for k in keys]
    return rng.choices(keys, weights=vals, k=1)[0]


def norm_page(x: Any) -> str:
    s = str(x or "unknown").strip().lower().replace(" ", "_")
    aliases = {
        "personal_info": "personalinformation",
        "banking_info": "bankinginformation",
        "preferences": "globalpreferences",
        "tp_card_download": "tpcarddownload",
        "updatebeneficiaries": "updatebeneficiaries",
    }
    return aliases.get(s, s)


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
    out: Dict[str, Dict[str, Any]] = {}
    for e in backend:
        method = str(e.get("method") or "GET").upper()
        path = str(e.get("path") or "/")
        tr = e.get("actionTracking") or {}
        typ = strip_audit_type(tr.get("type"))
        subtype = tr.get("subType") or "NO_SUBTYPE"
        value = tr.get("value")
        out[f"{method} {path}"] = {
            "type": typ,
            "subType": subtype,
            "value": value,
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
    return {
        "type": "UNTRACKED",
        "subType": "NO_SUBTYPE",
        "value": None,
        "controller": "UNKNOWN_CONTROLLER",
        "api_template": path,
        "api_family": api_family_from_template(path),
    }


def enrich_tracking(method: str, api_template: str, frontend_action_name: str, lookup: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    tr = match_api(lookup, method, api_template)
    frontend = str(frontend_action_name or "unknown_action")
    subtype = tr.get("subType") or "NO_SUBTYPE"
    action_value = tr.get("value") or (subtype if subtype != "NO_SUBTYPE" else frontend) or f"{method.upper()} {tr.get('api_template', api_template)}"
    tr.update({
        "frontend_action_name": frontend,
        "action_value": str(action_value),
        "subType": subtype,
        "api_template": tr.get("api_template") or api_template,
        "api_family": tr.get("api_family") or api_family_from_template(api_template),
        "controller": tr.get("controller") or "UNKNOWN_CONTROLLER",
    })
    return tr


def extract_actions(nav: Dict[str, Any], lookup: Dict[str, Dict[str, Any]]) -> Dict[str, List[Tuple[str, str, str, Dict[str, Any]]]]:
    pages: Dict[str, List[Tuple[str, str, str, Dict[str, Any]]]] = {}

    def add(page: str, method: str, api: str, name: str) -> None:
        pages.setdefault(norm_page(page), []).append((method.upper(), api, name, enrich_tracking(method, api, name, lookup)))

    for step in nav.get("auth_flow", {}).get("steps", []):
        state = norm_page(step.get("state", "login"))
        page = "mfa" if "mfa" in state else "login"
        for api in step.get("api", []):
            method, path = api.split(" ", 1)
            add(page, method, path, step.get("action", page))

    for gate in nav.get("post_login_gates", {}).values():
        for api in gate.get("api", []) or []:
            method, path = api.split(" ", 1)
            add("home_gate", method, path, gate.get("action") or gate.get("route") or "home_gate")
        for act in gate.get("actions", []) or []:
            for api in act.get("api", []) or []:
                method, path = api.split(" ", 1)
                add("home_gate", method, path, act.get("name", "gate"))

    for route, info in nav.get("routes", {}).items():
        page = norm_page(route)
        for act in info.get("user_actions", []) or []:
            for api in act.get("api", []) or []:
                method, path = api.split(" ", 1)
                add(page, method, path, act.get("name", page))

    return pages


def pick_device(rng: random.Random, probs: Dict[str, float]) -> Tuple[str, str, str]:
    device = weighted_choice(rng, probs)
    if device == "mobile":
        browser = rng.choices(["chrome", "safari"], weights=[.6, .4])[0]
        osn = "android" if browser == "chrome" else "ios"
    elif device == "tablet":
        browser = rng.choices(["safari", "chrome"], weights=[.6, .4])[0]
        osn = "ios" if browser == "safari" else "android"
    else:
        browser = rng.choices(["chrome", "firefox", "edge", "safari"], weights=[.6, .15, .15, .1])[0]
        osn = "macos" if browser == "safari" else "windows"
    return device, browser, osn


def gen_ip(rng: random.Random, country: str) -> str:
    if country == "FR":
        return f"82.{rng.randint(120,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"
    prefix = rng.choice(["185", "91", "45", "103", "196", "197"])
    return f"{prefix}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"


def request_size(rng: random.Random, method: str, api: str) -> int:
    low = api.lower()
    if "upload" in low:
        return rng.randint(10_000, 500_000)
    if method == "GET":
        return rng.randint(4, 120)
    if method == "POST":
        return rng.randint(80, 2200)
    if method == "PUT":
        return rng.randint(60, 1600)
    return rng.randint(10, 600)


def response_size(rng: random.Random, method: str, api: str, code: int) -> int:
    if code >= 400:
        return rng.randint(50, 700)
    low = api.lower()
    if any(k in low for k in ["file", "download", "refund-resume", "tp-card", "adhesion-certificate", "payment-schedules"]):
        return rng.randint(10_000, 5_000_000)
    if "all-info" in low:
        return rng.randint(2_000, 50_000)
    if "heartbeat" in low:
        return rng.randint(10, 50)
    if "auth" in low or "login" in low:
        return rng.randint(500, 2000)
    return rng.randint(200, 5000)


def http_code(rng: random.Random, method: str, success_probability: float = 0.97) -> int:
    if rng.random() > success_probability:
        return rng.choices([400, 401, 403, 404, 422, 429, 500, 503], weights=[.18, .20, .14, .10, .10, .08, .03, .02])[0]
    if method == "POST":
        return rng.choices([200, 201, 204], weights=[.70, .25, .05])[0]
    if method in {"PUT", "DELETE"}:
        return rng.choices([200, 204], weights=[.75, .25])[0]
    return rng.choices([200, 304], weights=[.96, .04])[0]


def status_from_code(code: int) -> str:
    if 200 <= code < 300 or code == 304:
        return "SUCCESS"
    if code in {202, 206, 208}:
        return "PENDING"
    return "FAILURE"

@dataclass
class UserProfile:
    insured_id: str
    persona: str
    primary_device: str
    primary_browser: str
    primary_os: str
    country: str
    region: str
    ip: str
    environments: List[str]
    sessions_started: int = 0

@dataclass
class PlannedSession:
    user: UserProfile
    session_id: str
    events: List[Dict[str, Any]]
    next_index: int = 0
    @property
    def done(self) -> bool:
        return self.next_index >= len(self.events)
    @property
    def next_event(self) -> Dict[str, Any]:
        return self.events[self.next_index]

class VirtualClock:
    def __init__(self, start: datetime, speed: float):
        self.current = start
        self.speed = speed
        self.last_wall = time.monotonic()
    def tick(self) -> datetime:
        now = time.monotonic()
        elapsed = now - self.last_wall
        self.current += timedelta(seconds=elapsed * self.speed)
        self.last_wall = now
        return self.current
    def load_factor(self) -> float:
        m = self.current.hour * 60 + self.current.minute
        value = 0.2
        for peak, width in [(510, 90), (750, 60), (1080, 90)]:
            value += 0.5 * math.exp(-((m - peak) ** 2) / (2 * width ** 2))
        if self.current.weekday() >= 5:
            value *= 0.6
        return min(1.0, max(0.1, value))

class NormalTrafficSimulator:
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
        self.clock = VirtualClock(parse_dt(args.virtual_day_start), args.virtual_speed)
        self.active: List[PlannedSession] = []
        self.record_counter = 0
        self.producer = None if args.dry_run else self.build_producer()

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
            persona = weighted_choice(self.rng, {k: v["w"] for k, v in PERSONAS.items()})
            p = PERSONAS[persona]
            device, browser, osn = pick_device(self.rng, p["devices"])
            country, region, _ = self.rng.choices(REGIONS, weights=REGION_W, k=1)[0]
            iid = f"insured-{i:05d}-{uuid.uuid4().hex[:6]}"
            envs = self.rng.sample(ENV_IDS, k=self.rng.choices([1, 2, 3], weights=[.7, .25, .05])[0])
            users.append(UserProfile(iid, persona, device, browser, osn, country, region, gen_ip(self.rng, country), envs))
        return users

    def choose_task(self, persona: str) -> str:
        return weighted_choice(self.rng, PERSONA_TASKS.get(persona, {"explorer_flow": 1.0}))

    def make_event(self, user: UserProfile, session_id: str, ts: datetime, method: str, api: str,
                   tr: Dict[str, Any], code: int, page: str, seq: int, prev: Optional[datetime], start: datetime) -> Dict[str, Any]:
        self.record_counter += 1
        return {
            "record_id": f"evt-{self.record_counter:012d}",
            "insured_id": user.insured_id,
            "session_id": session_id,
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
            "device": user.primary_device,
            "browser": user.primary_browser,
            "os": user.primary_os,
            "user_agent": f"Mozilla/5.0 {user.primary_device}/{user.primary_os}/{user.primary_browser}",
            "ip": user.ip,
            "ip_country": user.country,
            "ip_region": user.region,
            "environment_id": self.rng.choice(user.environments),
            "session_action_seq": int(seq),
            "time_since_prev_action_ms": int((ts - prev).total_seconds() * 1000) if prev else 0,
            "session_duration_so_far_ms": int((ts - start).total_seconds() * 1000),
            "request_data_size_bytes": request_size(self.rng, method, api),
            "response_data_size_bytes": response_size(self.rng, method, api, code),
            "is_anomaly": 0,
            "anomaly_type": "normal",
        }

    def plan_normal_session(self, user: UserProfile, start: datetime) -> PlannedSession:
        user.sessions_started += 1
        sid = f"sess-{user.insured_id}-{user.sessions_started:04d}-{uuid.uuid4().hex[:6]}"
        p = PERSONAS[user.persona]
        seq = 1
        prev = None
        events = []
        ts = start

        method, api, name, tr = self.rng.choice(self.pages["login"])
        code = 200 if self.rng.random() > 0.08 else self.rng.choice([202, 206])
        events.append(self.make_event(user, sid, ts, method, api, tr, code, "login", seq, prev, start))
        prev = ts
        seq += 1

        if code in {202, 206} and "mfa" in self.pages:
            ts = prev + timedelta(seconds=self.rng.randint(20, 90))
            method, api, name, tr = self.rng.choice(self.pages["mfa"])
            events.append(self.make_event(user, sid, ts, method, api, tr, 200, "mfa", seq, prev, start))
            prev = ts
            seq += 1

        flow = [x for x in TASK_FLOWS[self.choose_task(user.persona)] if x in self.pages]
        if not flow:
            flow = [p for p in self.pages.keys() if p not in {"login", "mfa"}]
        actions_to_generate = self.rng.randint(*p["aps"])
        for idx in range(max(1, actions_to_generate - seq + 1)):
            page = flow[idx % len(flow)] if self.rng.random() < .9 else self.rng.choice(flow)
            method, api, name, tr = self.rng.choice(self.pages.get(page) or self.pages["login"])
            ts = prev + timedelta(seconds=self.rng.randint(*p["delay"]))
            code = http_code(self.rng, method, success_probability=0.975)
            events.append(self.make_event(user, sid, ts, method, api, tr, code, page, seq, prev, start))
            prev = ts
            seq += 1

            if self.rng.random() < self.args.heartbeat_probability:
                hb_tr = enrich_tracking("GET", "/heartbeat", "heartbeat", self.lookup)
                ts = prev + timedelta(seconds=self.rng.randint(30, 180))
                ev = self.make_event(user, sid, ts, "GET", "/heartbeat", hb_tr, 200, "global", seq, prev, start)
                ev["request_data_size_bytes"] = 10
                ev["response_data_size_bytes"] = 30
                events.append(ev)
                prev = ts
                seq += 1

        return PlannedSession(user=user, session_id=sid, events=events)

    def start_session(self):
        busy = {s.user.insured_id for s in self.active}
        candidates = [u for u in self.users if u.insured_id not in busy]
        if not candidates:
            return
        user = self.rng.choice(candidates)
        p = PERSONAS[user.persona]
        now = self.clock.current
        h0, h1 = p["hours"]
        # Anchor start near current time but keep normal users mostly in their usual hours.
        start = now
        if self.args.respect_persona_hours and not (h0 <= start.hour <= h1):
            start = start.replace(hour=self.rng.randint(h0, min(h1, 23)), minute=self.rng.randint(0, 59), second=self.rng.randint(0, 59))
        self.active.append(self.plan_normal_session(user, start))

    def emit(self, event: Dict[str, Any], key: str):
        if self.args.dry_run:
            print(json.dumps(event, ensure_ascii=False))
        else:
            self.producer.send(self.args.topic, key=key, value=event)
            if self.args.verbose:
                print(f"[{event['timestamp']}] NORMAL {event['insured_id']} {event['session_id']} {event['frontend_action_name']} {event['api_template']}", flush=True)

    def run(self):
        print(f"Starting V3.6 normal traffic simulator at {self.clock.current} speed={self.clock.speed}x", file=sys.stderr)
        wall_start = time.monotonic()
        try:
            while True:
                now = self.clock.tick()
                target = max(1, int(self.args.insured_count * self.clock.load_factor() * self.args.concurrency_factor))
                while len(self.active) < target:
                    self.start_session()
                    if len(self.active) >= self.args.insured_count:
                        break

                remaining = []
                for session in self.active:
                    if session.done:
                        continue
                    nxt = parse_dt(session.next_event["timestamp"])
                    if nxt <= now:
                        self.emit(session.next_event, session.user.insured_id)
                        session.next_index += 1
                    if not session.done:
                        remaining.append(session)
                self.active = remaining

                if self.args.duration > 0 and (time.monotonic() - wall_start) >= self.args.duration:
                    break
                time.sleep(0.01)
        except KeyboardInterrupt:
            print("Stopped by user", file=sys.stderr)
        finally:
            if self.producer:
                self.producer.flush()
                self.producer.close()


def main():
    parser = argparse.ArgumentParser(description="V3.6 normal traffic Kafka simulator")
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--backend-apis", default=str(DEFAULT_BACKEND_APIS))
    parser.add_argument("--actions-order", default=str(DEFAULT_ACTIONS_ORDER))
    parser.add_argument("--insured-count", type=int, default=80)
    parser.add_argument("--virtual-day-start", default="2026-03-24T08:00:00+01:00")
    parser.add_argument("--virtual-speed", type=float, default=60.0)
    parser.add_argument("--duration", type=int, default=0, help="Wall-clock seconds to run; 0=infinite")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--api-version", default="3.7.0")
    parser.add_argument("--acks", type=int, default=1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--concurrency-factor", type=float, default=0.35, help="Fraction of insured-count active at peak load")
    parser.add_argument("--heartbeat-probability", type=float, default=0.07)
    parser.add_argument("--respect-persona-hours", action="store_true")
    args = parser.parse_args()
    NormalTrafficSimulator(args).run()

if __name__ == "__main__":
    main()
