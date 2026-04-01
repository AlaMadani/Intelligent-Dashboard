#!/usr/bin/env python3
"""
simulator_yearly_dataset_v3.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Generates a full year of audit-trail data driven by:
  • backend-apis-actions.json   — the 43 real tracked actions
  • actions_order-v2.json       — the real frontend navigation graph

NO Java scanning.  Session templates are derived directly from the
navigation JSON, so every sequence produced is a path that can actually
happen in your application.

Rich behavioral patterns:
  ─ 5 user personas         (frequent_checker, admin_delegated,
                             self_service, contact_heavy, new_user)
  ─ Time-of-day modifiers   (morning rush / lunch dip / evening peak)
  ─ Country modifiers        (FR heavy SSO; BE/DE direct+MFA; mobile vs web)
  ─ Device modifiers         (iOS → wallet; Android → TP; Web → banking/docs)
  ─ Month seasonality        (Jan activations; Sep beneficiary; end-of-month billing)
  ─ Transition probabilities (each action has a weighted "likely-next" map)

Anomaly injection (5 % of sessions by default):
  rapid_fire | unusual_hour | geo_jump |
  repeated_fail | skip_login | impossible_seq

Output CSV columns include: sessionLength, is_anomaly, anomaly_type
(used by the notebook for both the LSTM AE and the type classifier).
"""

import argparse, csv, json, math, os, random, sys, unicodedata, uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

# ─── Geo / UA pools ───────────────────────────────────────────────────────────

EU_GEO_PROFILE = {
    "FR": {"weight": 0.60, "cities": ["Paris", "Lyon", "Marseille", "Lille", "Bordeaux", "Nantes", "Toulouse"], "prefixes": [("2",), ("80", "12"), ("81", "64"), ("86", "192"), ("90",)]},
    "BE": {"weight": 0.08, "cities": ["Brussels", "Antwerp", "Ghent", "Liège"],                                  "prefixes": [("37", "60"), ("46", "18"), ("62", "235"), ("91", "176")]},
    "DE": {"weight": 0.08, "cities": ["Berlin", "Hamburg", "Munich", "Frankfurt"],                               "prefixes": [("46", "5"), ("80", "128"), ("87", "123"), ("91", "0")]},
    "ES": {"weight": 0.07, "cities": ["Madrid", "Barcelona", "Valencia", "Seville"],                             "prefixes": [("80", "58"), ("81", "32"), ("83", "32"), ("95", "16")]},
    "IT": {"weight": 0.06, "cities": ["Milan", "Rome", "Turin", "Bologna"],                                      "prefixes": [("79", "0"), ("80", "16"), ("82", "48"), ("93", "32")]},
    "NL": {"weight": 0.05, "cities": ["Amsterdam", "Rotterdam", "Utrecht"],                                      "prefixes": [("77", "160"), ("80", "56"), ("84", "104")]},
    "PT": {"weight": 0.03, "cities": ["Lisbon", "Porto", "Braga"],                                               "prefixes": [("85", "240"), ("88", "157"), ("94", "60")]},
    "CH": {"weight": 0.02, "cities": ["Geneva", "Zurich", "Basel"],                                              "prefixes": [("77", "56"), ("80", "218"), ("85", "0")]},
    "LU": {"weight": 0.01, "cities": ["Luxembourg"],                                                              "prefixes": [("188", "42"), ("188", "43")]},
}

