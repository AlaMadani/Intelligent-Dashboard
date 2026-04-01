#!/usr/bin/env python3
"""Shared runtime for graph-aware audit-trail simulation.

This module centralizes:
- actions_order-v2.json parsing
- realistic user/session generation
- graph-aware normal session planning
- structural + behavioural anomaly injection
- event payload building used by both the yearly dataset generator
  and the Kafka real-time simulator.
"""

from __future__ import annotations

import json
import random
import re
import unicodedata
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

AUDIT_TRAIL_VARIABLE_TO_FIELD = {
    "INSURED_ID": "insuredId",
    "STATUS": "status",
    "SESSION_ID": "sessionId",
    "ACTION": "action",
    "HTTP_CODE": "httpCode",
    "IP": "ip",
    "REQUEST_DATA": "requestData",
    "REQUEST_RETURN": "requestReturn",
    "CREATED_AT": "createdAt",
    "USER_AGENT": "userAgent",
    "TYPE": "type",
    "ENVIRONMENT_ID": "environmentId",
    "DEVICE": "device",
    "COMPANY_ID": "companyIdList",
    "COMPANY_GROUP_ID": "companyGroupIdList",
    "COMPANY_SECTION_ID": "companySectionIdList",
    "INSURER_ID": "insurerIdList",
    "INSURER_CODE_ID": "insurerCodeIdList",
    "HEALTHCARE_NETWORK_ID": "healthcareNetworkIdList",
    "DOMAIN_ID": "domainIdList",
    "SUBTYPE": "subType",
}

EU_GEO_PROFILE = {
    "FR": {
        "weight": 0.60,
        "cities": ["Paris", "Lyon", "Marseille", "Lille", "Bordeaux", "Nantes", "Toulouse", "Strasbourg"],
        "prefixes": [("2",), ("80", "12"), ("81", "64"), ("82", "64"), ("83", "112"), ("86", "192"), ("90",)],
    },
    "BE": {
        "weight": 0.08,
        "cities": ["Brussels", "Antwerp", "Ghent", "Liège"],
        "prefixes": [("37", "60"), ("46", "18"), ("62", "235"), ("91", "176")],
    },
    "DE": {
        "weight": 0.08,
        "cities": ["Berlin", "Hamburg", "Munich", "Frankfurt", "Cologne"],
        "prefixes": [("46", "5"), ("80", "128"), ("87", "123"), ("91", "0"), ("95", "90")],
    },
    "ES": {
        "weight": 0.07,
        "cities": ["Madrid", "Barcelona", "Valencia", "Seville", "Bilbao"],
        "prefixes": [("80", "58"), ("81", "32"), ("83", "32"), ("88", "0"), ("95", "16")],
    },
    "IT": {
        "weight": 0.06,
        "cities": ["Milan", "Rome", "Turin", "Bologna", "Naples"],
        "prefixes": [("79", "0"), ("80", "16"), ("82", "48"), ("87", "0"), ("93", "32")],
    },
    "NL": {
        "weight": 0.05,
        "cities": ["Amsterdam", "Rotterdam", "Utrecht", "Eindhoven"],
        "prefixes": [("77", "160"), ("80", "56"), ("83", "80"), ("84", "104")],
    },
    "PT": {
        "weight": 0.03,
        "cities": ["Lisbon", "Porto", "Braga"],
        "prefixes": [("85", "240"), ("88", "157"), ("94", "60")],
    },
    "CH": {
        "weight": 0.02,
        "cities": ["Geneva", "Lausanne", "Zurich", "Basel"],
        "prefixes": [("77", "56"), ("80", "218"), ("85", "0")],
    },
    "LU": {
        "weight": 0.01,
        "cities": ["Luxembourg"],
        "prefixes": [("188", "42"), ("188", "43")],
    },
}

