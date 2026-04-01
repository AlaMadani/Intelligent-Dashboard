#!/usr/bin/env python3
"""
Revised dataset generator — key improvements over v1:
  1. Anomaly injection (5 % of sessions) with `is_anomaly` column.
  2. Balanced action coverage: minimum appearances guarantee per action.
  3. Diverse session patterns with weighted transitions, not pure templates.
  4. `seq_pos_norm` and `session_len` pre-computed so the notebook can use them.
  5. Larger default dataset (200 users, 8 000 actions/month → ~96 K/yr).
"""

import argparse
import csv
import json
import math
import os
import random
import re
import sys
import unicodedata
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple

# ─── Constants ────────────────────────────────────────────────────────────────

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

WEEKDAY_WEIGHTS = [1.25, 1.25, 1.20, 1.15, 1.05, 0.50, 0.40]  # Monday..Sunday

# Anomaly types that the dataset generator can inject
ANOMALY_TYPES = [
    "rapid_fire",       # many actions in very short time (< 5 s between each)
    "unusual_hour",     # activity at 2–4 AM on weekdays
    "geo_jump",         # same session, different IP continent
    "repeated_fail",    # 3+ consecutive KO in a session
    "skip_login",       # session starts with a non-login action
    "impossible_seq",   # action that cannot logically follow the previous one
]

CSV_COLUMNS = [
    "id",
    "insuredId",
    "status",
    "sessionId",
    "action",
    "httpCode",
    "ip",
    "userAgent",
    "requestData",
    "requestReturn",
    "createdAt",
    "type",
    "environmentId",
    "device",
    "companyIdList",
    "companyGroupIdList",
    "insurerIdList",
    "companySectionIdList",
    "insurerCodeIdList",
    "healthcareNetworkIdList",
    "domainIdList",
    "subType",
    "countryCode",
    "city",
    "month",
    "sessionNumber",
    "sequenceInSession",
    "sessionLength",      # NEW: total length of the session (useful feature)
    "is_anomaly",         # NEW: 0 = normal, 1 = anomalous
    "anomaly_type",       # NEW: blank or one of ANOMALY_TYPES
]


# ─── Data classes ─────────────────────────────────────────────────────────────

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
    annual_target: int
    session_id: Optional[str] = None
    actions_generated: int = 0
    sessions_generated: int = 0
    seen_actions: Set[str] = field(default_factory=set)


# ─── Action catalog ───────────────────────────────────────────────────────────

