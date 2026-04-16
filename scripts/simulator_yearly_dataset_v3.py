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

import argparse, csv, json, math, os, random, re, sys, unicodedata, uuid
from collections import defaultdict, deque
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
    "type", "environmentId", "device", "persona", "route",
    "prevAction", "nextAction",
    "companyIdList", "companyGroupIdList", "insurerIdList",
    "companySectionIdList", "insurerCodeIdList",
    "healthcareNetworkIdList", "domainIdList",
    "subType", "countryCode", "city", "month",
    "sessionNumber", "sequenceInSession",
    "sessionLength", "sessionDurationSeconds",
    "timeDeltaSinceLastAction", "hourOfDay", "dayOfWeek", "isWeekend",
    "isIpChanged", "uniqueIpsInSession", "cumulativeKOs", "longestKoStreak",
    "hasLoggedIn", "isDeviceChanged", "uniqueDevicesInSession",
    "isDownloadAction", "downloadActionsInSession", "downloadsLast2Minutes",
    "pingPongCount", "sessionRiskScore",
    "is_anomaly", "anomaly_type", "campaignId",
]

SESSION_SUMMARY_COLUMNS = [
    "sessionId", "insuredId", "persona", "countryCode", "city", "month",
    "sessionNumber", "sessionStart", "sessionEnd",
    "startHour", "endHour", "dayOfWeek", "isWeekend",
    "firstAction", "lastAction", "firstRoute", "lastRoute",
    "totalEvents", "totalDurationSeconds",
    "avgInterActionSeconds", "minInterActionSeconds", "maxInterActionSeconds",
    "uniqueActions", "uniqueRoutes", "uniqueIpsUsed", "uniqueDevicesUsed",
    "totalKOs", "totalOKs", "longestKoStreak",
    "hasLogin", "hasLogout", "ipChanged", "deviceChanged",
    "totalDownloadActions", "maxDownloadsIn2Minutes", "pingPongCount",
    "riskScoreMax", "riskScoreAvg", "endedAbruptly",
    "is_anomaly", "anomaly_event_count", "primary_anomaly_type", "anomaly_types",
    "campaignIds", "actionSequenceSignature", "routeSequenceSignature",
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
DEFAULT_LOGIN_SEQUENCES = {
    "direct":    ["Connexion"],
    "sso":       ["Connexion SSO"],
    "derogatory":["Connexion en tant que"],
    "mfa":       ["Connexion", "Validation MFA"],
    "mfa_regen": ["Connexion", "Sélection email MFA", "Régénération code MFA", "Validation MFA"],
}

DEFAULT_LOGOUT_BY_LOGIN = {
    "direct":     ["Déconnexion"],
    "sso":        ["SSO Disconnect"],
    "derogatory": ["SSO Disconnect"],
    "mfa":        ["Déconnexion"],
    "mfa_regen":  ["Déconnexion"],
}

# ─── Route → action sequences (from routes in actions_order-v2.json) ──────────
# Each route entry: list of (action_value, weight) tuples.
# Multiple entries can be chosen (sequence of 1-2 actions per route visit).
DEFAULT_ROUTE_ACTIONS: Dict[str, List[Tuple[str, float]]] = {
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

# Route groups used by personas/modifiers. Each group is resolved from one or
# more concrete frontend routes in actions_order-v2.json.
ROUTE_GROUP_SOURCES: Dict[str, Tuple[str, ...]] = {
    "refunds": ("Refunds", "Home", "Requests"),
    "tp_card": ("TpCardDownload", "GlobalPreferences", "Home"),
    "documents": ("Documents", "Requests", "UpdateBeneficiaries"),
    "personal_info": ("PersonalInformation",),
    "banking": ("BankingInformation", "UpdateBeneficiaries"),
    "beneficiaries": ("Beneficiaries", "UpdateBeneficiaries"),
    "preferences": ("GlobalPreferences",),
    "contact_general": ("Home", "Requests", "BankingInformation"),
    "claims": ("Home", "Requests", "BankingInformation"),
    "resiliation": ("Home", "Requests"),
}


def clone_sequence_map(source: Dict[str, List[str]]) -> Dict[str, List[str]]:
    return {key: list(values) for key, values in source.items()}


def clone_weighted_route_map(
    source: Dict[str, List[Tuple[str, float]]]
) -> Dict[str, List[Tuple[str, float]]]:
    return {key: list(values) for key, values in source.items()}


LOGIN_SEQUENCES = clone_sequence_map(DEFAULT_LOGIN_SEQUENCES)
LOGOUT_BY_LOGIN = clone_sequence_map(DEFAULT_LOGOUT_BY_LOGIN)
ROUTE_ACTIONS = clone_weighted_route_map(DEFAULT_ROUTE_ACTIONS)

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

ANOMALY_TYPES += [
    "ping_pong_loop",
    "data_exfiltration",
    "impossible_device_switch",
    "distributed_brute_force",
    "zombie_session",
]

SESSION_SCOPED_ANOMALY_TYPES = [
    anomaly for anomaly in ANOMALY_TYPES if anomaly != "distributed_brute_force"
]

DOWNLOAD_ACTION_KEYWORDS = (
    "Télécharg",
    "document",
    "certificat",
    "décompte",
    "wallet",
    "carte TP",
)

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
    route_label: str = ""
    ip_override: Optional[str] = None
    device_override: Optional[str] = None
    user_agent_override: Optional[str] = None
    country_code_override: Optional[str] = None
    city_override: Optional[str] = None
    force_status: Optional[str] = None
    is_anomaly: int = 0
    anomaly_type: str = ""
    campaign_id: str = ""


@dataclass
class AttackCampaign:
    campaign_id: str
    start_time: datetime
    shared_ip: str
    country_code: str
    city: str
    max_slots: int
    participants: Set[str] = field(default_factory=set)

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

def load_tracked_api_index(path: str) -> List[Dict[str, str]]:
    """Returns tracked backend APIs as [{method, path, value}]."""
    with open(path, encoding="utf-8") as f:
        apis = json.load(f)

    tracked: List[Dict[str, str]] = []
    for entry in apis:
        at = entry.get("actionTracking")
        if not at:
            continue
        tracked.append({
            "method": (entry.get("method") or "").upper(),
            "path": normalize_api_path(entry.get("path") or ""),
            "value": at["value"],
        })
    return tracked


def load_actions_order(path: str) -> Dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def normalize_api_path(path: str) -> str:
    normalized = (path or "").strip()
    if normalized.endswith("/") and normalized != "/":
        return normalized[:-1]
    return normalized or "/"


def parse_api_spec(api_spec: str) -> Optional[Tuple[str, str]]:
    parts = api_spec.strip().split(" ", 1)
    if len(parts) != 2:
        return None
    return parts[0].upper(), normalize_api_path(parts[1])


def compile_api_path_pattern(path_pattern: str):
    escaped = re.escape(path_pattern)
    escaped = re.sub(r"\\\{[^}]+\\\}", r"[^/]+", escaped)
    escaped = escaped.replace(r"\*", r"[^?]+")
    return re.compile(rf"^{escaped}$")


def dedupe_preserve_order(values: List[str]) -> List[str]:
    seen: Set[str] = set()
    out: List[str] = []
    for value in values:
        if not value or value in seen:
            continue
        seen.add(value)
        out.append(value)
    return out


def resolve_action_values_for_specs(
    api_specs: List[str],
    tracked_apis: List[Dict[str, str]],
) -> List[str]:
    resolved: List[str] = []
    for spec in api_specs:
        parsed = parse_api_spec(spec)
        if parsed is None:
            continue
        method, path_pattern = parsed
        path_regex = compile_api_path_pattern(path_pattern)
        for entry in tracked_apis:
            if entry["method"] == method and path_regex.match(entry["path"]):
                resolved.append(entry["value"])
    return dedupe_preserve_order(resolved)


def build_auth_sequences_from_actions_order(
    actions_order: Dict,
    tracked_apis: List[Dict[str, str]],
) -> Tuple[Dict[str, List[str]], Dict[str, List[str]], Dict[Tuple[str, str], List[str]]]:
    step_actions: Dict[Tuple[str, str], List[str]] = {}
    for step in actions_order.get("auth_flow", {}).get("steps", []):
        state = step.get("state")
        action_name = step.get("action")
        if not state or not action_name:
            continue
        resolved = resolve_action_values_for_specs(step.get("api", []), tracked_apis)
        if resolved:
            step_actions[(state, action_name)] = resolved

    def step_value(state: str, action_name: str) -> Optional[str]:
        values = step_actions.get((state, action_name), [])
        return values[0] if values else None

    login_value = step_value("login", "submit_credentials")
    select_email_value = step_value("mfa_email_list", "select_email")
    regenerate_value = step_value("mfa", "resend_or_switch")
    validate_mfa_value = step_value("mfa", "validate_code")
    logout_value = step_value("logout", "explicit_logout")

    login_sequences = clone_sequence_map(DEFAULT_LOGIN_SEQUENCES)
    logout_by_login = clone_sequence_map(DEFAULT_LOGOUT_BY_LOGIN)

    if login_value:
        login_sequences["direct"] = [login_value]
    if login_value and validate_mfa_value:
        login_sequences["mfa"] = [login_value, validate_mfa_value]
    if login_value and select_email_value and regenerate_value and validate_mfa_value:
        login_sequences["mfa_regen"] = [
            login_value,
            select_email_value,
            regenerate_value,
            validate_mfa_value,
        ]

    tracked_values = {entry["value"] for entry in tracked_apis}
    if "Connexion SSO" in tracked_values:
        login_sequences["sso"] = ["Connexion SSO"]
    if "Connexion en tant que" in tracked_values:
        login_sequences["derogatory"] = ["Connexion en tant que"]

    if logout_value:
        for login_type in ("direct", "mfa", "mfa_regen"):
            logout_by_login[login_type] = [logout_value]
    if "SSO Disconnect" in tracked_values:
        logout_by_login["sso"] = ["SSO Disconnect"]
        logout_by_login["derogatory"] = ["SSO Disconnect"]

    return login_sequences, logout_by_login, step_actions


def build_route_actions_from_actions_order(
    actions_order: Dict,
    tracked_apis: List[Dict[str, str]],
    catalog: Dict[str, ActionDef],
) -> Tuple[Dict[str, List[Tuple[str, float]]], Dict[str, List[str]]]:
    route_actions_by_name: Dict[str, List[str]] = {}
    for route_name, route_data in actions_order.get("routes", {}).items():
        resolved: List[str] = []
        for route_action in route_data.get("user_actions", []):
            resolved.extend(resolve_action_values_for_specs(route_action.get("api", []), tracked_apis))
        route_actions_by_name[route_name] = [
            value for value in dedupe_preserve_order(resolved) if value in catalog
        ]

    derived_route_actions: Dict[str, List[Tuple[str, float]]] = {}
    for group_name, route_names in ROUTE_GROUP_SOURCES.items():
        resolved_for_group: List[str] = []
        for route_name in route_names:
            resolved_for_group.extend(route_actions_by_name.get(route_name, []))
        resolved_for_group = dedupe_preserve_order(resolved_for_group)

        fallback_weights = dict(DEFAULT_ROUTE_ACTIONS[group_name])
        filtered_values = [value for value in resolved_for_group if value in fallback_weights]

        if filtered_values:
            derived_route_actions[group_name] = [
                (value, fallback_weights[value]) for value in filtered_values
            ]
        elif resolved_for_group:
            derived_route_actions[group_name] = [(value, 1.0) for value in resolved_for_group]
        else:
            derived_route_actions[group_name] = list(DEFAULT_ROUTE_ACTIONS[group_name])

    return derived_route_actions, route_actions_by_name


def initialize_navigation(
    actions_order_path: str,
    backend_apis_path: str,
    catalog: Optional[Dict[str, ActionDef]] = None,
) -> Dict[str, int]:
    """Load auth/route flow from JSON and update the generator globals."""
    global LOGIN_SEQUENCES, LOGOUT_BY_LOGIN, ROUTE_ACTIONS

    if catalog is None:
        catalog = load_actions_from_json(backend_apis_path)

    actions_order = load_actions_order(actions_order_path)
    tracked_apis = load_tracked_api_index(backend_apis_path)

    LOGIN_SEQUENCES, LOGOUT_BY_LOGIN, auth_steps = build_auth_sequences_from_actions_order(
        actions_order, tracked_apis
    )
    ROUTE_ACTIONS, resolved_routes = build_route_actions_from_actions_order(
        actions_order, tracked_apis, catalog
    )

    return {
        "tracked_actions": len(catalog),
        "auth_steps_resolved": len(auth_steps),
        "route_groups": len(ROUTE_ACTIONS),
        "json_routes_with_actions": sum(1 for values in resolved_routes.values() if values),
    }


def dedupe_action_values(values: List[str]) -> List[str]:
    return dedupe_preserve_order(values)


def primary_login_actions() -> Set[str]:
    return {"Connexion", "Connexion SSO", "Connexion en tant que"}


def all_logout_actions() -> Set[str]:
    values: List[str] = []
    for actions in LOGOUT_BY_LOGIN.values():
        values.extend(actions)
    return set(dedupe_action_values(values))


def is_download_action(action_value: str) -> bool:
    lowered = action_value.lower()
    return any(keyword.lower() in lowered for keyword in DOWNLOAD_ACTION_KEYWORDS)


def random_geo_profile(
    rng: random.Random,
    exclude_country: Optional[str] = None,
) -> Tuple[str, str, Tuple[str, ...]]:
    choices = [(code, meta["weight"]) for code, meta in EU_GEO_PROFILE.items() if code != exclude_country]
    country = weighted_choice(choices, rng) if choices else weighted_choice(
        [(code, meta["weight"]) for code, meta in EU_GEO_PROFILE.items()],
        rng,
    )
    profile = EU_GEO_PROFILE[country]
    return country, rng.choice(profile["cities"]), rng.choice(profile["prefixes"])


def pick_alternate_device(
    current_device: str,
    rng: random.Random,
) -> Tuple[str, str]:
    if current_device.startswith("MOBILE"):
        candidates = [(device, ua) for device, ua in BROWSER_POOL if device == "WEB"]
    else:
        candidates = [(device, ua) for device, ua in BROWSER_POOL if device.startswith("MOBILE")]
    if not candidates:
        candidates = [(device, ua) for device, ua in BROWSER_POOL if device != current_device]
    return rng.choice(candidates or BROWSER_POOL)


def default_session_summary_path(output_path: str) -> str:
    path = Path(output_path)
    return str(path.with_name(f"{path.stem}_session_summary{path.suffix or '.csv'}"))


def build_attack_campaign(
    month_start: datetime,
    month_end: datetime,
    rng: random.Random,
) -> AttackCampaign:
    latest_start = month_end - timedelta(minutes=6)
    start_time = random_time_in_month(month_start, max(month_start, latest_start), rng)
    country_code, city, prefix = random_geo_profile(rng)
    return AttackCampaign(
        campaign_id=f"bf-{uuid.uuid4().hex[:10]}",
        start_time=start_time,
        shared_ip=random_public_ip(prefix, rng),
        country_code=country_code,
        city=city,
        max_slots=rng.randint(4, 7),
    )


def pick_or_create_attack_campaign(
    campaigns: List[AttackCampaign],
    user: UserProfile,
    month_start: datetime,
    month_end: datetime,
    rng: random.Random,
) -> AttackCampaign:
    reusable = [
        campaign for campaign in campaigns
        if len(campaign.participants) < campaign.max_slots
        and user.insured_id not in campaign.participants
    ]
    if reusable:
        return min(reusable, key=lambda item: len(item.participants))
    campaign = build_attack_campaign(month_start, month_end, rng)
    campaigns.append(campaign)
    return campaign


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


def infer_route_for_action(action_value: str) -> str:
    if action_value in all_logout_actions():
        return "logout"
    if action_value in primary_login_actions():
        return "login"
    for route_name, candidates in ROUTE_ACTIONS.items():
        for candidate, _ in candidates:
            if candidate == action_value:
                return route_name
    lowered = action_value.lower()
    if "bénéficiaire" in lowered or "organisme de rattachement" in lowered:
        return "beneficiaries"
    if "bancaire" in lowered or "mandat sepa" in lowered or "rib" in lowered:
        return "banking"
    if "message" in lowered or "réclamation" in lowered or "résiliation" in lowered:
        return "contact_general"
    if "document" in lowered or "justificatif" in lowered:
        return "documents"
    if "carte tp" in lowered or "tiers payant" in lowered or "wallet" in lowered:
        return "tp_card"
    if "rembourse" in lowered or "décompte" in lowered or "adhésion" in lowered:
        return "refunds"
    if "mot de passe" in lowered or "information" in lowered:
        return "personal_info"
    return "unknown"


def build_plans_from_action_sequence(
    action_sequence: List[Tuple[ActionDef, str, Dict[str, object]]],
    start_time: datetime,
    month_end: datetime,
    rng: random.Random,
    session_number: int,
    rapid: bool = False,
) -> List[EventPlan]:
    plans: List[EventPlan] = []
    current_time = start_time
    total_actions = len(action_sequence)
    for seq_idx, (action_obj, route_label, overrides) in enumerate(action_sequence, start=1):
        plan = EventPlan(
            action=action_obj,
            timestamp=current_time,
            sequence_in_session=seq_idx,
            session_number=session_number,
            route_label=route_label,
            ip_override=overrides.get("ip_override"),
            device_override=overrides.get("device_override"),
            user_agent_override=overrides.get("user_agent_override"),
            country_code_override=overrides.get("country_code_override"),
            city_override=overrides.get("city_override"),
            force_status=overrides.get("force_status"),
            is_anomaly=int(overrides.get("is_anomaly", 0)),
            anomaly_type=str(overrides.get("anomaly_type", "")),
            campaign_id=str(overrides.get("campaign_id", "")),
        )
        plans.append(plan)

        gap_seconds = overrides.get("gap_seconds")
        if gap_seconds is None:
            gap_seconds = inter_action_seconds(route_label, rng, rapid=rapid)
        current_time += timedelta(seconds=int(gap_seconds))
        if current_time > month_end:
            current_time = month_end - timedelta(minutes=max(0, total_actions - seq_idx))
    return plans

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

    action_sequence: List[Tuple[ActionDef, str, Dict[str, object]]] = []
    for action_value in LOGIN_SEQUENCES[login_type]:
        action_obj = catalog.get(action_value)
        if action_obj:
            action_sequence.append((action_obj, "login", {}))
    for route in routes:
        for _ in range(rng.randint(1, 2)):  # 1-2 actions per route visit
            action_obj = pick_action_from_route(route, catalog, rng, user.device)
            if action_obj:
                action_sequence.append((action_obj, route, {}))
    for action_value in LOGOUT_BY_LOGIN[login_type]:
        action_obj = catalog.get(action_value)
        if action_obj:
            action_sequence.append((action_obj, "logout", {}))

    return build_plans_from_action_sequence(
        action_sequence, start_time, month_end, rng, session_number
    )


def build_anomaly_session(
    user: UserProfile,
    month_start: datetime,
    month_end: datetime,
    catalog: Dict[str, ActionDef],
    rng: random.Random,
    session_number: int,
    anomaly_type: Optional[str] = None,
    campaign: Optional[AttackCampaign] = None,
) -> List[EventPlan]:
    if anomaly_type is None:
        anomaly_type = rng.choice(SESSION_SCOPED_ANOMALY_TYPES)

    route_action_map: Dict[str, List[ActionDef]] = {
        route_name: [catalog[value] for value, _ in values if value in catalog]
        for route_name, values in ROUTE_ACTIONS.items()
    }
    route_names = [route_name for route_name, values in route_action_map.items() if values]
    all_actions = list(catalog.values())
    primary_logins = [catalog[value] for value in primary_login_actions() if value in catalog]
    login_type_candidates = [name for name, values in LOGIN_SEQUENCES.items() if values]
    login_type = rng.choice(login_type_candidates or ["direct"])
    login_actions = [catalog[value] for value in LOGIN_SEQUENCES.get(login_type, []) if value in catalog]
    logout_actions = [catalog[value] for value in LOGOUT_BY_LOGIN.get(login_type, []) if value in catalog]
    if not login_actions and primary_logins:
        login_actions = [primary_logins[0]]
    if not logout_actions:
        logout_actions = [catalog[value] for value in all_logout_actions() if value in catalog]

    banking_actions = [
        action for action in all_actions
        if "BANKING" in action.type_name or "bancaire" in action.value.lower()
    ] or all_actions
    contact_actions = [
        action for action in all_actions if action.type_name == "CONTACT_ACTIONS"
    ] or all_actions
    download_actions = [action for action in all_actions if is_download_action(action.value)] or all_actions

    def choose_route_action(
        preferred_routes: List[str],
        fallback_actions: Optional[List[ActionDef]] = None,
    ) -> Tuple[ActionDef, str]:
        shuffled_routes = list(preferred_routes)
        rng.shuffle(shuffled_routes)
        for route_name in shuffled_routes:
            candidates = route_action_map.get(route_name, [])
            if candidates:
                return rng.choice(candidates), route_name
        pool = fallback_actions or all_actions
        action = rng.choice(pool or all_actions)
        return action, infer_route_for_action(action.value)

    if anomaly_type == "zombie_session":
        latest_start = month_end - timedelta(hours=19)
        if latest_start > month_start + timedelta(minutes=5):
            start_time = random_time_in_month(month_start, latest_start, rng)
        else:
            start_time = month_start + timedelta(minutes=5)
    else:
        start_time = random_time_in_month(month_start, month_end, rng)

    if anomaly_type == "unusual_hour":
        start_time = start_time.replace(hour=rng.randint(2, 4), minute=rng.randint(0, 59))

    rapid = anomaly_type in {"rapid_fire", "ping_pong_loop", "data_exfiltration"}
    action_sequence: List[Tuple[ActionDef, str, Dict[str, object]]] = []

    def add_action(action_obj: Optional[ActionDef], route_label: str, **overrides: object) -> None:
        if action_obj is None:
            return
        metadata: Dict[str, object] = {
            "is_anomaly": 1,
            "anomaly_type": anomaly_type,
        }
        metadata.update(overrides)
        action_sequence.append((action_obj, route_label, metadata))

    if anomaly_type == "skip_login":
        for _ in range(3):
            action_obj, route_label = choose_route_action(["banking", "personal_info"], banking_actions)
            add_action(action_obj, route_label)
        if logout_actions:
            add_action(rng.choice(logout_actions), "logout")

    elif anomaly_type == "impossible_seq":
        if login_actions:
            add_action(login_actions[0], "login")
        for _ in range(2):
            action_obj, route_label = choose_route_action(route_names, all_actions)
            add_action(action_obj, route_label)
        if logout_actions:
            add_action(rng.choice(logout_actions), "logout")
        for _ in range(2):
            action_obj, route_label = choose_route_action(
                ["banking", "documents", "refunds"], all_actions
            )
            add_action(action_obj, route_label)

    elif anomaly_type == "repeated_fail":
        if login_actions:
            add_action(login_actions[0], "login")
        for _ in range(4):
            action_obj = rng.choice(banking_actions)
            add_action(action_obj, infer_route_for_action(action_obj.value), force_status="KO")
        if logout_actions:
            add_action(rng.choice(logout_actions), "logout")

    elif anomaly_type == "ping_pong_loop":
        if login_actions:
            add_action(login_actions[0], "login")
        left_action, left_route = choose_route_action(["contact_general", "claims"], contact_actions)
        right_action, right_route = choose_route_action(["refunds", "documents", "tp_card"], download_actions)
        for index in range(rng.randint(6, 10)):
            action_obj, route_label = (left_action, left_route) if index % 2 == 0 else (right_action, right_route)
            add_action(action_obj, route_label, gap_seconds=rng.randint(4, 15))
        if logout_actions:
            add_action(rng.choice(logout_actions), "logout")

    elif anomaly_type == "data_exfiltration":
        if login_actions:
            add_action(login_actions[0], "login")
        burst_pool = download_actions[:]
        rng.shuffle(burst_pool)
        burst_pool = burst_pool[: max(2, min(4, len(burst_pool)))] or download_actions
        for _ in range(rng.randint(12, 18)):
            action_obj = rng.choice(burst_pool)
            add_action(
                action_obj,
                infer_route_for_action(action_obj.value),
                gap_seconds=rng.randint(3, 10),
            )
        if logout_actions:
            add_action(rng.choice(logout_actions), "logout")

    elif anomaly_type == "impossible_device_switch":
        if login_actions:
            add_action(login_actions[0], "login")
        switch_device, switch_user_agent = pick_alternate_device(user.device, rng)
        switch_ip = random_public_ip(user.home_ip_prefix, rng)
        planned_routes = [rng.choice(route_names) for _ in range(rng.randint(3, 4))]
        switch_point = max(1, len(planned_routes) // 2)
        for index, route_name in enumerate(planned_routes, start=1):
            action_obj, route_label = choose_route_action([route_name], all_actions)
            overrides: Dict[str, object] = {}
            if index > switch_point:
                overrides.update(
                    device_override=switch_device,
                    user_agent_override=switch_user_agent,
                    ip_override=switch_ip,
                )
            add_action(action_obj, route_label, **overrides)
        if logout_actions:
            add_action(
                rng.choice(logout_actions),
                "logout",
                device_override=switch_device,
                user_agent_override=switch_user_agent,
                ip_override=switch_ip,
            )

    elif anomaly_type == "distributed_brute_force":
        attack_campaign = campaign or build_attack_campaign(month_start, month_end, rng)
        start_time = attack_campaign.start_time + timedelta(seconds=rng.randint(0, 240))
        login_action = catalog.get("Connexion")
        if login_action is None:
            login_action = (login_actions or primary_logins or all_actions)[0]
        for _ in range(rng.randint(2, 4)):
            add_action(
                login_action,
                "login",
                force_status="KO",
                ip_override=attack_campaign.shared_ip,
                country_code_override=attack_campaign.country_code,
                city_override=attack_campaign.city,
                campaign_id=attack_campaign.campaign_id,
                gap_seconds=rng.randint(10, 45),
            )
        attack_campaign.participants.add(user.insured_id)

    elif anomaly_type == "zombie_session":
        if login_actions:
            add_action(login_actions[0], "login")
        zombie_route = rng.choice(["documents", "refunds", "tp_card"])
        zombie_action, zombie_route = choose_route_action([zombie_route], download_actions)
        gap_plan = [rng.randint(4 * 3600, 6 * 3600), rng.randint(5 * 3600, 7 * 3600), rng.randint(4 * 3600, 6 * 3600)]
        for gap_seconds in gap_plan:
            add_action(zombie_action, zombie_route, gap_seconds=gap_seconds)
        add_action(zombie_action, zombie_route, gap_seconds=rng.randint(900, 1800))

    else:
        foreign_ip = None
        foreign_country = None
        foreign_city = None
        if anomaly_type == "geo_jump":
            foreign_country, foreign_city, foreign_prefix = random_geo_profile(rng, exclude_country=user.country_code)
            foreign_ip = random_public_ip(foreign_prefix, rng)

        for action_obj in login_actions:
            add_action(action_obj, "login")
        planned_routes = [rng.choice(route_names) for _ in range(rng.randint(1, 3))]
        for index, route_name in enumerate(planned_routes, start=1):
            action_obj, route_label = choose_route_action([route_name], all_actions)
            overrides: Dict[str, object] = {}
            if foreign_ip and index >= math.ceil(len(planned_routes) / 2):
                overrides.update(
                    ip_override=foreign_ip,
                    country_code_override=foreign_country,
                    city_override=foreign_city,
                )
            add_action(action_obj, route_label, **overrides)
        for action_obj in logout_actions:
            add_action(
                action_obj,
                "logout",
                ip_override=foreign_ip,
                country_code_override=foreign_country,
                city_override=foreign_city,
            )

    return build_plans_from_action_sequence(
        action_sequence, start_time, month_end, rng, session_number, rapid=rapid
    )

# ─── Event record builder ─────────────────────────────────────────────────────

def choose_status(
    action: ActionDef,
    is_anomaly: bool,
    force_status: Optional[str],
    rng: random.Random,
) -> str:
    if force_status:
        return force_status
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

def build_request_data(
    action: ActionDef,
    user: UserProfile,
    rng: random.Random,
    device: Optional[str] = None,
    country_code: Optional[str] = None,
) -> str:
    t = action.type_name
    effective_device = device or user.device
    effective_country = country_code or user.country_code
    if "LOGGING" in t:
        return json.dumps({
            "login": user.insured_id,
            "channel": "mobile" if "MOBILE" in effective_device else "web",
            "country": effective_country,
        })
    if "BANKING" in t:
        return json.dumps({
            "iban_last4": str(rng.randint(1000, 9999)),
            "bic": rng.choice(["AGRIFRPP", "BNPAFRPP", "SOGEFRPP"]),
            "country": effective_country,
        })
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
                       created_at_format: str, rng: random.Random,
                       clear_session_after: bool = False) -> Dict:
    action = plan.action
    if plan.sequence_in_session == 1 or user.session_id is None:
        user.session_id = f"{rng.randint(100000, 9999999)}-{user.insured_id[-4:]}-{plan.session_number}"
        if not plan.ip_override and user.device.startswith("MOBILE") and rng.random() < 0.25:
            user.current_ip = random_public_ip(user.home_ip_prefix, rng)

    if plan.ip_override:
        ip = plan.ip_override
    else:
        ip = user.current_ip

    device = plan.device_override or user.device
    user_agent = plan.user_agent_override or user.user_agent
    country_code = plan.country_code_override or user.country_code
    city = plan.city_override or user.city

    status  = choose_status(action, bool(plan.is_anomaly), plan.force_status, rng)
    http    = choose_http_code(status, action, rng)
    req_d   = build_request_data(action, user, rng, device=device, country_code=country_code)
    req_r   = build_request_return(status, action, rng)

    event: Dict = {
        "id":             str(uuid.uuid4()),
        "insuredId":      user.insured_id,
        "status":         status,
        "sessionId":      user.session_id,
        "action":         action.value,
        "httpCode":       http,
        "ip":             ip,
        "userAgent":      user_agent,
        "requestData":    req_d,
        "requestReturn":  req_r,
        "createdAt":      format_ts(plan.timestamp.astimezone(timezone.utc), created_at_format),
        "type":           action.type_name,
        "environmentId":  str(user.environment_id),
        "device":         device,
        "persona":        user.persona,
        "route":          plan.route_label,
        "companyIdList":          gen_id_list(user.company_id, rng, 0.10),
        "companyGroupIdList":     gen_id_list(user.company_group_id, rng, 0.08),
        "insurerIdList":          gen_id_list(user.insurer_id, rng, 0.10),
        "companySectionIdList":   gen_id_list(user.company_section_id, rng, 0.10),
        "insurerCodeIdList":      gen_id_list(user.insurer_code_id, rng, 0.10),
        "healthcareNetworkIdList":gen_id_list(user.healthcare_network_id, rng, 0.15),
        "domainIdList":           gen_id_list(user.domain_id, rng, 0.10),
        "subType":        action.sub_type or None,
        "countryCode":    country_code,
        "city":           city,
        "month":          plan.timestamp.strftime("%Y-%m"),
        "sessionNumber":  plan.session_number,
        "sequenceInSession": plan.sequence_in_session,
        "sessionLength":  session_length,
        "is_anomaly":     plan.is_anomaly,
        "anomaly_type":   plan.anomaly_type or "normal",
        "campaignId":     plan.campaign_id or None,
        "_timestamp":     plan.timestamp.astimezone(timezone.utc),
    }

    # Let the generator decide logical session boundaries. This keeps
    # impossible_seq sessions under one stable sessionId even when a logout
    # appears before the final planned event.
    if clear_session_after:
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


def serialize_session_summary(summary: Dict) -> Dict:
    out = dict(summary)
    for key in ["anomaly_types", "campaignIds"]:
        if key in out:
            out[key] = json.dumps(out[key], ensure_ascii=False)
    return out


def enrich_events_and_build_summaries(
    events: List[Dict],
    created_at_format: str,
) -> Tuple[List[Dict], List[Dict]]:
    grouped_events: Dict[str, List[Dict]] = defaultdict(list)
    for event in events:
        grouped_events[event["sessionId"]].append(event)

    login_values = primary_login_actions()
    logout_values = all_logout_actions()
    summaries: List[Dict] = []

    for session_events in grouped_events.values():
        session_events.sort(key=lambda item: (item["_timestamp"], item.get("sequenceInSession", 0)))
        start_ts = session_events[0]["_timestamp"]
        end_ts = session_events[-1]["_timestamp"]
        total_duration_seconds = int(max(0, (end_ts - start_ts).total_seconds()))
        first_ip = session_events[0]["ip"]
        first_device = session_events[0]["device"]

        seen_ips: Set[str] = set()
        seen_devices: Set[str] = set()
        cumulative_kos = 0
        current_ko_streak = 0
        longest_ko_streak = 0
        has_logged_in = 0
        download_count = 0
        download_window: deque = deque()
        ping_pong_count = 0
        risk_scores: List[int] = []

        for index, event in enumerate(session_events):
            ts = event["_timestamp"]
            prev_event = session_events[index - 1] if index > 0 else None
            next_event = session_events[index + 1] if index + 1 < len(session_events) else None
            time_delta = int((ts - prev_event["_timestamp"]).total_seconds()) if prev_event else 0

            seen_ips.add(event["ip"])
            seen_devices.add(event["device"])

            if event["status"] == "KO":
                cumulative_kos += 1
                current_ko_streak += 1
            else:
                current_ko_streak = 0
            longest_ko_streak = max(longest_ko_streak, current_ko_streak)

            while download_window and (ts - download_window[0]).total_seconds() > 120:
                download_window.popleft()

            is_download = int(is_download_action(event["action"]))
            if is_download:
                download_count += 1
                download_window.append(ts)
            downloads_last_2_minutes = len(download_window)

            if (
                index >= 2
                and event["action"] == session_events[index - 2]["action"]
                and event["action"] != session_events[index - 1]["action"]
            ):
                ping_pong_count += 1

            event.update({
                "prevAction": prev_event["action"] if prev_event else "",
                "nextAction": next_event["action"] if next_event else "",
                "sessionDurationSeconds": total_duration_seconds,
                "timeDeltaSinceLastAction": time_delta,
                "hourOfDay": ts.hour,
                "dayOfWeek": ts.weekday(),
                "isWeekend": int(ts.weekday() >= 5),
                "isIpChanged": int(event["ip"] != first_ip),
                "uniqueIpsInSession": len(seen_ips),
                "cumulativeKOs": cumulative_kos,
                "longestKoStreak": longest_ko_streak,
                "hasLoggedIn": int(has_logged_in),
                "isDeviceChanged": int(event["device"] != first_device),
                "uniqueDevicesInSession": len(seen_devices),
                "isDownloadAction": is_download,
                "downloadActionsInSession": download_count,
                "downloadsLast2Minutes": downloads_last_2_minutes,
                "pingPongCount": ping_pong_count,
            })

            risk_score = 0
            if event["isIpChanged"]:
                risk_score += 30
            if event["status"] == "KO":
                risk_score += 10
            if event["isDeviceChanged"]:
                risk_score += 25
            if 2 <= event["hourOfDay"] <= 4:
                risk_score += 15
            if time_delta and time_delta <= 7:
                risk_score += 15
            if cumulative_kos >= 3:
                risk_score += 15
            if downloads_last_2_minutes >= 10:
                risk_score += 20
            if ping_pong_count >= 2:
                risk_score += 15
            if event.get("anomaly_type") in {
                "geo_jump",
                "data_exfiltration",
                "impossible_device_switch",
                "distributed_brute_force",
                "zombie_session",
            }:
                risk_score += 10
            if event.get("anomaly_type") == "skip_login" and not has_logged_in:
                risk_score += 15

            event["sessionRiskScore"] = min(100, risk_score)
            risk_scores.append(event["sessionRiskScore"])

            if event["action"] in login_values:
                has_logged_in = 1

        inter_action_seconds = [event["timeDeltaSinceLastAction"] for event in session_events[1:]]
        anomaly_types = [
            event["anomaly_type"]
            for event in session_events
            if event.get("anomaly_type") and event["anomaly_type"] != "normal"
        ]
        anomaly_type_counts: Dict[str, int] = defaultdict(int)
        for anomaly_type in anomaly_types:
            anomaly_type_counts[anomaly_type] += 1

        primary_anomaly_type = (
            max(anomaly_type_counts.items(), key=lambda item: item[1])[0]
            if anomaly_type_counts else "normal"
        )
        campaign_ids = sorted({
            event["campaignId"] for event in session_events if event.get("campaignId")
        })
        action_sequence = [event["action"] for event in session_events]
        route_sequence = [event["route"] for event in session_events if event.get("route")]

        summaries.append({
            "sessionId": session_events[0]["sessionId"],
            "insuredId": session_events[0]["insuredId"],
            "persona": session_events[0].get("persona"),
            "countryCode": session_events[0]["countryCode"],
            "city": session_events[0]["city"],
            "month": session_events[0]["month"],
            "sessionNumber": session_events[0]["sessionNumber"],
            "sessionStart": format_ts(start_ts, created_at_format),
            "sessionEnd": format_ts(end_ts, created_at_format),
            "startHour": start_ts.hour,
            "endHour": end_ts.hour,
            "dayOfWeek": start_ts.weekday(),
            "isWeekend": int(start_ts.weekday() >= 5),
            "firstAction": action_sequence[0],
            "lastAction": action_sequence[-1],
            "firstRoute": session_events[0].get("route", ""),
            "lastRoute": session_events[-1].get("route", ""),
            "totalEvents": len(session_events),
            "totalDurationSeconds": total_duration_seconds,
            "avgInterActionSeconds": round(sum(inter_action_seconds) / len(inter_action_seconds), 2) if inter_action_seconds else 0,
            "minInterActionSeconds": min(inter_action_seconds) if inter_action_seconds else 0,
            "maxInterActionSeconds": max(inter_action_seconds) if inter_action_seconds else 0,
            "uniqueActions": len(set(action_sequence)),
            "uniqueRoutes": len(set(route_sequence)),
            "uniqueIpsUsed": len({event["ip"] for event in session_events}),
            "uniqueDevicesUsed": len({event["device"] for event in session_events}),
            "totalKOs": sum(1 for event in session_events if event["status"] == "KO"),
            "totalOKs": sum(1 for event in session_events if event["status"] == "OK"),
            "longestKoStreak": max(event["longestKoStreak"] for event in session_events),
            "hasLogin": int(any(event["action"] in login_values for event in session_events)),
            "hasLogout": int(any(event["action"] in logout_values for event in session_events)),
            "ipChanged": int(any(event["isIpChanged"] for event in session_events)),
            "deviceChanged": int(any(event["isDeviceChanged"] for event in session_events)),
            "totalDownloadActions": sum(event["isDownloadAction"] for event in session_events),
            "maxDownloadsIn2Minutes": max(event["downloadsLast2Minutes"] for event in session_events),
            "pingPongCount": max(event["pingPongCount"] for event in session_events),
            "riskScoreMax": max(risk_scores) if risk_scores else 0,
            "riskScoreAvg": round(sum(risk_scores) / len(risk_scores), 2) if risk_scores else 0,
            "endedAbruptly": int(not any(event["action"] in logout_values for event in session_events)),
            "is_anomaly": int(any(event.get("is_anomaly") for event in session_events)),
            "anomaly_event_count": sum(1 for event in session_events if event.get("is_anomaly")),
            "primary_anomaly_type": primary_anomaly_type,
            "anomaly_types": sorted(set(anomaly_types)),
            "campaignIds": campaign_ids,
            "actionSequenceSignature": " > ".join(action_sequence),
            "routeSequenceSignature": " > ".join(route_sequence),
        })

    summaries.sort(key=lambda item: (item["sessionStart"], item["sessionId"]))
    return events, summaries

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
    p.add_argument(
        "--actions-order",
        default=str(Path(__file__).with_name("actions_order-v2.json")),
        help="path to actions_order-v2.json",
    )
    p.add_argument("--output",          default="audit_trail_2025.csv")
    p.add_argument(
        "--session-summary-output",
        default=None,
        help="optional path for session-level summary CSV",
    )
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
    nav_summary = initialize_navigation(args.actions_order, args.backend_apis, catalog)
    print(
        "Loaded navigation from "
        f"{args.actions_order} "
        f"({nav_summary['auth_steps_resolved']} auth steps, "
        f"{nav_summary['json_routes_with_actions']} JSON routes with tracked actions)"
    )

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
    session_summary_output = args.session_summary_output or default_session_summary_path(args.output)

    for month_start, month_end in months:
        month_events: List[Tuple[datetime, Dict]] = []
        attack_campaigns: List[AttackCampaign] = []
        for user in users:
            lengths = choose_session_lengths(apu_month, rng)
            for _ in lengths:
                user.sessions_generated += 1
                snum = user.sessions_generated
                if rng.random() < args.anomaly_rate:
                    anomaly_type = rng.choice(ANOMALY_TYPES)
                    campaign = None
                    if anomaly_type == "distributed_brute_force":
                        campaign = pick_or_create_attack_campaign(
                            attack_campaigns, user, month_start, month_end, rng
                        )
                    plans = build_anomaly_session(
                        user,
                        month_start,
                        month_end,
                        catalog,
                        rng,
                        snum,
                        anomaly_type=anomaly_type,
                        campaign=campaign,
                    )
                else:
                    plans = build_normal_session(user, month_start, month_end, catalog, rng, snum)
                # Compute actual session length
                true_length = len(plans)
                for idx, plan in enumerate(plans):
                    event = build_event_record(
                        plan,
                        user,
                        true_length,
                        args.created_at_format,
                        rng,
                        clear_session_after=(idx == true_length - 1),
                    )
                    month_events.append((plan.timestamp, event))

        month_events.sort(key=lambda x: x[0])
        all_events.extend(ev for _, ev in month_events)

    all_events, session_summaries = enrich_events_and_build_summaries(
        all_events, args.created_at_format
    )

    # Write event CSV
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for ev in all_events:
            row = serialize_for_csv(ev)
            writer.writerow({c: row.get(c, "") for c in CSV_COLUMNS})

    session_summary_path = Path(session_summary_output)
    session_summary_path.parent.mkdir(parents=True, exist_ok=True)
    with session_summary_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=SESSION_SUMMARY_COLUMNS)
        writer.writeheader()
        for summary in session_summaries:
            row = serialize_session_summary(summary)
            writer.writerow({c: row.get(c, "") for c in SESSION_SUMMARY_COLUMNS})

    normal  = sum(1 for e in all_events if not e.get("is_anomaly"))
    anomaly = sum(1 for e in all_events if e.get("is_anomaly"))
    by_type: Dict[str, int] = defaultdict(int)
    for e in all_events:
        if e.get("is_anomaly") and e.get("anomaly_type") and e["anomaly_type"] != "normal":
            by_type[e["anomaly_type"]] += 1

    print(json.dumps({
        "total_events":   len(all_events),
        "normal_events":  normal,
        "anomaly_events": anomaly,
        "anomaly_pct":    round(100 * anomaly / max(1, len(all_events)), 2),
        "anomaly_by_type": dict(by_type),
        "total_sessions": len(session_summaries),
        "session_summary_output": str(session_summary_path),
        "unique_actions": len(catalog),
        "users":          args.users,
        "months":         len(months),
    }, ensure_ascii=False, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