BROWSER_POOL = [
    ("WEB", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"),
    ("WEB", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"),
    ("WEB", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"),
    ("MOBILE_ANDROID", "mobileapp/4.2.1 (android; sdk=34; locale=fr-FR)"),
    ("MOBILE_ANDROID", "mobileapp/4.1.7 (android; sdk=33; locale=fr-FR)"),
    ("MOBILE_IOS", "mobileapp/4.3.0 (ios; build=812; locale=fr-FR)"),
    ("MOBILE_IOS", "mobileapp/4.2.4 (ios; build=805; locale=fr-FR)"),
]

BACKGROUND_CALLS = {
    "heartbeat": "GET /heartbeat",
    "token_refresh": "POST /auth/refresh",
    "init": "GET /init",
}

ROUTE_NODE_TO_KEY = {
    "requests": "Requests",
    "refunds": "Refunds",
    "documents": "Documents",
    "beneficiaries": "Beneficiaries",
    "personal_info": "PersonalInformation",
    "banking_info": "BankingInformation",
    "preferences": "GlobalPreferences",
    "help": "Help",
    "home": "Home",
    "logout": "logout",
    "login": "login",
    "mfa": "mfa",
    "mfa_email_list": "mfa_email_list",
}

DEFAULT_STRUCTURAL_ANOMALIES = [
    "skip_login",
    "invalid_transition",
    "mfa_bypass",
    "post_logout_action",
    "wrong_route_api",
]
DEFAULT_BEHAVIOURAL_ANOMALIES = [
    "rapid_fire",
    "geo_jump",
    "unusual_hour",
    "repeated_fail",
]


@dataclass(frozen=True)
class GraphAction:
    state: str
    name: str
    api_calls: Tuple[str, ...]
    kind: str = "route"
    next_state: Optional[str] = None
    branches: Tuple[Tuple[str, str], ...] = tuple()


@dataclass
class PlannedStep:
    route_state: str
    frontend_action: str
    api_call: str
    sequence_in_session: int
    session_number: int
    timestamp: datetime
    expected_next_states: Tuple[str, ...]
    transition_valid: int = 1
    is_anomaly: int = 0
    anomaly_type: str = ""
    flow_family: str = "normal_navigation"
    forced_status: Optional[str] = None
    forced_http_code: Optional[str] = None


@dataclass
class UserProfile:
    insured_id: str
    country_code: str
    city: str
    current_ip: str
    home_ip_prefix: Tuple[str, ...]
    device: str
    user_agent: str
    company_id: int
    company_group_id: int
    company_section_id: int
    insurer_id: int
    insurer_code_id: int
    healthcare_network_id: int
    domain_id: int
    environment_id: int
    session_id: Optional[str] = None
    sessions_generated: int = 0
    actions_generated: int = 0
    last_event_at: Optional[datetime] = None
    last_api_call: Optional[str] = None
    pending_plan: List[PlannedStep] = field(default_factory=list)


class ActionOrderGraph:
    def __init__(self, payload: Dict[str, object]):
        self.payload = payload
        self.global_calls = payload.get("global", {}) if isinstance(payload, dict) else {}
        self.auth_start = (payload.get("auth_flow", {}) or {}).get("start", "login")
        self.auth_steps = self._parse_auth_steps((payload.get("auth_flow", {}) or {}).get("steps", []))
        self.route_actions = self._parse_routes(payload.get("routes", {}) or {})
        self.post_login_gates = self._parse_post_login_gates(payload.get("post_login_gates", {}) or {})
        self.transition_edges = (payload.get("transition_graph", {}) or {}).get("edges", [])
        self.home_targets = self._derive_home_targets()
        self.all_route_keys = list(self.route_actions.keys())
        self.all_api_calls = sorted({api for actions in self.route_actions.values() for act in actions for api in act.api_calls} |
                                    {api for actions in self.auth_steps.values() for act in actions for api in act.api_calls} |
                                    {api for acts in self.post_login_gates.values() for act in acts for api in act.api_calls})

    def _parse_auth_steps(self, steps: Sequence[Dict[str, object]]) -> Dict[str, List[GraphAction]]:
        result: Dict[str, List[GraphAction]] = {}
        for step in steps:
            if not isinstance(step, dict):
                continue
            state = str(step.get("state", ""))
            name = str(step.get("action", state))
            api_calls = tuple(str(x) for x in step.get("api", []) if x)
            next_state = str(step.get("next")) if step.get("next") is not None else None
            branches_raw = []
            for b in step.get("branches", []) or []:
                if not isinstance(b, dict):
                    continue
                if "default" in b:
                    branches_raw.append(("default", str(b.get("default"))))
                elif "next" in b:
                    branches_raw.append((str(b.get("if_status", "condition")), str(b.get("next"))))
            action = GraphAction(
                state=state,
                name=name,
                api_calls=api_calls,
                kind="auth",
                next_state=next_state,
                branches=tuple(branches_raw),
            )
            result.setdefault(state, []).append(action)
        return result

    def _parse_routes(self, routes: Dict[str, object]) -> Dict[str, List[GraphAction]]:
        result: Dict[str, List[GraphAction]] = {}
        for route_name, route_obj in routes.items():
            if not isinstance(route_obj, dict):
                continue
            actions: List[GraphAction] = []
            for item in route_obj.get("user_actions", []) or []:
                if not isinstance(item, dict):
                    continue
                actions.append(
                    GraphAction(
                        state=str(route_name),
                        name=str(item.get("name", route_name)),
                        api_calls=tuple(str(x) for x in item.get("api", []) if x),
                        kind="route",
                    )
                )
            result[str(route_name)] = actions
        return result

    def _parse_post_login_gates(self, gates: Dict[str, object]) -> Dict[str, List[GraphAction]]:
        result: Dict[str, List[GraphAction]] = {}
        for gate_name, gate_obj in gates.items():
            if not isinstance(gate_obj, dict):
                continue
            bucket: List[GraphAction] = []
            if "api" in gate_obj:
                bucket.append(GraphAction(
                    state=str(gate_obj.get("route", gate_name)),
                    name=str(gate_obj.get("action", gate_name)),
                    api_calls=tuple(str(x) for x in gate_obj.get("api", []) if x),
                    kind="post_login_gate",
                ))
            for act in gate_obj.get("actions", []) or []:
                if not isinstance(act, dict):
                    continue
                bucket.append(GraphAction(
                    state=str(gate_obj.get("route", gate_name)),
                    name=str(act.get("name", gate_name)),
                    api_calls=tuple(str(x) for x in act.get("api", []) if x),
                    kind="post_login_gate",
                ))
            if bucket:
                result[str(gate_name)] = bucket
        return result

    def _derive_home_targets(self) -> List[str]:
        targets: List[str] = []
        for edge in self.transition_edges:
            if not isinstance(edge, dict):
                continue
            if str(edge.get("from", "")).lower() == "home":
                to_node = str(edge.get("to", ""))
                mapped = ROUTE_NODE_TO_KEY.get(to_node, to_node)
                if mapped in self.route_actions:
                    targets.append(mapped)
        if targets:
            return targets
        return [name for name in self.route_actions.keys() if name != "Home"]

    def allowed_next_states(self, state: str) -> List[str]:
        state_key = str(state)
        if state_key in self.auth_steps:
            output: List[str] = []
            for action in self.auth_steps[state_key]:
                if action.branches:
                    output.extend(dest for _, dest in action.branches)
                elif action.next_state:
                    output.append(action.next_state)
            return sorted(set(x for x in output if x))
        if state_key == "home" or state_key == "Home":
            return list(self.home_targets)
        if state_key in self.route_actions:
            return [state_key, "logout", "home"]
        if state_key == "logout":
            return ["login"]
        return ["home", "logout"]


def load_action_order_graph(path: str) -> ActionOrderGraph:
    with open(path, "r", encoding="utf-8") as fh:
        payload = json.load(fh)
    return ActionOrderGraph(payload)


def normalize_text(value: str) -> str:
    return unicodedata.normalize("NFKD", value or "").encode("ascii", "ignore").decode("ascii").lower()


def slugify(value: str) -> str:
    value = normalize_text(value)
    value = re.sub(r"[^a-z0-9]+", "_", value).strip("_")
    return value or "unknown"


def humanize_identifier(value: str) -> str:
    value = value.replace("/", " ").replace("-", " ").replace("_", " ").replace("*", " wildcard ")
    value = re.sub(r"\s+", " ", value).strip()
    return value or "unknown"


def weighted_choice(items: Sequence[Tuple[object, float]], rng: random.Random):
    total = sum(weight for _, weight in items)
    pick = rng.random() * total
    upto = 0.0
    for item, weight in items:
        upto += weight
        if upto >= pick:
            return item
    return items[-1][0]


def build_pools() -> Dict[str, List[int]]:
    return {
        "company_id": [27011, 27012, 27013, 27021],
        "company_group_id": [12001, 12002, 12003],
        "company_section_id": [110027, 110028, 110029],
        "insurer_id": [1, 2, 3],
        "insurer_code_id": [302, 303, 304],
        "healthcare_network_id": [1, 2, 3],
        "domain_id": [10, 11, 12],
        "environment_id": [10, 11],
    }


def random_public_ip(prefix: Tuple[str, ...], rng: random.Random) -> str:
    parts = [int(part) for part in prefix]
    while len(parts) < 4:
        if len(parts) == 3:
            parts.append(rng.randint(2, 250))
        else:
            parts.append(rng.randint(0, 255))
    if parts[0] in {10, 127, 169, 172, 192}:
        parts[0] = rng.choice([2, 37, 46, 62, 77, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95])
    return ".".join(str(p) for p in parts[:4])


def random_foreign_ip(rng: random.Random) -> str:
    octets = [rng.choice([41, 102, 103, 104, 105, 196, 197, 198]), rng.randint(0, 255), rng.randint(0, 255), rng.randint(2, 250)]
    return ".".join(str(x) for x in octets)


def build_users(rng: random.Random, count: int) -> List[UserProfile]:
    pools = build_pools()
    choices = [(country, profile["weight"]) for country, profile in EU_GEO_PROFILE.items()]
    users: List[UserProfile] = []
    for _ in range(count):
        country_code = weighted_choice(choices, rng)
        geo = EU_GEO_PROFILE[country_code]
        city = rng.choice(geo["cities"])
        prefix = rng.choice(geo["prefixes"])
        device, user_agent = rng.choice(BROWSER_POOL)
        users.append(UserProfile(
            insured_id=f"{rng.randint(10000000, 99999999)}",
            country_code=country_code,
            city=city,
            current_ip=random_public_ip(prefix, rng),
            home_ip_prefix=prefix,
            device=device,
            user_agent=user_agent,
            company_id=rng.choice(pools["company_id"]),
            company_group_id=rng.choice(pools["company_group_id"]),
            company_section_id=rng.choice(pools["company_section_id"]),
            insurer_id=rng.choice(pools["insurer_id"]),
            insurer_code_id=rng.choice(pools["insurer_code_id"]),
            healthcare_network_id=rng.choice(pools["healthcare_network_id"]),
            domain_id=rng.choice(pools["domain_id"]),
            environment_id=rng.choice(pools["environment_id"]),
        ))
    return users


def maybe_rotate_ip(user: UserProfile, rng: random.Random) -> None:
    if user.device.startswith("MOBILE") and rng.random() < 0.11:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)
    elif user.device == "WEB" and rng.random() < 0.04:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)


def api_category(api_call: str, frontend_action: str = "", route_state: str = "") -> str:
    text = normalize_text(f"{api_call} {frontend_action} {route_state}")
    if "/auth/login" in text or "/auth/logout" in text or "mfa" in text or "auth" in text:
        return "logging"
    if "/documents" in text or "document" in text or "wallet" in text or "tp-card" in text or "certificate" in text:
        return "document"
    if "refund" in text or "remboursement" in text or "requests" in text or "claim" in text:
        return "refund"
    if "contact" in text or "message" in text or "help" in text or "debug-report" in text:
        return "contact"
    if "rib" in text or "bank" in text or "subscription" in text or "teletransmission" in text or "social-security" in text:
        return "banking"
    if "benef" in text or "insured" in text or "adhesion" in text or "tp card" in text:
        return "insured"
    if "home" in text or "all-info" in text or "info" in text or "address" in text or "personal" in text or "preferences" in text:
        return "open"
    return "other"


def category_to_type(category: str) -> str:
    return {
        "logging": "LOGGING_ACTIONS",
        "document": "DOCUMENT_ACTIONS",
        "refund": "REFUND_ACTIONS",
        "contact": "CONTACT_ACTIONS",
        "banking": "BANKING_ACTIONS",
        "insured": "INSURED_ACTIONS",
        "open": "OPEN_ACTIONS",
        "other": "OTHER_ACTIONS",
    }.get(category, "OTHER_ACTIONS")


def action_subtype(api_call: str, frontend_action: str) -> str:
    return slugify(frontend_action or api_call)


def choose_normal_http_status(step: PlannedStep, rng: random.Random) -> str:
    api_text = normalize_text(step.api_call)
    if step.forced_http_code:
        return step.forced_http_code
    if step.route_state == "login":
        return str(rng.choice([200, 204]))
    if "post" in api_text:
        return str(rng.choice([200, 201, 202]))
    if "put" in api_text:
        return str(rng.choice([200, 204]))
    if "delete" in api_text:
        return str(rng.choice([200, 204]))
    return "200"


def choose_normal_status(step: PlannedStep, rng: random.Random) -> str:
    if step.forced_status:
        return step.forced_status
    category = api_category(step.api_call, step.frontend_action, step.route_state)
    failure_prob = {
        "logging": 0.02,
        "document": 0.04,
        "refund": 0.06,
        "contact": 0.03,
        "banking": 0.07,
        "insured": 0.03,
        "open": 0.02,
        "other": 0.04,
    }.get(category, 0.04)
    return "KO" if rng.random() < failure_prob else "OK"


def request_payload(step: PlannedStep, user: UserProfile, rng: random.Random) -> Optional[str]:
    category = api_category(step.api_call, step.frontend_action, step.route_state)
    payload: Dict[str, object]
    if category == "logging":
        payload = {
            "login": user.insured_id,
            "channel": "mobile" if user.device.startswith("MOBILE") else "web",
            "route": step.route_state,
        }
    elif category == "document":
        payload = {
            "document": step.frontend_action,
            "api": step.api_call,
            "tag": rng.choice(["medical", "administrative", "identity", "family"]),
        }
    elif category == "refund":
        payload = {
            "action": step.frontend_action,
            "claimAmount": round(rng.uniform(18, 240), 2),
            "currency": "EUR",
        }
    elif category == "contact":
        payload = {
            "subject": step.frontend_action,
            "message": rng.choice([
                "Besoin d'assistance sur mon espace assuré",
                "Question sur un document ou un remboursement",
                "Demande d'information complémentaire",
            ]),
            "priority": rng.choice(["low", "normal", "normal", "high"]),
        }
    elif category == "banking":
        payload = {
            "iban_last4": f"{rng.randint(1000, 9999)}",
            "bic": rng.choice(["AGRIFRPP", "BNPAFRPP", "SOGEFRPP", "CMCIFRPP"]),
            "api": step.api_call,
        }
    else:
        payload = {
            "insuredId": user.insured_id,
            "action": step.frontend_action,
            "api": step.api_call,
        }
    if step.api_call in {BACKGROUND_CALLS["heartbeat"], BACKGROUND_CALLS["init"]}:
        return None
    return json.dumps(payload, ensure_ascii=True)


def response_payload(status: str, http_code: str, step: PlannedStep, rng: random.Random) -> Optional[str]:
    if step.api_call == BACKGROUND_CALLS["heartbeat"] and status == "OK":
        return None
    if status == "OK":
        payload = {"status": "success", "reference": f"REF-{rng.randint(100000, 999999)}"}
        if api_category(step.api_call, step.frontend_action, step.route_state) == "refund":
            payload["claimId"] = f"CLM-{rng.randint(1000000, 9999999)}"
        return json.dumps(payload, ensure_ascii=True)
    if http_code in {"401", "403"}:
        return json.dumps({"status": "error", "code": "AUTH_FAILED"}, ensure_ascii=True)
    if http_code in {"409", "422"}:
        return json.dumps({"status": "error", "code": "BUSINESS_RULE_REJECTED"}, ensure_ascii=True)
    return json.dumps({"status": "error", "code": "VALIDATION_ERROR"}, ensure_ascii=True)


def generate_id_list(base_value: int, rng: random.Random, variability: float = 0.10) -> List[int]:
    if rng.random() < variability:
        return []
    return [base_value]


def format_created_at(now: datetime, mode: str) -> str:
    if mode == "java":
        return now.strftime("%Y-%m-%dT%H:%M:%S")
    return now.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def infer_expected_next_states(graph: ActionOrderGraph, current_state: str) -> Tuple[str, ...]:
    states = graph.allowed_next_states(current_state)
    return tuple(states)


def choose_login_branch(rng: random.Random) -> int:
    x = rng.random()
    if x < 0.02:
        return 208  # spam warning
    if x < 0.10:
        return 206  # mfa email list
    if x < 0.28:
        return 202  # mfa
    return 200


def choose_event_delay_seconds(category: str, rng: random.Random, rapid_fire: bool = False) -> int:
    if rapid_fire:
        return rng.randint(1, 5)
    ranges = {
        "logging": (8, 70),
        "document": (30, 260),
        "refund": (50, 420),
        "contact": (40, 300),
        "banking": (55, 420),
        "insured": (35, 320),
        "open": (25, 240),
        "other": (20, 180),
    }
    low, high = ranges.get(category, (20, 180))
    return rng.randint(low, high)


def random_time_in_window(start: datetime, end: datetime, rng: random.Random) -> datetime:
    total_seconds = max(1, int((end - start).total_seconds()))
    return start + timedelta(seconds=rng.randint(0, total_seconds))


def auth_flow_plan(graph: ActionOrderGraph, session_number: int, start_time: datetime, rng: random.Random,
                   flow_family: str = "main_login") -> Tuple[List[PlannedStep], datetime]:
    steps: List[PlannedStep] = []
    now = start_time
    seq = 1

    def append_step(state: str, action: GraphAction, expected: Sequence[str], forced_http: Optional[str] = None):
        nonlocal seq, now
        api_call = rng.choice(action.api_calls) if action.api_calls else BACKGROUND_CALLS["init"]
        steps.append(PlannedStep(
            route_state=state,
            frontend_action=action.name,
            api_call=api_call,
            sequence_in_session=seq,
            session_number=session_number,
            timestamp=now,
            expected_next_states=tuple(expected),
            flow_family=flow_family,
            forced_http_code=forced_http,
        ))
        seq += 1
        now += timedelta(seconds=choose_event_delay_seconds(api_category(api_call, action.name, state), rng))

    login_actions = graph.auth_steps.get("login", [])
    if not login_actions:
        login_actions = [GraphAction(state="login", name="submit_credentials", api_calls=("POST /auth/login",), kind="auth")]
    login_action = login_actions[0]
    branch_status = choose_login_branch(rng)
    append_step("login", login_action, ["home", "mfa", "mfa_email_list", "spam_warning"], forced_http=str(branch_status))

    current_state = "home"
    if branch_status == 208:
        current_state = "spam_warning"
        spam_action = next((x for x in graph.auth_steps.get("spam_warning", []) if x.name == "remove_from_spam"), None)
        if spam_action:
            append_step("spam_warning", spam_action, ["login"])
        append_step("login", login_action, ["home", "mfa", "mfa_email_list", "spam_warning"], forced_http="200")
        current_state = "home"
    elif branch_status == 206:
        current_state = "mfa_email_list"
        email_action = graph.auth_steps.get("mfa_email_list", [GraphAction(state="mfa_email_list", name="select_email", api_calls=("POST /auth/select-email",))])[0]
        append_step("mfa_email_list", email_action, ["mfa"])
        current_state = "mfa"
    elif branch_status == 202:
        current_state = "mfa"

    if current_state == "mfa":
        mfa_actions = graph.auth_steps.get("mfa", [])
        validate = next((x for x in mfa_actions if "validate" in x.name), None)
        resend = next((x for x in mfa_actions if "resend" in x.name or "switch" in x.name), None)
        if resend and rng.random() < 0.18:
            append_step("mfa", resend, ["mfa"])
        append_step("mfa", validate or GraphAction(state="mfa", name="validate_code", api_calls=("POST /auth/validate-mfa",)), ["home"])

    return steps, now


def optional_post_login_gates(graph: ActionOrderGraph, session_number: int, start_time: datetime, rng: random.Random,
                              probability: float = 0.18) -> Tuple[List[PlannedStep], datetime]:
    steps: List[PlannedStep] = []
    now = start_time
    seq_offset = 0
    for gate_name, actions in graph.post_login_gates.items():
        if not actions or rng.random() >= probability:
            continue
        chosen_count = 1 if gate_name == "validate_legal_pages" else rng.randint(1, len(actions))
        for action in rng.sample(actions, k=chosen_count):
            seq_offset += 1
            api_call = rng.choice(action.api_calls)
            steps.append(PlannedStep(
                route_state=action.state,
                frontend_action=action.name,
                api_call=api_call,
                sequence_in_session=seq_offset,
                session_number=session_number,
                timestamp=now,
                expected_next_states=("home",),
                flow_family="post_login_gate",
            ))
            now += timedelta(seconds=choose_event_delay_seconds(api_category(api_call, action.name, action.state), rng))
    return steps, now


def route_navigation_plan(graph: ActionOrderGraph, session_number: int, start_time: datetime, rng: random.Random,
                          max_steps: int) -> Tuple[List[PlannedStep], datetime]:
    steps: List[PlannedStep] = []
    now = start_time
    seq = 1
    if max_steps <= 0 or not graph.home_targets:
        return steps, now

    route_count = min(len(graph.home_targets), max(1, min(3, rng.randint(1, 3), max_steps)))
    chosen_routes = rng.sample(graph.home_targets, k=route_count)
    steps_left = max_steps
    for route in chosen_routes:
        if steps_left <= 0:
            break
        if rng.random() < 0.80 and steps_left > 1:
            steps.append(PlannedStep(
                route_state="background",
                frontend_action="heartbeat",
                api_call=BACKGROUND_CALLS["heartbeat"],
                sequence_in_session=seq,
                session_number=session_number,
                timestamp=now,
                expected_next_states=(route,),
                flow_family="background_navigation",
            ))
            seq += 1
            steps_left -= 1
            now += timedelta(seconds=rng.randint(2, 20))
        actions = graph.route_actions.get(route, [])
        if not actions:
            continue
        local_budget = min(steps_left, rng.randint(1, min(3, steps_left)))
        chosen_actions = [rng.choice(actions) for _ in range(local_budget)]
        for action in chosen_actions:
            api_call = rng.choice(action.api_calls)
            steps.append(PlannedStep(
                route_state=route,
                frontend_action=action.name,
                api_call=api_call,
                sequence_in_session=seq,
                session_number=session_number,
                timestamp=now,
                expected_next_states=(route, "home", "logout"),
                flow_family="normal_navigation",
            ))
            seq += 1
            steps_left -= 1
            now += timedelta(seconds=choose_event_delay_seconds(api_category(api_call, action.name, route), rng))
            if steps_left <= 0:
                break
            if rng.random() < 0.12 and steps_left > 0:
                steps.append(PlannedStep(
                    route_state="background",
                    frontend_action="token_refresh_background",
                    api_call=BACKGROUND_CALLS["token_refresh"],
                    sequence_in_session=seq,
                    session_number=session_number,
                    timestamp=now,
                    expected_next_states=(route,),
                    flow_family="background_navigation",
                ))
                seq += 1
                steps_left -= 1
                now += timedelta(seconds=rng.randint(1, 8))
        if steps_left <= 0:
            break
    return steps, now


def logout_plan(graph: ActionOrderGraph, session_number: int, start_time: datetime, sequence_in_session: int,
                rng: random.Random) -> PlannedStep:
    logout_actions = graph.auth_steps.get("logout", [GraphAction(state="logout", name="explicit_logout", api_calls=("GET /auth/logout",))])
    chosen = logout_actions[0]
    return PlannedStep(
        route_state="logout",
        frontend_action=chosen.name,
        api_call=rng.choice(chosen.api_calls),
        sequence_in_session=sequence_in_session,
        session_number=session_number,
        timestamp=start_time,
        expected_next_states=("login",),
        flow_family="normal_navigation",
    )


def build_normal_session_plan(graph: ActionOrderGraph, user: UserProfile, start_time: datetime, desired_len: int,
                              rng: random.Random) -> List[PlannedStep]:
    desired_len = max(4, desired_len)
    session_number = user.sessions_generated + 1
    steps: List[PlannedStep] = []

    if rng.random() < 0.55:
        steps.append(PlannedStep(
            route_state="background",
            frontend_action="unauthenticated_boot",
            api_call=BACKGROUND_CALLS["init"],
            sequence_in_session=1,
            session_number=session_number,
            timestamp=start_time,
            expected_next_states=("login",),
            flow_family="boot",
        ))
        start_time += timedelta(seconds=rng.randint(1, 15))

    auth_steps, now = auth_flow_plan(graph, session_number, start_time, rng)
    seq_base = len(steps)
    for idx, step in enumerate(auth_steps, start=1):
        step.sequence_in_session = seq_base + idx
    steps.extend(auth_steps)

    gate_steps, now = optional_post_login_gates(graph, session_number, now, rng)
    seq_base = len(steps)
    for idx, step in enumerate(gate_steps, start=1):
        step.sequence_in_session = seq_base + idx
    steps.extend(gate_steps)

    remaining_for_routes = max(0, desired_len - len(steps) - 1)
    route_steps, now = route_navigation_plan(graph, session_number, now, rng, remaining_for_routes)
    seq_base = len(steps)
    for idx, step in enumerate(route_steps, start=1):
        step.sequence_in_session = seq_base + idx
    steps.extend(route_steps)

    logout_step = logout_plan(graph, session_number, now, len(steps) + 1, rng)
    steps.append(logout_step)

    # If the plan is longer than desired_len, keep the earliest steps + logout.
    if len(steps) > desired_len:
        body = steps[: max(1, desired_len - 1)]
        last = steps[-1]
        steps = body + [last]
        for idx, step in enumerate(steps, start=1):
            step.sequence_in_session = idx
            if idx > 1 and step.timestamp <= steps[idx - 2].timestamp:
                step.timestamp = steps[idx - 2].timestamp + timedelta(seconds=1)

    user.sessions_generated += 1
    return steps


def apply_behavioural_anomaly(steps: List[PlannedStep], anomaly_type: str, rng: random.Random) -> List[PlannedStep]:
    if not steps:
        return steps
    if anomaly_type == "rapid_fire":
        base = steps[0].timestamp
        for i, step in enumerate(steps):
            step.is_anomaly = 1
            step.anomaly_type = anomaly_type
            if i == 0:
                continue
            base = base + timedelta(seconds=rng.randint(1, 4))
            step.timestamp = base
    elif anomaly_type == "unusual_hour":
        shift_hour = rng.randint(2, 4)
        base_date = steps[0].timestamp
        base = base_date.replace(hour=shift_hour, minute=rng.randint(0, 59), second=rng.randint(0, 59))
        if base.tzinfo is None:
            base = base.replace(tzinfo=timezone.utc)
        for i, step in enumerate(steps):
            step.is_anomaly = 1
            step.anomaly_type = anomaly_type
            if i == 0:
                step.timestamp = base
            else:
                step.timestamp = steps[i - 1].timestamp + timedelta(seconds=max(1, int((step.timestamp - steps[i - 1].timestamp).total_seconds())))
    elif anomaly_type == "repeated_fail":
        for step in steps[1:-1][: max(1, len(steps) // 2)]:
            step.is_anomaly = 1
            step.anomaly_type = anomaly_type
            step.forced_status = "KO"
            step.forced_http_code = rng.choice(["400", "409", "422"])
    elif anomaly_type == "geo_jump":
        for step in steps[len(steps)//2:]:
            step.is_anomaly = 1
            step.anomaly_type = anomaly_type
    return steps


def apply_structural_anomaly(graph: ActionOrderGraph, steps: List[PlannedStep], anomaly_type: str,
                             rng: random.Random) -> List[PlannedStep]:
    if not steps:
        return steps
    for step in steps:
        step.is_anomaly = 1
        step.anomaly_type = anomaly_type

    if anomaly_type == "skip_login":
        route = rng.choice(graph.home_targets) if graph.home_targets else rng.choice(graph.all_route_keys)
        action = rng.choice(graph.route_actions.get(route, [GraphAction(state=route, name="unexpected_route_action", api_calls=("GET /documents",))]))
        steps[0].route_state = route
        steps[0].frontend_action = action.name
        steps[0].api_call = rng.choice(action.api_calls)
        steps[0].transition_valid = 0
        steps[0].expected_next_states = ("login",)
    elif anomaly_type == "invalid_transition":
        idx = min(len(steps) - 2, max(1, len(steps) // 2))
        wrong_api = rng.choice([api for api in graph.all_api_calls if api != steps[idx].api_call]) if graph.all_api_calls else "PUT /insured/rib"
        steps[idx].api_call = wrong_api
        steps[idx].route_state = "InvalidState"
        steps[idx].frontend_action = "invalid_transition"
        steps[idx].transition_valid = 0
        steps[idx].expected_next_states = tuple()
    elif anomaly_type == "mfa_bypass":
        login_steps = [s for s in steps if s.route_state == "login"]
        if login_steps:
            login_steps[0].forced_http_code = "202"
        filtered = []
        skipped_mfa = False
        for step in steps:
            if step.route_state in {"mfa", "mfa_email_list"}:
                skipped_mfa = True
                continue
            filtered.append(step)
        if skipped_mfa and len(filtered) >= 2:
            filtered[1].transition_valid = 0
            filtered[1].anomaly_type = anomaly_type
        steps = filtered
    elif anomaly_type == "post_logout_action":
        last = steps[-1]
        route = rng.choice(graph.home_targets) if graph.home_targets else "Documents"
        actions = graph.route_actions.get(route, [GraphAction(state=route, name="post_logout_action", api_calls=("GET /documents/file",))])
        action = rng.choice(actions)
        steps.append(PlannedStep(
            route_state=route,
            frontend_action=action.name,
            api_call=rng.choice(action.api_calls),
            sequence_in_session=last.sequence_in_session + 1,
            session_number=last.session_number,
            timestamp=last.timestamp + timedelta(seconds=rng.randint(1, 8)),
            expected_next_states=("login",),
            transition_valid=0,
            is_anomaly=1,
            anomaly_type=anomaly_type,
            flow_family="structural_anomaly",
        ))
    elif anomaly_type == "wrong_route_api":
        candidates = [s for s in steps if s.route_state in graph.route_actions]
        if candidates:
            picked = rng.choice(candidates)
            wrong_route = rng.choice([r for r in graph.route_actions.keys() if r != picked.route_state]) if len(graph.route_actions) > 1 else picked.route_state
            wrong_action = rng.choice(graph.route_actions.get(wrong_route, [GraphAction(state=wrong_route, name="wrong_route_api", api_calls=("GET /all-info?actionKey=requests",))]))
            picked.api_call = rng.choice(wrong_action.api_calls)
            picked.transition_valid = 0
            picked.frontend_action = f"wrong_route_api::{wrong_action.name}"
            picked.expected_next_states = tuple(graph.allowed_next_states(picked.route_state))
    for idx, step in enumerate(steps, start=1):
        step.sequence_in_session = idx
    return steps


def build_session_plan(graph: ActionOrderGraph, user: UserProfile, start_time: datetime, desired_len: int,
                       rng: random.Random, structural_anomaly_rate: float = 0.03,
                       behavioural_anomaly_rate: float = 0.05) -> List[PlannedStep]:
    plan = build_normal_session_plan(graph, user, start_time, desired_len, rng)
    if rng.random() < structural_anomaly_rate:
        plan = apply_structural_anomaly(graph, plan, rng.choice(DEFAULT_STRUCTURAL_ANOMALIES), rng)
    elif rng.random() < behavioural_anomaly_rate:
        plan = apply_behavioural_anomaly(plan, rng.choice(DEFAULT_BEHAVIOURAL_ANOMALIES), rng)
    return plan


def apply_excludes(event: Dict[str, object], excludes: Sequence[str]) -> Dict[str, object]:
    for exclude in excludes:
        field_name = AUDIT_TRAIL_VARIABLE_TO_FIELD.get(exclude)
        if field_name and field_name in event:
            event[field_name] = None
    return event


def prune_nones(event: Dict[str, object]) -> Dict[str, object]:
    return {key: value for key, value in event.items() if value is not None}


def planned_step_to_event(step: PlannedStep, user: UserProfile, graph: ActionOrderGraph,
                          rng: random.Random, created_at_format: str = "kibana") -> Dict[str, object]:
    maybe_rotate_ip(user, rng)
    status = choose_normal_status(step, rng)
    http_code = choose_normal_http_status(step, rng)
    if status == "KO" and http_code in {"200", "201", "202", "204"}:
        http_code = rng.choice(["400", "401", "403", "409", "422"])
    if status == "OK" and http_code in {"400", "401", "403", "409", "422", "500"}:
        http_code = "200"

    current_ip = user.current_ip
    if step.is_anomaly and step.anomaly_type == "geo_jump" and step.sequence_in_session > max(2, len(user.pending_plan) // 2):
        current_ip = random_foreign_ip(rng)

    if user.session_id is None or step.sequence_in_session == 1:
        user.session_id = str(rng.randint(100000, 9999999))

    category = api_category(step.api_call, step.frontend_action, step.route_state)
    event: Dict[str, object] = {
        "id": str(uuid.uuid4()),
        "insuredId": user.insured_id,
        "status": status,
        "sessionId": user.session_id,
        "action": step.frontend_action,
        "httpCode": int(http_code),
        "ip": current_ip,
        "userAgent": user.user_agent,
        "requestData": request_payload(step, user, rng),
        "requestReturn": response_payload(status, http_code, step, rng),
        "createdAt": format_created_at(step.timestamp, created_at_format),
        "type": category_to_type(category),
        "environmentId": int(user.environment_id),
        "device": user.device,
        "companyIdList": generate_id_list(user.company_id, rng, variability=0.10),
        "companyGroupIdList": generate_id_list(user.company_group_id, rng, variability=0.08),
        "insurerIdList": generate_id_list(user.insurer_id, rng, variability=0.10),
        "companySectionIdList": generate_id_list(user.company_section_id, rng, variability=0.10),
        "insurerCodeIdList": generate_id_list(user.insurer_code_id, rng, variability=0.10),
        "healthcareNetworkIdList": generate_id_list(user.healthcare_network_id, rng, variability=0.15),
        "domainIdList": generate_id_list(user.domain_id, rng, variability=0.10),
        "subType": action_subtype(step.api_call, step.frontend_action),
        "countryCode": user.country_code,
        "city": user.city,
        "month": step.timestamp.strftime("%Y-%m"),
        "sessionNumber": step.session_number,
        "sequenceInSession": step.sequence_in_session,
        "is_anomaly": int(step.is_anomaly),
        "anomaly_type": step.anomaly_type,
        "routeState": step.route_state,
        "frontendAction": step.frontend_action,
        "apiCall": step.api_call,
        "prevApiCall": user.last_api_call,
        "transitionValid": int(step.transition_valid),
        "flowFamily": step.flow_family,
        "expectedNextStates": json.dumps(list(step.expected_next_states), ensure_ascii=True),
    }
    event = prune_nones(event)
    user.actions_generated += 1
    user.last_event_at = step.timestamp
    user.last_api_call = step.api_call
    if step.route_state == "logout":
        user.session_id = None
        user.last_api_call = None
    return event