class ActionCatalog:
    def __init__(self, actions: List[ActionDef], rng: random.Random):
        self.rng = rng
        self.actions = actions
        self.by_category: Dict[str, List[ActionDef]] = defaultdict(list)
        self.usage_count: Dict[str, int] = defaultdict(int)
        for action in actions:
            self.by_category[self.category_of(action)].append(action)
        self.coverage_order = self._build_coverage_order()
        self.coverage_cursor = 0

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
        if any(word in text for word in ["connexion", "login", "signin", "auth", "sso"]):
            return "login"
        if any(word in text for word in ["dashboard", "home", "accueil", "tableau"]):
            return "dashboard"
        if type_name == "CONTACT_ACTIONS" or any(word in text for word in ["contact", "message", "support", "question", "demander"]):
            return "contact"
        if type_name == "BANKING_ACTIONS" or any(word in text for word in ["rib", "iban", "bank", "bancair", "cotisation"]):
            return "banking"
        if type_name == "DOCUMENT_ACTIONS" or any(word in text for word in ["document", "piece", "carte", "attestation", "certificat", "justificatif", "telecharger", "upload", "partager"]):
            return "document"
        if type_name == "REFUND_ACTIONS" or any(word in text for word in ["remboursement", "refund", "devis", "prise en charge"]):
            return "refund"
        if type_name == "OPEN_ACTIONS" or any(word in text for word in ["information", "profil", "personnel", "coordonnees", "update", "consulter", "ouvrir"]):
            return "open"
        if type_name == "INSURED_ACTIONS" or any(word in text for word in ["beneficiaire", "insured", "adherent", "carte tp"]):
            return "insured"
        return "other"

    def _build_coverage_order(self) -> List[ActionDef]:
        categories = ["login", "dashboard", "insured", "open", "document", "refund", "banking", "contact", "logout", "other"]
        order: List[ActionDef] = []
        max_len = max((len(self.by_category.get(cat, [])) for cat in categories), default=0)
        for idx in range(max_len):
            for category in categories:
                bucket = self.by_category.get(category, [])
                if idx < len(bucket):
                    order.append(bucket[idx])
        return order or list(self.actions)

    def mark_used(self, action: ActionDef) -> None:
        self.usage_count[self.key_of(action)] += 1

    def _pick_underused(self, categories: List[str], exclude_keys: Optional[Set[str]] = None) -> Optional[ActionDef]:
        exclude_keys = exclude_keys or set()
        candidates: List[ActionDef] = []
        for category in categories:
            for action in self.by_category.get(category, []):
                if self.key_of(action) not in exclude_keys:
                    candidates.append(action)
        if not candidates:
            return None
        candidates.sort(key=lambda a: (self.usage_count[self.key_of(a)], CATEGORY_PRIORITY.get(self.category_of(a), 99), self.key_of(a)))
        best_usage = self.usage_count[self.key_of(candidates[0])]
        best = [a for a in candidates if self.usage_count[self.key_of(a)] == best_usage]
        return self.rng.choice(best)

    def coverage_action_for_category(self, category: str, used_keys: Set[str]) -> Optional[ActionDef]:
        if not self.coverage_order:
            return None
        for _ in range(len(self.coverage_order)):
            action = self.coverage_order[self.coverage_cursor % len(self.coverage_order)]
            self.coverage_cursor += 1
            if self.category_of(action) == category and self.key_of(action) not in used_keys:
                return action
        return None


@dataclass
class SessionEventPlan:
    action: ActionDef
    timestamp: datetime
    sequence_in_session: int
    session_number: int
    is_anomaly: int = 0
    anomaly_type: str = ""


# ─── Java scanning ────────────────────────────────────────────────────────────

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
    return ActionDef(value=value, type_name=type_name, sub_type=sub_type, excludes=tuple(sorted(set(excludes))), source=source)


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


# ─── Helpers ──────────────────────────────────────────────────────────────────

def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate one full year of realistic audit-trail data for ML training")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--output", default="audit_trail_2025.csv")
    parser.add_argument("--start-date", default="2025-01-01")
    parser.add_argument("--end-date", default="2025-12-31")
    parser.add_argument("--users", type=int, default=200, help="number of simulated users (↑ for larger dataset)")
    parser.add_argument("--actions-per-month", type=int, default=8000, help="total actions per month")
    parser.add_argument("--anomaly-rate", type=float, default=0.05, help="fraction of sessions that are anomalous")
    parser.add_argument("--seed", type=int, default=2025)
    parser.add_argument("--created-at-format", choices=["java", "kibana"], default="kibana")
    parser.add_argument("--file-format", choices=["csv", "txt"], default=None)
    return parser.parse_args(argv)


def parse_date(value: str) -> datetime:
    try:
        return datetime.fromisoformat(value).replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"invalid date '{value}'") from exc


def month_range(start: datetime, end: datetime) -> List[Tuple[int, datetime, datetime]]:
    months: List[Tuple[int, datetime, datetime]] = []
    cursor = datetime(start.year, start.month, 1, tzinfo=timezone.utc)
    idx = 0
    while cursor <= end:
        if cursor.month == 12:
            next_month = datetime(cursor.year + 1, 1, 1, tzinfo=timezone.utc)
        else:
            next_month = datetime(cursor.year, cursor.month + 1, 1, tzinfo=timezone.utc)
        month_end = next_month - timedelta(seconds=1)
        months.append((idx, cursor, month_end))
        cursor = next_month
        idx += 1
    return months


