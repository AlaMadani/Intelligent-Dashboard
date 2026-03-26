#!/usr/bin/env python3
import argparse
import json
import math
import os
import random
import re
import sys
import time
import unicodedata
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Deque, Dict, Iterable, List, Optional, Set, Tuple

try:
    from kafka import KafkaProducer
except Exception:  # pragma: no cover - optional dependency
    KafkaProducer = None

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

CATEGORY_PRIORITY = {
    "login": 0,
    "dashboard": 1,
    "insured": 2,
    "open": 2,
    "document": 3,
    "refund": 3,
    "banking": 3,
    "contact": 4,
    "logout": 5,
    "other": 2,
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


@dataclass(frozen=True)
class ActionDef:
    value: str
    type_name: str
    sub_type: str
    excludes: Tuple[str, ...]
    source: str


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
    connected: bool = True
    last_action_key: str = "dashboard"
    last_event_at: Optional[datetime] = None
    flow: Deque[ActionDef] = field(default_factory=deque)
    seen_actions: Set[str] = field(default_factory=set)
    actions_in_session: int = 0
    sessions_today: int = 1


class ActionCatalog:
    def __init__(self, actions: List[ActionDef], rng: random.Random):
        self.rng = rng
        self.actions = actions
        self.by_category: Dict[str, List[ActionDef]] = defaultdict(list)
        self.usage_count: Dict[str, int] = defaultdict(int)
        for action in actions:
            self.by_category[self.category_of(action)].append(action)
        self.all_keys = [self.key_of(action) for action in actions]
        self.coverage_cursor = 0
        self.coverage_order = self._build_coverage_order()

    @staticmethod
    def normalized(text: str) -> str:
        return (
            unicodedata.normalize("NFKD", text or "")
            .encode("ascii", "ignore")
            .decode("ascii")
            .lower()
        )

    def key_of(self, action: ActionDef) -> str:
        return f"{action.type_name}|{action.sub_type}|{action.value}"

    def category_of(self, action: ActionDef) -> str:
        text = self.normalized(f"{action.value} {action.sub_type} {action.type_name}")
        type_name = action.type_name.upper()
        if any(word in text for word in ["deconnexion", "logout", "disconnect", "signout"]):
            return "logout"
        if any(word in text for word in ["connexion", "login", "signin", "sso connect"]) or type_name == "LOGGING_ACTIONS" and any(word in text for word in ["connexion", "login", "auth", "sso"]):
            return "login"
        if type_name == "CONTACT_ACTIONS" or any(word in text for word in ["contact", "message", "support", "question", "demander"]):
            return "contact"
        if type_name == "BANKING_ACTIONS" or any(word in text for word in ["rib", "iban", "bank", "bancair", "cotisation", "refund bank"]):
            return "banking"
        if type_name == "DOCUMENT_ACTIONS" or any(word in text for word in ["document", "piece", "carte", "attestation", "certificat", "justificatif", "telecharger", "upload", "partager"]):
            return "document"
        if type_name == "REFUND_ACTIONS" or any(word in text for word in ["remboursement", "refund", "devis", "prise en charge"]):
            return "refund"
        if type_name == "OPEN_ACTIONS" or any(word in text for word in ["information", "profil", "personnel", "beneficiaire", "coordonnees", "update", "consulter", "ouvrir"]):
            return "open"
        if type_name == "INSURED_ACTIONS" or any(word in text for word in ["insured", "adherent", "beneficiaire", "carte tp", "tp papier"]):
            return "insured"
        if any(word in text for word in ["dashboard", "home", "accueil", "tableau", "consultation"]):
            return "dashboard"
        return "other"

    def _build_coverage_order(self) -> List[ActionDef]:
        categories = ["login", "dashboard", "insured", "open", "document", "refund", "banking", "contact", "logout", "other"]
        order: List[ActionDef] = []
        max_len = max((len(self.by_category.get(cat, [])) for cat in categories), default=0)
        for idx in range(max_len):
            for cat in categories:
                bucket = self.by_category.get(cat, [])
                if idx < len(bucket):
                    order.append(bucket[idx])
        return order or self.actions[:]

    def _pick_underused(self, categories: List[str]) -> Optional[ActionDef]:
        candidates: List[ActionDef] = []
        for category in categories:
            candidates.extend(self.by_category.get(category, []))
        if not candidates:
            return None
        candidates.sort(key=lambda a: (self.usage_count[self.key_of(a)], CATEGORY_PRIORITY.get(self.category_of(a), 99), self.key_of(a)))
        best_usage = self.usage_count[self.key_of(candidates[0])]
        best = [c for c in candidates if self.usage_count[self.key_of(c)] == best_usage]
        return self.rng.choice(best)

    def pick_for_login(self) -> Optional[ActionDef]:
        candidate = self._pick_underused(["login"])
        if candidate:
            return candidate
        return self.rng.choice(self.actions) if self.actions else None

    def pick_for_logout(self) -> Optional[ActionDef]:
        candidate = self._pick_underused(["logout"])
        return candidate

    def pick_next_for_user(self, user: UserProfile) -> ActionDef:
        if not user.connected:
            action = self.pick_for_login()
            if action is None:
                action = self.rng.choice(self.actions)
            return action

        if user.flow:
            return user.flow.popleft()

        categories = self._plan_categories_for_user(user)
        planned: List[ActionDef] = []
        local_used = set()
        for category in categories:
            action = self._pick_underused([category])
            if action is None:
                action = self._pick_underused(["insured", "open", "document", "refund", "banking", "contact", "dashboard", "other"])
            if action is not None:
                key = self.key_of(action)
                if key not in local_used:
                    planned.append(action)
                    local_used.add(key)

        # Coverage booster: force underused global actions in logical spots.
        if self.coverage_order:
            for _ in range(len(self.coverage_order)):
                action = self.coverage_order[self.coverage_cursor % len(self.coverage_order)]
                self.coverage_cursor += 1
                action_cat = self.category_of(action)
                if action_cat in categories and self.key_of(action) not in local_used:
                    insert_at = min(len(planned), max(1, len(planned) // 2))
                    planned.insert(insert_at, action)
                    break

        if not planned:
            planned = [self.rng.choice(self.actions)]

        for extra in planned[1:]:
            user.flow.append(extra)
        return planned[0]

    def _plan_categories_for_user(self, user: UserProfile) -> List[str]:
        # Keep flows short and plausible.
        if not user.connected:
            return ["login", "dashboard"]

        patterns = [
            ["dashboard", "insured", "document", "contact"],
            ["dashboard", "open", "insured", "contact"],
            ["dashboard", "refund", "document", "contact"],
            ["dashboard", "banking", "open"],
            ["dashboard", "insured", "refund"],
            ["dashboard", "contact"],
            ["dashboard", "document", "open"],
            ["dashboard", "insured", "logout"],
        ]
        pattern = list(self.rng.choice(patterns))
        if user.actions_in_session >= self.rng.randint(5, 9):
            if pattern[-1] != "logout":
                pattern.append("logout")
        elif self.rng.random() < 0.18:
            pattern.append("logout")
        return pattern

    def mark_used(self, action: ActionDef) -> None:
        self.usage_count[self.key_of(action)] += 1


def iter_java_files(root: str) -> Iterable[str]:
    for dirpath, _, filenames in os.walk(root):
        if "target" in dirpath.split(os.sep):
            continue
        for name in filenames:
            if name.endswith(".java"):
                yield os.path.join(dirpath, name)


def _strip_java_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//.*?$", "", text, flags=re.M)
    return text


def _extract_action_tracking_blocks(text: str) -> List[str]:
    blocks: List[str] = []
    idx = 0
    while True:
        start = text.find("@ActionTracking", idx)
        if start == -1:
            break
        paren = text.find("(", start)
        if paren == -1:
            idx = start + 1
            continue
        depth = 0
        end = paren
        while end < len(text):
            if text[end] == "(":
                depth += 1
            elif text[end] == ")":
                depth -= 1
                if depth == 0:
                    break
            end += 1
        if depth == 0:
            blocks.append(text[paren + 1:end])
        idx = end + 1
    return blocks


def _parse_action_block(block: str, source: str) -> Optional[ActionDef]:
    block = _strip_java_comments(block)
    value_match = re.search(r'\bvalue\s*=\s*"([^"\\]*)"', block)
    if value_match:
        value = value_match.group(1)
    else:
        first_literal = re.search(r'^\s*"([^"\\]*)"', block.strip(), flags=re.S)
        if not first_literal:
            return None
        value = first_literal.group(1)

    type_match = re.search(r"\btype\s*=\s*AuditTrailType\.([A-Z_]+)", block)
    if not type_match:
        return None
    type_name = type_match.group(1)

    sub_type_match = re.search(r'\bsubType\s*=\s*"([^"\\]*)"', block)
    sub_type = sub_type_match.group(1) if sub_type_match else ""

    excludes: List[str] = []
    excludes_block = re.search(r"\bexcludes\s*=\s*\{([^}]*)\}", block, flags=re.S)
    if excludes_block:
        raw = excludes_block.group(1)
        for item in raw.split(","):
            item = item.strip()
            if not item:
                continue
            enum_match = re.search(r"AuditTrailVariable\.([A-Z_]+)", item)
            if enum_match:
                excludes.append(enum_match.group(1))
    else:
        single_exclude = re.search(r"\bexcludes\s*=\s*AuditTrailVariable\.([A-Z_]+)", block)
        if single_exclude:
            excludes.append(single_exclude.group(1))

    return ActionDef(
        value=value,
        type_name=type_name,
        sub_type=sub_type,
        excludes=tuple(sorted(set(excludes))),
        source=source,
    )


def scan_action_tracking(project_root: str) -> List[ActionDef]:
    java_root = os.path.join(project_root, "src", "main", "java")
    if not os.path.isdir(java_root):
        return []

    actions: List[ActionDef] = []
    for path in iter_java_files(java_root):
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as fh:
                text = fh.read()
        except OSError:
            continue

        for block in _extract_action_tracking_blocks(text):
            parsed = _parse_action_block(block, source=path)
            if parsed:
                actions.append(parsed)

    uniq: Dict[Tuple[str, str, str, Tuple[str, ...]], ActionDef] = {}
    for action in actions:
        key = (action.value, action.type_name, action.sub_type, action.excludes)
        uniq[key] = action
    return list(uniq.values())


def format_created_at(now: datetime, mode: str) -> str:
    if mode == "java":
        return now.strftime("%Y-%m-%dT%H:%M:%S")
    return now.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def apply_excludes(event: Dict[str, object], excludes: Tuple[str, ...]) -> Dict[str, object]:
    for exclude in excludes:
        field = AUDIT_TRAIL_VARIABLE_TO_FIELD.get(exclude)
        if field and field in event:
            event[field] = None
    return event


def prune_nones(event: Dict[str, object]) -> Dict[str, object]:
    return {k: v for k, v in event.items() if v is not None}


def parse_api_version(value: str) -> Tuple[int, ...]:
    parts = [part.strip() for part in value.split(".") if part.strip()]
    if not parts:
        raise argparse.ArgumentTypeError("--api-version must look like 3.7.0")
    try:
        return tuple(int(part) for part in parts)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("--api-version must contain only integers") from exc


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="AuditTrail Kafka simulator with realistic multi-user journeys")
    parser.add_argument("--bootstrap-servers", default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--rate", type=float, default=2.0, help="target send rate in events/sec")
    parser.add_argument("--duration", type=int, default=0, help="seconds to run (0 = infinite)")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--project-root", default=os.getcwd())
    parser.add_argument("--dry-run", action="store_true", help="print events to stdout instead of Kafka")
    parser.add_argument("--insured-count", type=int, default=50, help="number of simulated connected users")
    parser.add_argument("--created-at-format", choices=["java", "kibana"], default="kibana")
    parser.add_argument("--api-version", type=parse_api_version, default=parse_api_version("3.7.0"))
    parser.add_argument("--acks", type=int, choices=[0, 1, -1], default=1)
    parser.add_argument("--linger-ms", type=int, default=0)
    parser.add_argument("--request-timeout-ms", type=int, default=10000)
    parser.add_argument("--max-block-ms", type=int, default=10000)
    parser.add_argument("--retries", type=int, default=0)
    parser.add_argument("--async-send", action="store_true", help="send asynchronously instead of waiting for each ack")
    parser.add_argument("--virtual-day-start", default="2026-03-24T06:30:00+01:00", help="start of the virtual activity day")
    parser.add_argument("--virtual-speed", type=float, default=120.0, help="virtual seconds advanced per emitted event at neutral load")
    return parser.parse_args(argv)


def build_producer(args: argparse.Namespace) -> KafkaProducer:
    return KafkaProducer(
        bootstrap_servers=[server.strip() for server in args.bootstrap_servers.split(",") if server.strip()],
        value_serializer=lambda v: json.dumps(v, ensure_ascii=True).encode("utf-8"),
        key_serializer=lambda v: v.encode("utf-8") if v else None,
        linger_ms=args.linger_ms,
        acks=args.acks,
        request_timeout_ms=args.request_timeout_ms,
        max_block_ms=args.max_block_ms,
        retries=args.retries,
        api_version=args.api_version,
    )


def close_producer(producer: Optional[KafkaProducer]) -> None:
    if producer is None:
        return
    try:
        producer.flush(timeout=10)
    except Exception as exc:
        print(f"flush failed during shutdown: {exc}", file=sys.stderr, flush=True)
    finally:
        try:
            producer.close(timeout=10)
        except Exception as exc:
            print(f"close failed during shutdown: {exc}", file=sys.stderr, flush=True)


def weighted_choice(items: List[Tuple[object, float]], rng: random.Random):
    total = sum(weight for _, weight in items)
    pick = rng.random() * total
    upto = 0.0
    for item, weight in items:
        upto += weight
        if upto >= pick:
            return item
    return items[-1][0]


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


def random_user_agent_for_country(rng: random.Random) -> Tuple[str, str]:
    device, ua = rng.choice(BROWSER_POOL)
    return device, ua


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


def build_users(rng: random.Random, count: int, pools: Dict[str, List[int]]) -> List[UserProfile]:
    country_items = [(country, config["weight"]) for country, config in EU_GEO_PROFILE.items()]
    users: List[UserProfile] = []
    for idx in range(count):
        country_code = weighted_choice(country_items, rng)
        geo = EU_GEO_PROFILE[country_code]
        city = rng.choice(geo["cities"])
        prefix = rng.choice(geo["prefixes"])
        device, user_agent = random_user_agent_for_country(rng)
        insured_id = f"{rng.randint(10000000, 99999999)}"
        user = UserProfile(
            insured_id=insured_id,
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
            session_id=str(rng.randint(100000, 9999999)),
            connected=True,
        )
        users.append(user)
    return users


class VirtualClock:
    def __init__(self, start: datetime, base_step_seconds: float, rng: random.Random):
        self.current = start
        self.base_step_seconds = max(1.0, base_step_seconds)
        self.rng = rng

    def load_factor(self) -> float:
        minutes = self.current.hour * 60 + self.current.minute
        peaks = [8 * 60 + 30, 12 * 60 + 30, 18 * 60]
        widths = [90, 60, 90]
        value = 0.18
        for peak, width in zip(peaks, widths):
            value += 0.42 * math.exp(-((minutes - peak) ** 2) / (2 * width * width))
        if self.current.weekday() >= 5:
            value *= 0.72
        return min(1.0, max(0.10, value))

    def advance(self) -> datetime:
        load = self.load_factor()
        # Higher load => denser event timestamps; lower load => larger gaps.
        jitter = self.rng.uniform(0.65, 1.35)
        seconds = self.base_step_seconds * jitter * (1.55 - load)
        self.current += timedelta(seconds=max(6.0, seconds))
        return self.current


def choose_active_users(users: List[UserProfile], clock: VirtualClock, rng: random.Random) -> List[UserProfile]:
    load = clock.load_factor()
    min_pool = max(8, int(len(users) * 0.28))
    max_pool = max(min_pool, int(len(users) * (0.55 + 0.45 * load)))

    scored: List[Tuple[float, UserProfile]] = []
    for user in users:
        idle_minutes = 999.0
        if user.last_event_at is not None:
            idle_minutes = (clock.current - user.last_event_at).total_seconds() / 60.0
        base = 0.8 + min(3.0, idle_minutes / 9.0)
        if user.connected:
            base += 0.25
        if user.country_code == "FR":
            base += 0.15
        if user.device.startswith("MOBILE") and 7 <= clock.current.hour <= 9:
            base += 0.20
        if user.device == "WEB" and 9 <= clock.current.hour <= 18:
            base += 0.18
        base += rng.uniform(0.0, 0.25)
        scored.append((base, user))

    scored.sort(key=lambda item: item[0], reverse=True)
    selected = [user for _, user in scored[:max_pool]]
    if len(selected) < min_pool:
        selected = users[:min_pool]
    return selected


def choose_user(users: List[UserProfile], clock: VirtualClock, rng: random.Random) -> UserProfile:
    active_pool = choose_active_users(users, clock, rng)
    weighted: List[Tuple[UserProfile, float]] = []
    for user in active_pool:
        weight = 1.0
        if user.connected:
            weight += 0.6
        if user.last_event_at is not None:
            idle_seconds = (clock.current - user.last_event_at).total_seconds()
            weight += min(2.0, idle_seconds / 900.0)
        else:
            weight += 1.2
        if user.actions_in_session >= 6:
            weight -= 0.2
        if user.country_code == "FR":
            weight += 0.2
        weighted.append((user, max(0.15, weight)))
    return weighted_choice(weighted, rng)


def maybe_rotate_ip(user: UserProfile, rng: random.Random) -> None:
    if user.device.startswith("MOBILE") and rng.random() < 0.10:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)
    elif user.device == "WEB" and rng.random() < 0.03:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)


def choose_status(action: ActionDef, catalog: ActionCatalog, rng: random.Random) -> str:
    category = catalog.category_of(action)
    failure_probability = {
        "login": 0.03,
        "logout": 0.01,
        "banking": 0.08,
        "refund": 0.07,
        "document": 0.05,
        "contact": 0.04,
        "open": 0.03,
        "insured": 0.03,
        "dashboard": 0.01,
        "other": 0.04,
    }.get(category, 0.04)
    return "KO" if rng.random() < failure_probability else "OK"


def choose_http_code(status: str, action: ActionDef, catalog: ActionCatalog, rng: random.Random) -> str:
    category = catalog.category_of(action)
    if status == "OK":
        if category == "login":
            return str(rng.choice([200, 204]))
        if category in {"document", "refund", "banking", "contact"}:
            return str(rng.choice([200, 201, 202]))
        return str(rng.choice([200, 204]))

    failure_codes = {
        "login": [401, 403],
        "refund": [400, 409, 422],
        "banking": [400, 409, 422],
        "document": [400, 422],
        "contact": [400, 422],
    }
    return str(rng.choice(failure_codes.get(category, [400, 403, 409, 422, 500])))


def choose_geo_payload(user: UserProfile) -> Dict[str, str]:
    return {
        "country": user.country_code,
        "city": user.city,
    }


def build_request_data(action: ActionDef, user: UserProfile, catalog: ActionCatalog, rng: random.Random) -> Optional[str]:
    category = catalog.category_of(action)
    geo = choose_geo_payload(user)
    if category == "login":
        payload = {
            "login": user.insured_id,
            "channel": "mobile" if user.device.startswith("MOBILE") else "web",
            "country": geo["country"],
            "city": geo["city"],
        }
    elif category == "contact":
        payload = {
            "subject": action.value,
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
            "country": geo["country"],
        }
    elif category in {"insured", "open"}:
        payload = {
            "insuredId": user.insured_id,
            "action": action.value,
        }
    elif category == "document":
        payload = {
            "document": action.value,
            "tag": rng.choice(["medical", "administrative", "identity", "family"]),
        }
    elif category == "refund":
        payload = {
            "action": action.value,
            "claimAmount": round(rng.uniform(18, 240), 2),
            "currency": "EUR",
        }
    else:
        payload = {"action": action.value}
    return json.dumps(payload, ensure_ascii=True)


def build_request_return(status: str, action: ActionDef, catalog: ActionCatalog, rng: random.Random) -> Optional[str]:
    category = catalog.category_of(action)
    if status == "OK":
        if category in {"login", "dashboard", "open", "insured"} and rng.random() < 0.55:
            return None
        payload = {"status": "success", "reference": f"REF-{rng.randint(100000, 999999)}"}
        if category == "refund":
            payload["claimId"] = f"CLM-{rng.randint(1000000, 9999999)}"
        return json.dumps(payload, ensure_ascii=True)

    if category == "contact" and rng.random() < 0.5:
        return "SubTheme should not be empty !"
    payload = {"status": "error", "code": rng.choice(["AUTH_FAILED", "VALIDATION_ERROR", "SERVER_ERROR", "BUSINESS_RULE_REJECTED"])}
    return json.dumps(payload, ensure_ascii=True)


def generate_id_list_for_user(base_value: int, rng: random.Random, variability: float = 0.12) -> List[int]:
    if rng.random() < variability:
        return []
    return [base_value]


def generate_event(
    action: ActionDef,
    user: UserProfile,
    catalog: ActionCatalog,
    rng: random.Random,
    created_at_mode: str,
    created_at: datetime,
) -> Dict[str, object]:
    maybe_rotate_ip(user, rng)
    status = choose_status(action, catalog, rng)
    http_code = choose_http_code(status, action, catalog, rng)
    request_data = build_request_data(action, user, catalog, rng)
    request_return = build_request_return(status, action, catalog, rng)

    category = catalog.category_of(action)
    if category == "login":
        if not user.connected:
            user.session_id = str(rng.randint(100000, 9999999))
        user.connected = True
        user.actions_in_session = 0
    elif category == "logout":
        request_return = request_return or json.dumps({"status": "success"}, ensure_ascii=True)
    event = {
        "id": str(uuid.uuid4()),
        "insuredId": user.insured_id,
        "status": status,
        "sessionId": user.session_id,
        "action": action.value,
        "httpCode": http_code,
        "ip": user.current_ip,
        "userAgent": user.user_agent,
        "requestData": request_data,
        "requestReturn": request_return,
        "createdAt": format_created_at(created_at.astimezone(timezone.utc), created_at_mode),
        "type": action.type_name,
        "environmentId": str(user.environment_id),
        "device": user.device,
        "companyIdList": generate_id_list_for_user(user.company_id, rng),
        "companyGroupIdList": generate_id_list_for_user(user.company_group_id, rng, variability=0.08),
        "insurerIdList": generate_id_list_for_user(user.insurer_id, rng, variability=0.10),
        "companySectionIdList": generate_id_list_for_user(user.company_section_id, rng, variability=0.10),
        "insurerCodeIdList": generate_id_list_for_user(user.insurer_code_id, rng, variability=0.10),
        "healthcareNetworkIdList": generate_id_list_for_user(user.healthcare_network_id, rng, variability=0.15),
        "domainIdList": generate_id_list_for_user(user.domain_id, rng, variability=0.10),
        "subType": action.sub_type or None,
    }
    event = apply_excludes(event, action.excludes)
    event = prune_nones(event)

    user.last_action_key = catalog.category_of(action)
    user.last_event_at = created_at
    user.seen_actions.add(catalog.key_of(action))
    catalog.mark_used(action)
    user.actions_in_session += 1

    if category == "logout":
        user.connected = False
        user.actions_in_session = 0
        user.session_id = None
    return event


def fallback_actions() -> List[ActionDef]:
    return [
        ActionDef(value="Connexion", type_name="LOGGING_ACTIONS", sub_type="logging_login", excludes=(), source="fallback"),
        ActionDef(value="Déconnexion", type_name="LOGGING_ACTIONS", sub_type="logging_logout", excludes=(), source="fallback"),
        ActionDef(value="Consulter tableau de bord", type_name="OPEN_ACTIONS", sub_type="dashboard", excludes=(), source="fallback"),
        ActionDef(value="Mettre à jour ses informations personnelles", type_name="OPEN_ACTIONS", sub_type="update_personal_information", excludes=(), source="fallback"),
        ActionDef(value="Télécharger une attestation", type_name="DOCUMENT_ACTIONS", sub_type="download_certificate", excludes=(), source="fallback"),
        ActionDef(value="Partager un justificatif", type_name="DOCUMENT_ACTIONS", sub_type="upload_document", excludes=(), source="fallback"),
        ActionDef(value="Demande de remboursement consultation", type_name="REFUND_ACTIONS", sub_type="refund_consultation", excludes=(), source="fallback"),
        ActionDef(value="Modifier ses coordonnées bancaires", type_name="BANKING_ACTIONS", sub_type="update_bank_details", excludes=(), source="fallback"),
        ActionDef(value="Envoyer un message au support", type_name="CONTACT_ACTIONS", sub_type="contact_support", excludes=(), source="fallback"),
        ActionDef(value="Renvoi carte TP papier", type_name="INSURED_ACTIONS", sub_type="request_tpa_card", excludes=(), source="fallback"),
    ]


def main(argv: List[str]) -> int:
    args = parse_args(argv)
    rng = random.Random(args.seed)

    actions = scan_action_tracking(args.project_root)
    if not actions:
        actions = fallback_actions()

    catalog = ActionCatalog(actions, rng)
    pools = build_pools()
    users = build_users(rng, args.insured_count, pools)

    try:
        virtual_start = datetime.fromisoformat(args.virtual_day_start)
        if virtual_start.tzinfo is None:
            virtual_start = virtual_start.replace(tzinfo=timezone.utc)
    except ValueError:
        print("--virtual-day-start must be ISO 8601, e.g. 2026-03-24T06:30:00+01:00", file=sys.stderr)
        return 2
    clock = VirtualClock(virtual_start, args.virtual_speed, rng)

    producer: Optional[KafkaProducer] = None
    if not args.dry_run:
        if KafkaProducer is None:
            print("kafka-python is required. Install with: pip install kafka-python", file=sys.stderr)
            return 2
        try:
            producer = build_producer(args)
        except Exception as exc:
            print(f"failed to create Kafka producer: {exc}", file=sys.stderr)
            return 2

    wall_start = time.monotonic()

    try:
        while True:
            user = choose_user(users, clock, rng)
            action = catalog.pick_next_for_user(user)
            created_at = clock.advance()
            event = generate_event(action, user, catalog, rng, args.created_at_format, created_at)
            key = user.insured_id

            if args.dry_run:
                print(json.dumps(event, ensure_ascii=True), flush=True)
            else:
                try:
                    future = producer.send(args.topic, key=key, value=event)
                    if args.async_send:
                        future.add_callback(
                            lambda metadata: print(
                                f"sent to {metadata.topic} partition {metadata.partition} offset {metadata.offset}",
                                flush=True,
                            )
                        )
                        future.add_errback(lambda exc: print(f"send failed: {exc}", file=sys.stderr, flush=True))
                    else:
                        metadata = future.get(timeout=max(1, args.request_timeout_ms // 1000))
                        print(
                            f"sent to {metadata.topic} partition {metadata.partition} offset {metadata.offset}",
                            flush=True,
                        )
                except Exception as exc:
                    print(f"send failed: {exc}", file=sys.stderr, flush=True)

            if args.duration > 0 and (time.monotonic() - wall_start) >= args.duration:
                break

            if args.rate > 0:
                time.sleep(max(0.0, 1.0 / args.rate))

    except KeyboardInterrupt:
        print("stopped by user", file=sys.stderr, flush=True)
    finally:
        close_producer(producer)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