BROWSER_POOL = [
    ("WEB",            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123.0.0.0 Safari/537.36"),
    ("WEB",            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 Version/17.4 Safari/605.1.15"),
    ("WEB",            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36"),
    ("MOBILE_ANDROID", "mobileapp/4.2.1 (android; sdk=34; locale=fr-FR)"),
    ("MOBILE_ANDROID", "mobileapp/4.1.7 (android; sdk=33; locale=fr-FR)"),
    ("MOBILE_IOS",     "mobileapp/4.3.0 (ios; build=812; locale=fr-FR)"),
    ("MOBILE_IOS",     "mobileapp/4.2.4 (ios; build=805; locale=fr-FR)"),
]

WEEKDAY_WEIGHTS = [1.25, 1.25, 1.20, 1.15, 1.05, 0.50, 0.40]

CSV_COLUMNS = [
    "id", "insuredId", "status", "sessionId", "action", "httpCode",
    "ip", "userAgent", "requestData", "requestReturn", "createdAt",
    "type", "environmentId", "device",
    "companyIdList", "companyGroupIdList", "insurerIdList",
    "companySectionIdList", "insurerCodeIdList",
    "healthcareNetworkIdList", "domainIdList",
    "subType", "countryCode", "city", "month",
    "sessionNumber", "sequenceInSession",
    "sessionLength", "is_anomaly", "anomaly_type",
]

# ─── User personas ─────────────────────────────────────────────────────────────
# Each persona has: login-type weights, route weights, session-length range
PERSONAS = {
    "frequent_checker": {
        "weight": 0.30,
        "login_probs": {"direct": 0.20, "sso": 0.50, "derogatory": 0.05, "mfa": 0.20, "mfa_regen": 0.05},
        "route_probs": {"refunds": 0.40, "tp_card": 0.30, "documents": 0.15, "contact": 0.10, "personal_info": 0.05},
        "n_routes": (1, 2),  # range of routes per session
    },
    "admin_delegated": {
        "weight": 0.20,
        "login_probs": {"direct": 0.05, "sso": 0.20, "derogatory": 0.60, "mfa": 0.10, "mfa_regen": 0.05},
        "route_probs": {"banking": 0.35, "beneficiaries": 0.30, "documents": 0.15, "preferences": 0.10, "contact": 0.10},
        "n_routes": (1, 3),
    },
    "self_service": {
        "weight": 0.25,
        "login_probs": {"direct": 0.35, "sso": 0.20, "derogatory": 0.00, "mfa": 0.35, "mfa_regen": 0.10},
        "route_probs": {"personal_info": 0.30, "documents": 0.25, "refunds": 0.20, "banking": 0.15, "contact": 0.10},
        "n_routes": (1, 3),
    },
    "contact_heavy": {
        "weight": 0.15,
        "login_probs": {"direct": 0.30, "sso": 0.30, "derogatory": 0.00, "mfa": 0.30, "mfa_regen": 0.10},
        "route_probs": {"contact_general": 0.35, "claims": 0.25, "resiliation": 0.15, "documents": 0.15, "refunds": 0.10},
        "n_routes": (1, 2),
    },
    "new_user": {
        "weight": 0.10,
        "login_probs": {"direct": 0.50, "sso": 0.10, "derogatory": 0.00, "mfa": 0.35, "mfa_regen": 0.05},
        "route_probs": {"personal_info": 0.30, "tp_card": 0.25, "documents": 0.20, "preferences": 0.15, "banking": 0.10},
        "n_routes": (1, 2),
    },
}

# ─── Login sequences (from auth_flow in actions_order-v2.json) ────────────────
LOGIN_SEQUENCES = {
    "direct":    ["Connexion"],
    "sso":       ["Connexion SSO"],
    "derogatory":["Connexion en tant que"],
    "mfa":       ["Connexion", "Validation MFA"],
    "mfa_regen": ["Connexion", "Sélection email MFA", "Régénération code MFA", "Validation MFA"],
}

LOGOUT_BY_LOGIN = {
    "direct":     ["Déconnexion"],
    "sso":        ["SSO Disconnect"],
    "derogatory": ["SSO Disconnect"],
    "mfa":        ["Déconnexion"],
    "mfa_regen":  ["Déconnexion"],
}

# ─── Route → action sequences (from routes in actions_order-v2.json) ──────────
# Each route entry: list of (action_value, weight) tuples.
# Multiple entries can be chosen (sequence of 1-2 actions per route visit).
ROUTE_ACTIONS: Dict[str, List[Tuple[str, float]]] = {
    "refunds": [
        ("Exporter remboursement",             0.55),
        ("Télécharger un décompte",            0.30),
        ("Télécharger le certificat d'adhésion",0.15),
    ],
    "tp_card": [
        ("Téléchargement de la carte de tiers payant", 0.45),
        ("Téléchargement carte TP",                    0.25),
        ("Renvoi carte TP papier",                     0.15),
        ("Envoi carte TP par mail",                    0.10),
        ("Ajout de la carte tiers payant dans le wallet", 0.05),
    ],
    "documents": [
        ("Envoi d'un document",                                       0.35),
        ("Ouverture d'un document contractuel",                       0.30),
        ("Partager un justificatif du PACS",                          0.20),
        ("Partager un justificatif sur l'honneur de vie commune",     0.15),
    ],
    "personal_info": [
        ("Changer ses informations personnel",              0.60),
        ("Changement de Mot de passe",                      0.25),
        ("Demande de réinitialisation de mot de passe",     0.15),
    ],
    "banking": [
        ("Changer les coordonnées bancaire",                                    0.35),
        ("Signature électronique d'un mandat sepa",                             0.30),
        ("Changer les coordonnées bancaire, changement de RIB pour les cotisations",   0.20),
        ("Changer les coordonnées bancaire, changement de RIB pour les remboursements",0.15),
    ],
    "beneficiaries": [
        ("Signature électronique d'un mandat sepa",                          0.40),
        ("Changer l'organisme de rattachement sécu. Social",                 0.30),
        ("Changer les coordonnées bancaire, changement de RIB de bénéficiaire", 0.30),
    ],
    "preferences": [
        ("Changer l'organisme de rattachement sécu. Social",  0.45),
        ("Renvoi carte TP papier",                            0.30),
        ("Envoi carte TP par mail",                           0.25),
    ],
    "contact_general": [
        ("Envoi d'un message",                                                    0.30),
        ("Contactez nous : via message (Mon adhésion, Autres)",                   0.15),
        ("Contactez nous : via message (Mes garanties)",                          0.15),
        ("Contactez nous : via message (Mes remboursements)",                     0.15),
        ("Contactez nous : via message (Mes cotisations)",                        0.10),
        ("Contactez nous : via message (Mes prises en charge/devis)",             0.08),
        ("Contactez nous : via message (Ma carte tiers-payant)",                  0.05),
        ("Contactez nous : via message (Ma télétransmission - rattachement automatique avec votre Régime Obligatoire)", 0.02),
    ],
    "claims": [
        ("Envoi d'une réclamation",                  0.65),
        ("demander un renvoi par mail d'échéancier", 0.35),
    ],
    "resiliation": [
        ("envoi d'une demande de résiliation",       1.00),
    ],
}

# ─── Time-of-day route modifiers ──────────────────────────────────────────────
def time_of_day_modifiers(hour: int) -> Dict[str, float]:
    if 7 <= hour <= 9:    # morning rush — quick checks
        return {"tp_card": 1.8, "refunds": 1.4, "contact_general": 0.6, "banking": 0.7}
    if 10 <= hour <= 12:  # late morning — heavier tasks
        return {"documents": 1.4, "banking": 1.3, "personal_info": 1.2, "contact_general": 1.2}
    if 12 <= hour <= 14:  # lunch — contact + claims
        return {"contact_general": 1.5, "claims": 1.6, "resiliation": 1.3, "tp_card": 1.2}
    if 14 <= hour <= 17:  # afternoon — admin
        return {"banking": 1.4, "beneficiaries": 1.5, "documents": 1.2, "personal_info": 1.1}
    if 17 <= hour <= 20:  # evening — self-service
        return {"personal_info": 1.4, "banking": 1.2, "contact_general": 1.3, "resiliation": 1.2}
    return {}  # night: no modifier (anomaly territory)

# ─── Country modifiers ────────────────────────────────────────────────────────
COUNTRY_ROUTE_MODIFIERS: Dict[str, Dict[str, float]] = {
    "FR": {"refunds": 1.2, "tp_card": 1.4, "beneficiaries": 1.2},
    "BE": {"banking": 1.3, "personal_info": 1.2},
    "DE": {"personal_info": 1.3, "documents": 1.2},
    "ES": {"contact_general": 1.3, "claims": 1.2},
    "IT": {"documents": 1.3, "contact_general": 1.2},
    "NL": {"banking": 1.2, "refunds": 1.2},
    "PT": {"contact_general": 1.2},
    "CH": {"banking": 1.4},
    "LU": {"banking": 1.3, "documents": 1.2},
}

# ─── Device modifiers ─────────────────────────────────────────────────────────
DEVICE_ROUTE_MODIFIERS: Dict[str, Dict[str, float]] = {
    "MOBILE_IOS":     {"tp_card": 2.0, "refunds": 1.3, "banking": 0.5, "documents": 0.6},
    "MOBILE_ANDROID": {"tp_card": 1.6, "refunds": 1.3, "banking": 0.6, "documents": 0.7},
    "WEB":            {"banking": 1.6, "documents": 1.5, "personal_info": 1.4, "beneficiaries": 1.3},
}

# ─── Month/seasonal modifiers ─────────────────────────────────────────────────
def month_route_modifiers(month: int, day: int) -> Dict[str, float]:
    mods: Dict[str, float] = {}
    if month in (1, 2):
        mods["personal_info"] = 1.5   # new year account setup
    if month == 9:
        mods["beneficiaries"] = 1.8   # back to school — add children
        mods["documents"] = 1.3
    if month == 12:
        mods["resiliation"] = 1.6
        mods["contact_general"] = 1.3
    if day >= 25:                     # end of month — billing inquiries
        mods["claims"] = 1.5
        mods["contact_general"] = 1.3
    return mods

# ─── Anomaly types ────────────────────────────────────────────────────────────
ANOMALY_TYPES = [
    "rapid_fire",    # actions every 2–5 s
    "unusual_hour",  # active at 02:00–04:00
    "geo_jump",      # IP switches continent mid-session
    "repeated_fail", # 3+ consecutive KO on sensitive actions
    "skip_login",    # session starts without a login action
    "impossible_seq",# logout followed by more actions in same session
]

# ─── Data classes ─────────────────────────────────────────────────────────────

@dataclass
class ActionDef:
    value: str
    type_name: str
    sub_type: str

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
    persona: str
    annual_target: int
    session_id: Optional[str] = None
    sessions_generated: int = 0
    seen_actions: Set[str] = field(default_factory=set)

@dataclass
class EventPlan:
    action: ActionDef
    timestamp: datetime
    sequence_in_session: int
    session_number: int
    ip_override: Optional[str] = None
    force_ko: bool = False
    is_anomaly: int = 0
    anomaly_type: str = ""

# ─── Load actions from JSON ────────────────────────────────────────────────────

def load_actions_from_json(path: str) -> Dict[str, ActionDef]:
    """Returns {action_value: ActionDef} for all tracked actions."""
    with open(path, encoding="utf-8") as f:
        apis = json.load(f)
    catalog: Dict[str, ActionDef] = {}
    for entry in apis:
        at = entry.get("actionTracking")
        if not at:
            continue
        value = at["value"]
        if value not in catalog:
            catalog[value] = ActionDef(
                value=value,
                type_name=at["type"].replace("AuditTrailType.", ""),
                sub_type=at.get("subType") or "",
            )
    return catalog

# ─── Helpers ──────────────────────────────────────────────────────────────────

def weighted_choice(items: List[Tuple[object, float]], rng: random.Random):
    total = sum(w for _, w in items)
    pick = rng.random() * total
    upto = 0.0
    for item, w in items:
        upto += w
        if upto >= pick:
            return item
    return items[-1][0]

def random_public_ip(prefix: Tuple[str, ...], rng: random.Random) -> str:
    parts = [int(p) for p in prefix]
    while len(parts) < 4:
        parts.append(rng.randint(2, 250) if len(parts) == 3 else rng.randint(0, 255))
    if parts[0] in {10, 127, 169, 172, 192}:
        parts[0] = rng.choice([37, 46, 62, 77, 80, 83, 85, 86, 87, 88, 90, 91, 95])
    return ".".join(str(p) for p in parts[:4])

def random_foreign_ip(rng: random.Random) -> str:
    octets = [rng.choice([41, 102, 103, 104, 196, 197, 198]), rng.randint(0, 255), rng.randint(0, 255), rng.randint(2, 250)]
    return ".".join(str(o) for o in octets)

def build_pools() -> Dict[str, List[int]]:
    return {
        "company_id":           [27011, 27012, 27013, 27021],
        "company_group_id":     [12001, 12002, 12003],
        "company_section_id":   [110027, 110028, 110029],
        "insurer_id":           [1, 2, 3],
        "insurer_code_id":      [302, 303, 304],
        "healthcare_network_id":[1, 2, 3],
        "domain_id":            [10, 11, 12],
        "environment_id":       [10, 11],
    }

def build_users(rng: random.Random, count: int, annual_target: int, pools: Dict[str, List[int]]) -> List[UserProfile]:
    geo_choices = [(c, p["weight"]) for c, p in EU_GEO_PROFILE.items()]
    persona_choices = [(p, d["weight"]) for p, d in PERSONAS.items()]
    users = []
    for _ in range(count):
        country = weighted_choice(geo_choices, rng)
        geo = EU_GEO_PROFILE[country]
        city = rng.choice(geo["cities"])
        prefix = rng.choice(geo["prefixes"])
        device, ua = rng.choice(BROWSER_POOL)
        persona = weighted_choice(persona_choices, rng)
        users.append(UserProfile(
            insured_id=str(rng.randint(10_000_000, 99_999_999)),
            country_code=country, city=city,
            current_ip=random_public_ip(prefix, rng),
            home_ip_prefix=prefix, device=device, user_agent=ua,
            company_id=rng.choice(pools["company_id"]),
            company_group_id=rng.choice(pools["company_group_id"]),
            company_section_id=rng.choice(pools["company_section_id"]),
            insurer_id=rng.choice(pools["insurer_id"]),
            insurer_code_id=rng.choice(pools["insurer_code_id"]),
            healthcare_network_id=rng.choice(pools["healthcare_network_id"]),
            domain_id=rng.choice(pools["domain_id"]),
            environment_id=rng.choice(pools["environment_id"]),
            persona=persona, annual_target=annual_target,
        ))
    return users

def random_time_in_month(month_start: datetime, month_end: datetime, rng: random.Random) -> datetime:
    days, day_weights = [], []
    cursor = month_start
    while cursor <= month_end:
        days.append(cursor)
        w = WEEKDAY_WEIGHTS[cursor.weekday()]
        if cursor.day in {1, 2, 3, 28, 29, 30, 31}:
            w *= 1.08
        day_weights.append(w)
        cursor += timedelta(days=1)
    day = weighted_choice(list(zip(days, day_weights)), rng)
    peaks = [(8.5, 0.28), (12.5, 0.18), (18.0, 0.28), (10.5, 0.13), (15.0, 0.13)]
    peak_h = weighted_choice(peaks, rng)
    h = int(peak_h)
    m = int((peak_h - h) * 60)
    dt = day.replace(hour=h, minute=m, second=0, microsecond=0)
    dt += timedelta(minutes=rng.randint(-40, 40), seconds=rng.randint(0, 59))
    return max(min(dt, month_end - timedelta(minutes=5)), month_start + timedelta(minutes=5))

def inter_action_seconds(route: str, rng: random.Random, rapid: bool = False) -> int:
    if rapid:
        return rng.randint(2, 7)
    ranges = {
        "login":    (10, 60),
        "logout":   (5, 30),
        "refunds":  (30, 180),
        "tp_card":  (20, 120),
        "documents":(60, 480),
        "personal_info": (90, 600),
        "banking":  (120, 900),
        "beneficiaries": (120, 720),
        "preferences":   (60, 360),
        "contact_general": (90, 600),
        "claims":   (90, 480),
        "resiliation": (120, 720),
    }
    lo, hi = ranges.get(route, (60, 300))
    return rng.randint(lo, hi)

# ─── Route selection ──────────────────────────────────────────────────────────

def pick_routes(user: UserProfile, n: int, login_type: str, start_time: datetime, rng: random.Random) -> List[str]:
    persona = PERSONAS[user.persona]
    base_weights = dict(persona["route_probs"])

    # Apply all modifiers
    all_mods = [
        time_of_day_modifiers(start_time.hour),
        COUNTRY_ROUTE_MODIFIERS.get(user.country_code, {}),
        DEVICE_ROUTE_MODIFIERS.get(user.device, {}),
        month_route_modifiers(start_time.month, start_time.day),
    ]
    for mods in all_mods:
        for route, factor in mods.items():
            if route in base_weights:
                base_weights[route] *= factor

    # SSO/derogatory users lean toward banking + beneficiaries
    if login_type in ("sso", "derogatory"):
        for r in ("banking", "beneficiaries"):
            if r in base_weights:
                base_weights[r] *= 1.5

    # Wallet only available on iOS
    if user.device != "MOBILE_IOS" and "tp_card" in base_weights:
        base_weights["tp_card"] *= 0.7

    items = [(r, w) for r, w in base_weights.items() if w > 0]
    chosen: List[str] = []
    for _ in range(n):
        r = weighted_choice(items, rng)
        chosen.append(r)
    return chosen

def pick_action_from_route(route: str, catalog: Dict[str, ActionDef],
                           rng: random.Random, device: str) -> Optional[ActionDef]:
    candidates = ROUTE_ACTIONS.get(route, [])
    if not candidates:
        return None
    # On non-iOS, reduce wallet action weight to 0
    if device != "MOBILE_IOS":
        candidates = [(a, w if a != "Ajout de la carte tiers payant dans le wallet" else 0.0)
                      for a, w in candidates]
    candidates = [(a, w) for a, w in candidates if w > 0]
    if not candidates:
        return None
    action_value = weighted_choice(candidates, rng)
    return catalog.get(action_value)

# ─── Session building ──────────────────────────────────────────────────────────

def build_normal_session(
    user: UserProfile,
    month_start: datetime,
    month_end: datetime,
    catalog: Dict[str, ActionDef],
    rng: random.Random,
    session_number: int,
) -> List[EventPlan]:
    persona = PERSONAS[user.persona]
    login_type = weighted_choice(list(persona["login_probs"].items()), rng)
    start_time = random_time_in_month(month_start, month_end, rng)
    n_routes = rng.randint(*persona["n_routes"])
    routes = pick_routes(user, n_routes, login_type, start_time, rng)

    action_sequence: List[Tuple[str, str]] = []  # [(action_value, route_label)]
    for a in LOGIN_SEQUENCES[login_type]:
        action_sequence.append((a, "login"))
    for route in routes:
        for _ in range(rng.randint(1, 2)):  # 1-2 actions per route visit
            action_obj = pick_action_from_route(route, catalog, rng, user.device)
            if action_obj:
                action_sequence.append((action_obj.value, route))
    for a in LOGOUT_BY_LOGIN[login_type]:
        action_sequence.append((a, "logout"))

    plans: List[EventPlan] = []
    current_time = start_time
    for seq_idx, (action_value, route_label) in enumerate(action_sequence, start=1):
        action_obj = catalog.get(action_value)
        if action_obj is None:
            continue
        plans.append(EventPlan(
            action=action_obj, timestamp=current_time,
            sequence_in_session=seq_idx, session_number=session_number,
        ))
        current_time += timedelta(seconds=inter_action_seconds(route_label, rng))
        if current_time > month_end:
            current_time = month_end - timedelta(minutes=max(0, len(action_sequence) - seq_idx))

    return plans


def build_anomaly_session(
    user: UserProfile,
    month_start: datetime,
    month_end: datetime,
    catalog: Dict[str, ActionDef],
    rng: random.Random,
    session_number: int,
    anomaly_type: Optional[str] = None,
) -> List[EventPlan]:
    if anomaly_type is None:
        anomaly_type = rng.choice(ANOMALY_TYPES)

    start_time = random_time_in_month(month_start, month_end, rng)
    if anomaly_type == "unusual_hour":
        start_time = start_time.replace(hour=rng.randint(2, 4), minute=rng.randint(0, 59))

    rapid = anomaly_type == "rapid_fire"
    geo_jump = anomaly_type == "geo_jump"
    foreign_ip = random_foreign_ip(rng)

    # Build action list
    all_actions = list(catalog.values())
    login_actions  = [a for a in all_actions if "Connexion" in a.value or "login" in a.type_name.lower()]
    banking_actions = [a for a in all_actions if "BANKING" in a.type_name or "bancaire" in a.value.lower()]
    logout_actions  = [a for a in all_actions if "Déconnexion" in a.value or "Disconnect" in a.value]

    if anomaly_type == "skip_login":
        seq = [rng.choice(banking_actions), rng.choice(banking_actions),
               rng.choice(banking_actions), rng.choice(logout_actions or [rng.choice(all_actions)])]
    elif anomaly_type == "impossible_seq":
        mid = [rng.choice(all_actions) for _ in range(2)]
        seq = ([login_actions[0]] if login_actions else []) + mid + \
              (logout_actions[:1] if logout_actions else []) + \
              [rng.choice(banking_actions), rng.choice(all_actions)]
    elif anomaly_type == "repeated_fail":
        seq = ([login_actions[0]] if login_actions else []) + \
              [rng.choice(banking_actions) for _ in range(4)] + \
              (logout_actions[:1] if logout_actions else [])
    else:
        login_type = rng.choice(["direct", "sso", "mfa"])
        seq_vals = LOGIN_SEQUENCES[login_type]
        routes = [rng.choice(list(ROUTE_ACTIONS.keys())) for _ in range(rng.randint(1, 3))]
        seq_values: List[str] = list(seq_vals)
        for route in routes:
            candidates = [a for a, _ in ROUTE_ACTIONS[route]]
            seq_values.append(rng.choice(candidates))
        seq_values += LOGOUT_BY_LOGIN[login_type]
        seq = [catalog[v] for v in seq_values if v in catalog]

    plans: List[EventPlan] = []
    current_time = start_time
    for seq_idx, action_obj in enumerate(seq, start=1):
        ip_override = foreign_ip if geo_jump and seq_idx > 2 else None
        plans.append(EventPlan(
            action=action_obj, timestamp=current_time,
            sequence_in_session=seq_idx, session_number=session_number,
            ip_override=ip_override,
            force_ko=(anomaly_type == "repeated_fail" and "BANKING" in action_obj.type_name),
            is_anomaly=1, anomaly_type=anomaly_type,
        ))
        current_time += timedelta(seconds=inter_action_seconds("login", rng, rapid=rapid))
        if current_time > month_end:
            current_time = month_end - timedelta(minutes=max(0, len(seq) - seq_idx))

    return plans

# ─── Event record builder ─────────────────────────────────────────────────────

def choose_status(action: ActionDef, is_anomaly: bool, force_ko: bool, rng: random.Random) -> str:
    if force_ko:
        return "KO"
    if is_anomaly:
        return "KO" if rng.random() < 0.35 else "OK"
    failure_rates = {
        "LOGGING_ACTIONS": 0.03, "BANKING_ACTIONS": 0.08,
        "CONTACT_ACTIONS": 0.04, "DOCUMENT_ACTIONS": 0.05,
        "INSURED_ACTIONS": 0.03, "OPEN_ACTIONS": 0.03, "BACKOFFICE_ACTIONS": 0.02,
    }
    rate = failure_rates.get(action.type_name, 0.04)
    return "KO" if rng.random() < rate else "OK"

def choose_http_code(status: str, action: ActionDef, rng: random.Random) -> str:
    if status == "OK":
        if "LOGGING" in action.type_name:
            return str(rng.choice([200, 204]))
        if action.type_name in {"BANKING_ACTIONS", "CONTACT_ACTIONS", "DOCUMENT_ACTIONS"}:
            return str(rng.choice([200, 201, 202]))
        return str(rng.choice([200, 204]))
    if "LOGGING" in action.type_name:
        return str(rng.choice([401, 403]))
    return str(rng.choice([400, 403, 409, 422, 500]))

def build_request_data(action: ActionDef, user: UserProfile, rng: random.Random) -> str:
    t = action.type_name
    if "LOGGING" in t:
        return json.dumps({"login": user.insured_id, "channel": "mobile" if "MOBILE" in user.device else "web", "country": user.country_code})
    if "BANKING" in t:
        return json.dumps({"iban_last4": str(rng.randint(1000, 9999)), "bic": rng.choice(["AGRIFRPP", "BNPAFRPP", "SOGEFRPP"]), "country": user.country_code})
    if "CONTACT" in t:
        return json.dumps({"subject": action.value, "message": rng.choice(["Besoin d'information", "Question sur remboursement", "Problème accès espace"]), "priority": rng.choice(["low", "normal", "high"])})
    if "DOCUMENT" in t:
        return json.dumps({"document": action.value, "tag": rng.choice(["medical", "administrative", "identity"])})
    if "INSURED" in t or "OPEN" in t:
        return json.dumps({"insuredId": user.insured_id, "action": action.value})
    return json.dumps({"action": action.value})

def build_request_return(status: str, action: ActionDef, rng: random.Random) -> Optional[str]:
    if status == "OK":
        if action.type_name in {"LOGGING_ACTIONS", "OPEN_ACTIONS", "INSURED_ACTIONS"} and rng.random() < 0.50:
            return None
        payload: Dict = {"status": "success", "reference": f"REF-{rng.randint(100000, 999999)}"}
        if "INSURED" in action.type_name and "refund" in action.sub_type.lower():
            payload["claimId"] = f"CLM-{rng.randint(1000000, 9999999)}"
        return json.dumps(payload)
    if "CONTACT" in action.type_name and rng.random() < 0.4:
        return "SubTheme should not be empty !"
    return json.dumps({"status": "error", "code": rng.choice(["AUTH_FAILED", "VALIDATION_ERROR", "SERVER_ERROR", "BUSINESS_RULE_REJECTED"])})

def gen_id_list(val: int, rng: random.Random, variability: float) -> List[int]:
    return [] if rng.random() < variability else [val]

def format_ts(dt: datetime, mode: str) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S") if mode == "java" else dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")

def build_event_record(plan: EventPlan, user: UserProfile, session_length: int,
                       created_at_format: str, rng: random.Random) -> Dict:
    action = plan.action
    if plan.sequence_in_session == 1 or user.session_id is None:
        user.session_id = str(rng.randint(100000, 9999999))

    if plan.ip_override:
        ip = plan.ip_override
    else:
        if user.device.startswith("MOBILE") and rng.random() < 0.10:
            user.current_ip = random_public_ip(user.home_ip_prefix, rng)
        ip = user.current_ip

    status  = choose_status(action, bool(plan.is_anomaly), plan.force_ko, rng)
    http    = choose_http_code(status, action, rng)
    req_d   = build_request_data(action, user, rng)
    req_r   = build_request_return(status, action, rng)

    event: Dict = {
        "id":             str(uuid.uuid4()),
        "insuredId":      user.insured_id,
        "status":         status,
        "sessionId":      user.session_id,
        "action":         action.value,
        "httpCode":       http,
        "ip":             ip,
        "userAgent":      user.user_agent,
        "requestData":    req_d,
        "requestReturn":  req_r,
        "createdAt":      format_ts(plan.timestamp.astimezone(timezone.utc), created_at_format),
        "type":           action.type_name,
        "environmentId":  str(user.environment_id),
        "device":         user.device,
        "companyIdList":          gen_id_list(user.company_id, rng, 0.10),
        "companyGroupIdList":     gen_id_list(user.company_group_id, rng, 0.08),
        "insurerIdList":          gen_id_list(user.insurer_id, rng, 0.10),
        "companySectionIdList":   gen_id_list(user.company_section_id, rng, 0.10),
        "insurerCodeIdList":      gen_id_list(user.insurer_code_id, rng, 0.10),
        "healthcareNetworkIdList":gen_id_list(user.healthcare_network_id, rng, 0.15),
        "domainIdList":           gen_id_list(user.domain_id, rng, 0.10),
        "subType":        action.sub_type or None,
        "countryCode":    user.country_code,
        "city":           user.city,
        "month":          plan.timestamp.strftime("%Y-%m"),
        "sessionNumber":  plan.session_number,
        "sequenceInSession": plan.sequence_in_session,
        "sessionLength":  session_length,
        "is_anomaly":     plan.is_anomaly,
        "anomaly_type":   plan.anomaly_type,
    }

    if "logout" in action.value.lower() or "disconnect" in action.value.lower():
        user.session_id = None

    return {k: v for k, v in event.items() if v is not None}

def serialize_for_csv(event: Dict) -> Dict:
    out = dict(event)
    for key in ["companyIdList", "companyGroupIdList", "insurerIdList",
                "companySectionIdList", "insurerCodeIdList",
                "healthcareNetworkIdList", "domainIdList"]:
        if key in out:
            out[key] = json.dumps(out[key])
    return out

# ─── Month iteration ──────────────────────────────────────────────────────────

def month_range(start: datetime, end: datetime) -> List[Tuple[datetime, datetime]]:
    months = []
    cursor = datetime(start.year, start.month, 1, tzinfo=timezone.utc)
    while cursor <= end:
        nxt = datetime(cursor.year + (1 if cursor.month == 12 else 0),
                       (cursor.month % 12) + 1, 1, tzinfo=timezone.utc)
        months.append((cursor, nxt - timedelta(seconds=1)))
        cursor = nxt
    return months

def choose_session_lengths(total: int, rng: random.Random) -> List[int]:
    lengths, remaining = [], total
    while remaining > 0:
        if remaining <= 8:
            lengths.append(remaining)
            break
        candidates = [(l, w) for l, w in [(3, 0.15), (4, 0.20), (5, 0.25), (6, 0.20), (7, 0.13), (8, 0.07)] if l <= remaining]
        length = weighted_choice(candidates, rng)
        if remaining - length in {1, 2}:
            length += remaining - length
        lengths.append(length)
        remaining -= length
    return lengths

# ─── CLI ──────────────────────────────────────────────────────────────────────

def parse_args(argv):
    p = argparse.ArgumentParser(description="Generate audit-trail training data (v3)")
    p.add_argument("--backend-apis",    required=True, help="path to backend-apis-actions.json")
    p.add_argument("--output",          default="audit_trail_2025.csv")
    p.add_argument("--start-date",      default="2025-01-01")
    p.add_argument("--end-date",        default="2025-12-31")
    p.add_argument("--users",           type=int,   default=200)
    p.add_argument("--actions-per-month", type=int, default=8000)
    p.add_argument("--anomaly-rate",    type=float, default=0.05)
    p.add_argument("--seed",            type=int,   default=2025)
    p.add_argument("--created-at-format", choices=["java", "kibana"], default="kibana")
    return p.parse_args(argv)

def main(argv) -> int:
    args = parse_args(argv)
    rng = random.Random(args.seed)

    catalog = load_actions_from_json(args.backend_apis)
    print(f"Loaded {len(catalog)} tracked actions from {args.backend_apis}")

    start = datetime.fromisoformat(args.start_date).replace(tzinfo=timezone.utc)
    end   = (datetime.fromisoformat(args.end_date) + timedelta(hours=23, minutes=59, seconds=59)).replace(tzinfo=timezone.utc)
    months = month_range(start, end)

    if args.actions_per_month % args.users != 0:
        print("actions-per-month must be divisible by users", file=sys.stderr)
        return 2

    apu_month  = args.actions_per_month // args.users
    apu_annual = apu_month * len(months)
    pools = build_pools()
    users = build_users(rng, args.users, apu_annual, pools)

    all_events: List[Dict] = []

    for month_start, month_end in months:
        month_events: List[Tuple[datetime, Dict]] = []
        for user in users:
            lengths = choose_session_lengths(apu_month, rng)
            for length in lengths:
                user.sessions_generated += 1
                snum = user.sessions_generated
                if rng.random() < args.anomaly_rate:
                    plans = build_anomaly_session(user, month_start, month_end, catalog, rng, snum)
                else:
                    plans = build_normal_session(user, month_start, month_end, catalog, rng, snum)
                # Compute actual session length
                true_length = len(plans)
                for plan in plans:
                    event = build_event_record(plan, user, true_length, args.created_at_format, rng)
                    month_events.append((plan.timestamp, event))

        month_events.sort(key=lambda x: x[0])
        all_events.extend(ev for _, ev in month_events)

    # Write CSV
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for ev in all_events:
            row = serialize_for_csv(ev)
            writer.writerow({c: row.get(c, "") for c in CSV_COLUMNS})

    normal  = sum(1 for e in all_events if not e.get("is_anomaly"))
    anomaly = sum(1 for e in all_events if e.get("is_anomaly"))
    by_type: Dict[str, int] = defaultdict(int)
    for e in all_events:
        if e.get("anomaly_type"):
            by_type[e["anomaly_type"]] += 1

    print(json.dumps({
        "total_events":   len(all_events),
        "normal_events":  normal,
        "anomaly_events": anomaly,
        "anomaly_pct":    round(100 * anomaly / max(1, len(all_events)), 2),
        "anomaly_by_type": dict(by_type),
        "unique_actions": len(catalog),
        "users":          args.users,
        "months":         len(months),
    }, ensure_ascii=False, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