def weighted_choice(items: List[Tuple[object, float]], rng: random.Random):
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
    """Return an IP that looks like it's from a different continent."""
    octets = [rng.choice([41, 102, 103, 104, 105, 196, 197, 198]),
              rng.randint(0, 255), rng.randint(0, 255), rng.randint(2, 250)]
    return ".".join(str(o) for o in octets)


def build_users(rng: random.Random, count: int, annual_target: int, pools: Dict[str, List[int]]) -> List[UserProfile]:
    users: List[UserProfile] = []
    geo_choices = [(country, profile["weight"]) for country, profile in EU_GEO_PROFILE.items()]
    for _ in range(count):
        country_code = weighted_choice(geo_choices, rng)
        geo = EU_GEO_PROFILE[country_code]
        city = rng.choice(geo["cities"])
        prefix = rng.choice(geo["prefixes"])
        device, user_agent = rng.choice(BROWSER_POOL)
        users.append(
            UserProfile(
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
                annual_target=annual_target,
            )
        )
    return users


def random_time_in_month(month_start: datetime, month_end: datetime, rng: random.Random) -> datetime:
    days = []
    cursor = month_start
    while cursor <= month_end:
        days.append(cursor)
        cursor += timedelta(days=1)
    day_weights = []
    for day in days:
        weight = WEEKDAY_WEIGHTS[day.weekday()]
        if day.day in {1, 2, 3, 28, 29, 30, 31}:
            weight *= 1.08
        day_weights.append(weight)
    chosen_day = weighted_choice(list(zip(days, day_weights)), rng)
    hour_peaks = [(8.5, 0.33), (12.5, 0.17), (18.0, 0.27), (10.5, 0.13), (15.0, 0.10)]
    peak_hour = weighted_choice(hour_peaks, rng)
    hour = int(peak_hour)
    minute = int((peak_hour - hour) * 60)
    dt = chosen_day.replace(hour=hour, minute=minute, second=0, microsecond=0)
    dt += timedelta(minutes=rng.randint(-50, 50), seconds=rng.randint(0, 59))
    if dt < month_start:
        dt = month_start + timedelta(minutes=5)
    if dt > month_end:
        dt = month_end - timedelta(minutes=5)
    return dt


# ─── Improved session building ─────────────────────────────────────────────────

# Weighted session templates – more variety than v1
SESSION_TEMPLATES = [
    (["dashboard", "insured", "document", "contact"], 0.12),
    (["dashboard", "open", "insured", "contact"], 0.10),
    (["dashboard", "refund", "document", "contact"], 0.10),
    (["dashboard", "banking", "open"], 0.08),
    (["dashboard", "insured", "refund"], 0.08),
    (["dashboard", "contact"], 0.07),
    (["dashboard", "document", "open"], 0.07),
    (["dashboard", "refund", "banking", "contact"], 0.06),
    (["dashboard", "open", "document"], 0.06),
    (["dashboard", "insured", "banking"], 0.05),
    (["dashboard", "insured", "open", "document", "refund"], 0.05),
    (["dashboard", "banking"], 0.04),
    (["dashboard", "refund"], 0.04),
    (["dashboard", "insured"], 0.04),
    (["dashboard", "contact", "document"], 0.04),
]


def build_session_categories(length: int, rng: random.Random) -> List[str]:
    templates, weights = zip(*SESSION_TEMPLATES)
    template = list(rng.choices(templates, weights=weights, k=1)[0])
    categories = ["login"]
    while len(categories) < length - 1:
        categories.append(template[(len(categories) - 1) % len(template)])
    categories.append("logout")
    if len(categories) > length:
        categories = categories[: length - 1] + ["logout"]
    return categories


def session_length_weights(remaining: int) -> List[Tuple[int, float]]:
    choices = []
    for length in range(3, min(9, remaining + 1)):
        w = {3: 0.15, 4: 0.20, 5: 0.25, 6: 0.20, 7: 0.13, 8: 0.07}.get(length, 0.05)
        choices.append((length, w))
    return choices


def choose_session_lengths(total_actions: int, rng: random.Random) -> List[int]:
    lengths: List[int] = []
    remaining = total_actions
    while remaining > 0:
        if remaining <= 8:
            lengths.append(remaining)
            break
        length = weighted_choice(session_length_weights(remaining), rng)
        if remaining - length in {1, 2}:
            length += remaining - length
        lengths.append(length)
        remaining -= length
    return lengths


def choose_status(category: str, rng: random.Random, is_anomaly: bool = False) -> str:
    if is_anomaly:
        # Anomalous sessions have higher failure rates
        return "KO" if rng.random() < 0.40 else "OK"
    failure_probability = {
        "login": 0.03, "logout": 0.01, "banking": 0.08, "refund": 0.07,
        "document": 0.05, "contact": 0.04, "open": 0.03, "insured": 0.03,
        "dashboard": 0.01, "other": 0.04,
    }.get(category, 0.04)
    return "KO" if rng.random() < failure_probability else "OK"


def choose_http_code(status: str, category: str, rng: random.Random) -> str:
    if status == "OK":
        if category == "login":
            return str(rng.choice([200, 204]))
        if category in {"document", "refund", "banking", "contact"}:
            return str(rng.choice([200, 201, 202]))
        return str(rng.choice([200, 204]))
    failure_codes = {
        "login": [401, 403], "refund": [400, 409, 422],
        "banking": [400, 409, 422], "document": [400, 422], "contact": [400, 422],
    }
    return str(rng.choice(failure_codes.get(category, [400, 403, 409, 422, 500])))


def event_delay_seconds(category: str, rng: random.Random, rapid: bool = False) -> int:
    if rapid:
        return rng.randint(2, 8)  # anomaly: rapid fire
    ranges = {
        "login": (15, 90), "dashboard": (20, 180), "insured": (60, 420),
        "open": (70, 480), "document": (90, 600), "refund": (120, 900),
        "banking": (120, 840), "contact": (120, 720), "logout": (15, 120), "other": (60, 300),
    }
    low, high = ranges.get(category, (60, 300))
    return rng.randint(low, high)


def maybe_rotate_ip(user: UserProfile, rng: random.Random) -> None:
    if user.device.startswith("MOBILE") and rng.random() < 0.12:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)
    elif user.device == "WEB" and rng.random() < 0.04:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)


def build_request_data(action: ActionDef, category: str, user: UserProfile, rng: random.Random) -> Optional[str]:
    if category == "login":
        payload = {"login": user.insured_id, "channel": "mobile" if user.device.startswith("MOBILE") else "web", "country": user.country_code, "city": user.city}
    elif category == "contact":
        payload = {"subject": action.value, "message": rng.choice(["Besoin d'assistance sur mon espace assuré", "Question sur un document ou un remboursement", "Demande d'information complémentaire"]), "priority": rng.choice(["low", "normal", "normal", "high"])}
    elif category == "banking":
        payload = {"iban_last4": f"{rng.randint(1000, 9999)}", "bic": rng.choice(["AGRIFRPP", "BNPAFRPP", "SOGEFRPP", "CMCIFRPP"]), "country": user.country_code}
    elif category in {"insured", "open"}:
        payload = {"insuredId": user.insured_id, "action": action.value}
    elif category == "document":
        payload = {"document": action.value, "tag": rng.choice(["medical", "administrative", "identity", "family"])}
    elif category == "refund":
        payload = {"action": action.value, "claimAmount": round(rng.uniform(18, 240), 2), "currency": "EUR"}
    else:
        payload = {"action": action.value}
    return json.dumps(payload, ensure_ascii=True)


def build_request_return(status: str, category: str, rng: random.Random) -> Optional[str]:
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


def generate_id_list_for_user(base_value: int, rng: random.Random, variability: float) -> List[int]:
    if rng.random() < variability:
        return []
    return [base_value]


def apply_excludes(event: Dict[str, object], excludes: Tuple[str, ...]) -> Dict[str, object]:
    for exclude in excludes:
        field_name = AUDIT_TRAIL_VARIABLE_TO_FIELD.get(exclude)
        if field_name and field_name in event:
            event[field_name] = None
    return event


def prune_nones(event: Dict[str, object]) -> Dict[str, object]:
    return {key: value for key, value in event.items() if value is not None}


def format_created_at(now: datetime, mode: str) -> str:
    if mode == "java":
        return now.strftime("%Y-%m-%dT%H:%M:%S")
    return now.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def pick_action_for_category(catalog: ActionCatalog, category: str, used_keys: Set[str], rng: random.Random) -> ActionDef:
    action = catalog.coverage_action_for_category(category, used_keys)
    if action is None:
        action = catalog._pick_underused([category], exclude_keys=used_keys)
    if action is None:
        action = catalog._pick_underused(
            ["insured", "open", "document", "refund", "banking", "contact", "dashboard", "other", "login", "logout"],
            exclude_keys=used_keys,
        )
    if action is None:
        action = rng.choice(catalog.actions)
    return action


# ─── Anomaly injection ────────────────────────────────────────────────────────

def build_anomaly_session_plans(
    user: UserProfile,
    month_start: datetime,
    month_end: datetime,
    catalog: ActionCatalog,
    rng: random.Random,
    session_number: int,
    anomaly_type: Optional[str] = None,
) -> List[SessionEventPlan]:
    """
    Build an anomalous session plan.  Returns a list of SessionEventPlan entries.
    """
    if anomaly_type is None:
        anomaly_type = rng.choice(ANOMALY_TYPES)

    start_time = random_time_in_month(month_start, month_end, rng)

    # unusual_hour: override start time to 2-4 AM
    if anomaly_type == "unusual_hour":
        start_time = start_time.replace(hour=rng.randint(2, 4), minute=rng.randint(0, 59))

    length = rng.randint(4, 8)
    plans: List[SessionEventPlan] = []

    if anomaly_type == "skip_login":
        # Start session with a non-login action (e.g. banking)
        categories = ["banking", "refund", "document", "contact", "logout"][:length]
    elif anomaly_type == "impossible_seq":
        # Put logout in the middle, then more actions
        mid = length // 2
        categories = ["login"] + ["dashboard"] * (mid - 1) + ["logout"] + ["banking"] * (length - mid - 1)
    elif anomaly_type == "repeated_fail":
        categories = ["login"] + ["banking"] * (length - 2) + ["logout"]
    else:
        categories = build_session_categories(length, rng)

    current_time = start_time
    used_keys: Set[str] = set()
    rapid = anomaly_type == "rapid_fire"

    for seq_idx, category in enumerate(categories, start=1):
        action = pick_action_for_category(catalog, category, used_keys, rng)
        used_keys.add(catalog.key_of(action))
        plans.append(SessionEventPlan(
            action=action,
            timestamp=current_time,
            sequence_in_session=seq_idx,
            session_number=session_number,
            is_anomaly=1,
            anomaly_type=anomaly_type,
        ))
        current_time += timedelta(seconds=event_delay_seconds(category, rng, rapid=rapid))
        if current_time > month_end:
            current_time = month_end - timedelta(minutes=max(0, len(categories) - seq_idx))

    return plans


# ─── Main plan builder ────────────────────────────────────────────────────────

def build_month_plans(
    user: UserProfile,
    month_start: datetime,
    month_end: datetime,
    actions_per_user: int,
    catalog: ActionCatalog,
    rng: random.Random,
    anomaly_rate: float,
) -> List[SessionEventPlan]:
    session_lengths = choose_session_lengths(actions_per_user, rng)
    plans: List[SessionEventPlan] = []

    for length in session_lengths:
        user.sessions_generated += 1
        session_number = user.sessions_generated

        # Decide if this session is anomalous
        if rng.random() < anomaly_rate:
            anomaly_plans = build_anomaly_session_plans(
                user, month_start, month_end, catalog, rng, session_number
            )
            plans.extend(anomaly_plans)
            continue

        start_time = random_time_in_month(month_start, month_end, rng)
        categories = build_session_categories(length, rng)
        current_time = start_time
        used_keys: Set[str] = set()

        for seq_idx, category in enumerate(categories, start=1):
            action = pick_action_for_category(catalog, category, used_keys, rng)
            used_keys.add(catalog.key_of(action))
            plans.append(SessionEventPlan(
                action=action,
                timestamp=current_time,
                sequence_in_session=seq_idx,
                session_number=session_number,
                is_anomaly=0,
                anomaly_type="",
            ))
            current_time += timedelta(seconds=event_delay_seconds(category, rng))
            if current_time > month_end:
                current_time = month_end - timedelta(minutes=max(0, len(categories) - seq_idx))

    plans.sort(key=lambda p: p.timestamp)
    return plans


def build_event_record(
    plan: SessionEventPlan,
    user: UserProfile,
    catalog: ActionCatalog,
    created_at_format: str,
    rng: random.Random,
    session_length: int,
) -> Dict[str, object]:
    action = plan.action
    category = catalog.category_of(action)
    if plan.sequence_in_session == 1 or category == "login" or user.session_id is None:
        user.session_id = str(rng.randint(100000, 9999999))

    # geo_jump anomaly: override IP mid-session
    current_ip = user.current_ip
    if plan.is_anomaly and plan.anomaly_type == "geo_jump" and plan.sequence_in_session > 2:
        current_ip = random_foreign_ip(rng)
    else:
        maybe_rotate_ip(user, rng)
        current_ip = user.current_ip

    is_anomaly_session = bool(plan.is_anomaly)
    status = choose_status(category, rng, is_anomaly=is_anomaly_session)
    # repeated_fail: force KO
    if plan.is_anomaly and plan.anomaly_type == "repeated_fail" and category == "banking":
        status = "KO"

    http_code = choose_http_code(status, category, rng)
    request_data = build_request_data(action, category, user, rng)
    request_return = build_request_return(status, category, rng)

    event: Dict[str, object] = {
        "id": str(uuid.uuid4()),
        "insuredId": user.insured_id,
        "status": status,
        "sessionId": user.session_id,
        "action": action.value,
        "httpCode": http_code,
        "ip": current_ip,
        "userAgent": user.user_agent,
        "requestData": request_data,
        "requestReturn": request_return,
        "createdAt": format_created_at(plan.timestamp.astimezone(timezone.utc), created_at_format),
        "type": action.type_name,
        "environmentId": str(user.environment_id),
        "device": user.device,
        "companyIdList": generate_id_list_for_user(user.company_id, rng, variability=0.10),
        "companyGroupIdList": generate_id_list_for_user(user.company_group_id, rng, variability=0.08),
        "insurerIdList": generate_id_list_for_user(user.insurer_id, rng, variability=0.10),
        "companySectionIdList": generate_id_list_for_user(user.company_section_id, rng, variability=0.10),
        "insurerCodeIdList": generate_id_list_for_user(user.insurer_code_id, rng, variability=0.10),
        "healthcareNetworkIdList": generate_id_list_for_user(user.healthcare_network_id, rng, variability=0.15),
        "domainIdList": generate_id_list_for_user(user.domain_id, rng, variability=0.10),
        "subType": action.sub_type or None,
        "countryCode": user.country_code,
        "city": user.city,
        "month": plan.timestamp.strftime("%Y-%m"),
        "sessionNumber": plan.session_number,
        "sequenceInSession": plan.sequence_in_session,
        "sessionLength": session_length,     # NEW
        "is_anomaly": plan.is_anomaly,        # NEW
        "anomaly_type": plan.anomaly_type,    # NEW
    }
    event = apply_excludes(event, action.excludes)
    event = prune_nones(event)
    catalog.mark_used(action)
    user.actions_generated += 1
    user.seen_actions.add(catalog.key_of(action))
    if category == "logout":
        user.session_id = None
    return event


def serialize_lists_for_csv(event: Dict[str, object]) -> Dict[str, object]:
    formatted = dict(event)
    for key in ["companyIdList", "companyGroupIdList", "insurerIdList", "companySectionIdList",
                "insurerCodeIdList", "healthcareNetworkIdList", "domainIdList"]:
        if key in formatted:
            formatted[key] = json.dumps(formatted[key], ensure_ascii=True)
    return formatted


def infer_file_format(output: str, forced: Optional[str]) -> str:
    if forced:
        return forced
    suffix = Path(output).suffix.lower()
    if suffix == ".txt":
        return "txt"
    return "csv"


def write_output(events: List[Dict[str, object]], output: str, file_format: str) -> None:
    path = Path(output)
    path.parent.mkdir(parents=True, exist_ok=True)
    if file_format == "csv":
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=CSV_COLUMNS)
            writer.writeheader()
            for event in events:
                row = serialize_lists_for_csv(event)
                writer.writerow({column: row.get(column, "") for column in CSV_COLUMNS})
    else:
        with path.open("w", encoding="utf-8") as fh:
            for event in events:
                fh.write(json.dumps(event, ensure_ascii=True) + "\n")


# ─── Entry point ──────────────────────────────────────────────────────────────

def main(argv: List[str]) -> int:
    args = parse_args(argv)
    rng = random.Random(args.seed)

    start_date = parse_date(args.start_date)
    end_date = parse_date(args.end_date) + timedelta(hours=23, minutes=59, seconds=59)
    months = month_range(start_date, end_date)

    if args.actions_per_month % args.users != 0:
        print("actions-per-month must be divisible by users", file=sys.stderr)
        return 2

    actions_per_user_per_month = args.actions_per_month // args.users
    annual_target_per_user = (args.actions_per_month * len(months)) // args.users

    actions = scan_action_tracking(args.project_root)
    if not actions:
        actions = fallback_actions()
    catalog = ActionCatalog(actions, rng)

    pools = build_pools()
    users = build_users(rng, args.users, annual_target_per_user, pools)

    all_events: List[Dict[str, object]] = []

    for month_index, month_start, month_end in months:
        month_events: List[Tuple[datetime, Dict[str, object]]] = []
        for user in users:
            plans = build_month_plans(
                user=user,
                month_start=month_start,
                month_end=month_end,
                actions_per_user=actions_per_user_per_month,
                catalog=catalog,
                rng=rng,
                anomaly_rate=args.anomaly_rate,
            )
            # Compute session lengths for each session
            session_len_map: Dict[int, int] = {}
            for plan in plans:
                session_len_map[plan.session_number] = max(
                    session_len_map.get(plan.session_number, 0), plan.sequence_in_session
                )

            for plan in plans:
                event = build_event_record(
                    plan=plan,
                    user=user,
                    catalog=catalog,
                    created_at_format=args.created_at_format,
                    rng=rng,
                    session_length=session_len_map[plan.session_number],
                )
                month_events.append((plan.timestamp, event))

        month_events.sort(key=lambda item: item[0])
        all_events.extend(event for _, event in month_events)

    file_format = infer_file_format(args.output, args.file_format)
    write_output(all_events, args.output, file_format)

    normal_count = sum(1 for e in all_events if not e.get("is_anomaly"))
    anomaly_count = sum(1 for e in all_events if e.get("is_anomaly"))
    print(json.dumps({
        "output": str(Path(args.output).resolve()),
        "total_events": len(all_events),
        "normal_events": normal_count,
        "anomaly_events": anomaly_count,
        "anomaly_pct": round(100 * anomaly_count / max(1, len(all_events)), 2),
        "months": len(months),
        "users": args.users,
        "unique_actions": len(actions),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
