#!/usr/bin/env python3
"""
Updated Kafka audit-trail simulator aligned with the Spring Boot AuditTrailEvent DTO,
AuditTrailConsumer logic, and ModelInferenceService heuristics.

Highlights
- Emits all event-level columns present in the provided dataset-1 snippet.
- Produces JSON payloads directly consumable by the Spring @KafkaListener.
- Computes running/cumulative session features used by rules + inference:
  route, prevAction, nextAction, sequenceInSession, KO streaks, download windows,
  ping-pong loops, device/IP changes, risk score, anomaly flags, etc.
- Can emit normal-only traffic or mixed traffic with injected anomaly sessions.
- Standalone: does not depend on simulator_yearly_dataset_v3 or external JSON catalogs.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
import uuid
from collections import Counter, deque
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Deque, Dict, List, Optional, Sequence, Tuple

try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

# ---------------------------------------------------------------------------
# Catalog aligned with provided dataset and Spring Boot DTO fields
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ActionSpec:
    action: str
    type_name: str
    sub_type: str
    route: str
    is_download: bool = False


LOGIN_ACTIONS: List[ActionSpec] = [
    ActionSpec("Connexion", "LOGGING_ACTIONS", "logging_login", "login"),
    ActionSpec("Connexion SSO", "LOGGING_ACTIONS", "logging_login_sso", "login"),
    ActionSpec("Connexion en tant que", "LOGGING_ACTIONS", "logging_login_as_insured", "login"),
]

LOGOUT_ACTIONS: Dict[str, ActionSpec] = {
    "Connexion": ActionSpec("Déconnexion", "LOGGING_ACTIONS", "logging_logout", "logout"),
    "Connexion SSO": ActionSpec("SSO Disconnect", "LOGGING_ACTIONS", "logging_logout_sso", "logout"),
    "Connexion en tant que": ActionSpec("SSO Disconnect", "LOGGING_ACTIONS", "logging_logout_sso", "logout"),
}

ROUTE_ACTIONS: Dict[str, List[ActionSpec]] = {
    "beneficiaries": [
        ActionSpec("Ajouter un bénéficiaire", "INSURED_ACTIONS", "add_beneficiary", "beneficiaries"),
        ActionSpec("Supprimer un bénéficiaire", "INSURED_ACTIONS", "remove_beneficiary", "beneficiaries"),
        ActionSpec("Changer l'organisme de rattachement sécu. Social", "OPEN_ACTIONS", "change_social_security_provider", "beneficiaries"),
    ],
    "refunds": [
        ActionSpec("Télécharger un décompte", "INSURED_ACTIONS", "download_refund_statement", "refunds", is_download=True),
        ActionSpec("Exporter remboursement", "INSURED_ACTIONS", "export_refund", "refunds", is_download=True),
        ActionSpec("Télécharger le certificat d'adhésion", "INSURED_ACTIONS", "download_membership_certificate", "refunds", is_download=True),
    ],
    "tp_card": [
        ActionSpec("Téléchargement carte TP", "INSURED_ACTIONS", "download_third_party_card", "tp_card", is_download=True),
        ActionSpec("Téléchargement de la carte de tiers payant", "INSURED_ACTIONS", "download_third_party_card", "tp_card", is_download=True),
        ActionSpec("Ajout de la carte tiers payant dans le wallet", "INSURED_ACTIONS", "add_tp_card_wallet", "tp_card", is_download=False),
    ],
    "documents": [
        ActionSpec("Envoi d'un document", "DOCUMENT_ACTIONS", "send_document", "documents"),
        ActionSpec("Ouverture d'un document contractuel", "INSURED_ACTIONS", "open_contract_document", "documents"),
        ActionSpec("demander un renvoi par mail d'échéancier", "CONTACT_ACTIONS", "request_payment_schedule_by_email", "documents"),
    ],
    "requests": [
        ActionSpec("Envoi d'un message", "CONTACT_ACTIONS", "contact_send_message", "requests"),
        ActionSpec("Envoi d'une réclamation", "CONTACT_ACTIONS", "contact_submit_complaint", "requests"),
        ActionSpec("Contactez nous : via message (Mes remboursements)", "CONTACT_ACTIONS", "contact_message_my_refunds", "requests"),
    ],
    "personal_info": [
        ActionSpec("Changer ses informations personnel", "OPEN_ACTIONS", "update_personal_information", "personal_info"),
        ActionSpec("Changement de Mot de passe", "LOGGING_ACTIONS", "logging_password_change", "personal_info"),
    ],
    "banking_info": [
        ActionSpec("Changer les coordonnées bancaire", "BANKING_ACTIONS", "update_bank_details", "banking_info"),
        ActionSpec("Signature électronique d'un mandat sepa", "INSURED_ACTIONS", "sign_sepa_mandate", "banking_info"),
        ActionSpec("Changer les coordonnées bancaire, changement de RIB pour les remboursements", "BANKING_ACTIONS", "update_bank_details_refunds", "banking_info"),
    ],
    "preferences": [
        ActionSpec("Envoi carte TP par mail", "OPEN_ACTIONS", "send_third_party_card_by_email", "preferences"),
        ActionSpec("Renvoi carte TP papier", "INSURED_ACTIONS", "resend_tp_card_paper", "preferences"),
        ActionSpec("envoi d'une demande de résiliation", "CONTACT_ACTIONS", "request_cancellation_by_message", "preferences"),
    ],
}

ROUTE_WEIGHTS: List[Tuple[str, float]] = [
    ("refunds", 0.22),
    ("tp_card", 0.18),
    ("beneficiaries", 0.14),
    ("documents", 0.14),
    ("requests", 0.12),
    ("personal_info", 0.08),
    ("banking_info", 0.07),
    ("preferences", 0.05),
]

PERSONAS = ["self_service", "frequent_checker", "admin_delegated"]
ENVIRONMENTS = ["10", "11"]
COMPANY_IDS = [27011, 27012, 27013, 27021]
COMPANY_GROUP_IDS = [12001, 12002, 12003]
INSURER_IDS = [1, 2, 3]
COMPANY_SECTION_IDS = [110027, 110028, 110029]
INSURER_CODE_IDS = [302, 303, 304]
HEALTHCARE_NETWORK_IDS = [1, 2, 3]
DOMAIN_IDS = [10, 11, 12]

GEO_PROFILE = {
    "FR": {
        "cities": ["Paris", "Lyon", "Lille", "Toulouse", "Nantes", "Bordeaux", "Strasbourg", "Marseille"],
        "prefixes": [("81", "64"), ("82", "64"), ("86", "192"), ("90",), ("2",)],
        "weight": 0.72,
    },
    "BE": {
        "cities": ["Brussels", "Antwerp", "Ghent", "Liège"],
        "prefixes": [("37", "60"), ("46", "18")],
        "weight": 0.08,
    },
    "DE": {
        "cities": ["Berlin", "Hamburg", "Munich", "Frankfurt"],
        "prefixes": [("46", "5"), ("87", "123")],
        "weight": 0.08,
    },
    "ES": {
        "cities": ["Madrid", "Barcelona", "Valencia"],
        "prefixes": [("80", "58"), ("81", "32")],
        "weight": 0.06,
    },
    "IT": {
        "cities": ["Milan", "Rome", "Turin"],
        "prefixes": [("79", "0"), ("82", "48")],
        "weight": 0.06,
    },
}

DEVICE_POOL = [
    ("WEB", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123.0.0.0 Safari/537.36"),
    ("WEB", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 Version/17.4 Safari/605.1.15"),
    ("MOBILE_ANDROID", "mobileapp/4.2.1 (android; sdk=34; locale=fr-FR)"),
    ("MOBILE_ANDROID", "mobileapp/4.1.7 (android; sdk=33; locale=fr-FR)"),
    ("MOBILE_IOS", "mobileapp/4.3.0 (ios; build=812; locale=fr-FR)"),
]

ANOMALY_TYPES = [
    "normal",
    "repeated_fail",
    "geo_jump",
    "impossible_device_switch",
    "data_exfiltration",
    "ping_pong_loop",
    "skip_login",
    "unusual_hour",
    "impossible_seq",
    "zombie_session",
]

# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------

@dataclass
class UserProfile:
    insured_id: str
    persona: str
    country_code: str
    city: str
    device: str
    user_agent: str
    ip: str
    company_id: int
    company_group_id: int
    insurer_id: int
    company_section_id: int
    insurer_code_id: int
    healthcare_network_id: int
    domain_id: int
    environment_id: str
    session_counter: int = 0


@dataclass
class EventPlan:
    action: ActionSpec
    created_at: datetime
    force_status: Optional[str] = None
    force_http_code: Optional[int] = None
    force_ip: Optional[str] = None
    force_device: Optional[str] = None
    force_user_agent: Optional[str] = None
    campaign_id: Optional[str] = None


# ---------------------------------------------------------------------------
# Utility helpers
# ---------------------------------------------------------------------------

def weighted_choice(items: Sequence[Tuple[object, float]], rng: random.Random):
    total = sum(weight for _, weight in items)
    pick = rng.random() * total
    upto = 0.0
    for item, weight in items:
        upto += weight
        if upto >= pick:
            return item
    return items[-1][0]


def random_public_ip(prefix: Sequence[str], rng: random.Random) -> str:
    parts = [int(x) for x in prefix]
    while len(parts) < 4:
        parts.append(rng.randint(2, 250) if len(parts) == 3 else rng.randint(0, 255))
    if parts[0] in {10, 127, 169, 172, 192}:
        parts[0] = rng.choice([2, 37, 46, 62, 77, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 93, 95])
    return ".".join(str(p) for p in parts[:4])


def iso_z(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_iso_dt(value: str) -> datetime:
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def choose_status(route: str, rng: random.Random) -> str:
    failure_rates = {
        "login": 0.04,
        "logout": 0.01,
        "beneficiaries": 0.05,
        "refunds": 0.03,
        "tp_card": 0.02,
        "documents": 0.05,
        "requests": 0.06,
        "personal_info": 0.03,
        "banking_info": 0.08,
        "preferences": 0.04,
    }
    return "KO" if rng.random() < failure_rates.get(route, 0.04) else "OK"


def choose_http_code(status: str, route: str, action: str, rng: random.Random) -> int:
    if status == "OK":
        if action in {"Connexion", "Connexion SSO", "Connexion en tant que"}:
            return rng.choice([200, 204])
        if route in {"documents", "requests", "banking_info", "beneficiaries"}:
            return rng.choice([200, 201, 202])
        return rng.choice([200, 204])
    failure_codes = {
        "login": [401, 403],
        "logout": [401],
        "banking_info": [400, 409, 422],
        "documents": [400, 422],
        "requests": [400, 422],
        "refunds": [400, 403, 422],
    }
    return rng.choice(failure_codes.get(route, [400, 403, 409, 422, 500]))


def build_request_data(action: ActionSpec, user: UserProfile, rng: random.Random) -> Dict:
    if action.route == "login":
        return {
            "login": user.insured_id,
            "channel": "mobile" if user.device.startswith("MOBILE") else "web",
            "country": user.country_code,
        }
    if action.route == "logout":
        return {
            "login": user.insured_id,
            "channel": "mobile" if user.device.startswith("MOBILE") else "web",
            "country": user.country_code,
        }
    if action.route == "banking_info":
        return {
            "insuredId": user.insured_id,
            "iban_last4": str(rng.randint(1000, 9999)),
            "bic": rng.choice(["AGRIFRPP", "BNPAFRPP", "SOGEFRPP", "CMCIFRPP"]),
        }
    if action.route == "requests":
        return {
            "insuredId": user.insured_id,
            "subject": action.action,
            "priority": rng.choice(["low", "normal", "normal", "high"]),
        }
    if action.route in {"documents", "refunds", "tp_card"}:
        return {
            "insuredId": user.insured_id,
            "action": action.action,
            "documentType": rng.choice(["pdf", "image", "csv"]),
        }
    return {
        "insuredId": user.insured_id,
        "action": action.action,
    }


def build_request_return(status: str, http_code: int, rng: random.Random) -> Dict:
    if status == "OK":
        return {"status": "success", "reference": f"REF-{rng.randint(100000, 999999)}"}
    code = "VALIDATION_ERROR" if http_code in {400, 409, 422} else "ACCESS_DENIED"
    if http_code >= 500:
        code = "SERVER_ERROR"
    return {"status": "error", "code": code}


def is_login_action(action: str) -> bool:
    return action in {a.action for a in LOGIN_ACTIONS}


def is_logout_action(action: str) -> bool:
    return action in {a.action for a in LOGOUT_ACTIONS.values()}


def compute_ping_pong(actions: Sequence[str]) -> int:
    # count A-B-A-B windows (conservative and simple)
    count = 0
    for i in range(3, len(actions)):
        if actions[i] == actions[i - 2] and actions[i - 1] == actions[i - 3] and actions[i] != actions[i - 1]:
            count += 1
    return count


def compute_risk_score(
    ip_changed: int,
    device_changed: int,
    cumulative_kos: int,
    downloads_last_2m: int,
    ping_pong_count: int,
    unusual_hour: bool,
    skip_login: bool,
) -> float:
    score = 0.0
    score += 30.0 * ip_changed
    score += 25.0 * device_changed
    score += 15.0 if cumulative_kos >= 3 else 0.0
    score += 15.0 if downloads_last_2m >= 10 else 0.0
    score += 15.0 if ping_pong_count >= 2 else 0.0
    score += 10.0 if unusual_hour else 0.0
    score += 10.0 if skip_login else 0.0
    return max(0.0, min(100.0, score))


def build_users(rng: random.Random, count: int) -> List[UserProfile]:
    geo_items = [(country, data["weight"]) for country, data in GEO_PROFILE.items()]
    users: List[UserProfile] = []
    for _ in range(count):
        country_code = weighted_choice(geo_items, rng)
        geo = GEO_PROFILE[country_code]
        device, ua = rng.choice(DEVICE_POOL)
        prefix = rng.choice(geo["prefixes"])
        users.append(
            UserProfile(
                insured_id=str(rng.randint(10000000, 99999999)),
                persona=rng.choice(PERSONAS),
                country_code=country_code,
                city=rng.choice(geo["cities"]),
                device=device,
                user_agent=ua,
                ip=random_public_ip(prefix, rng),
                company_id=rng.choice(COMPANY_IDS),
                company_group_id=rng.choice(COMPANY_GROUP_IDS),
                insurer_id=rng.choice(INSURER_IDS),
                company_section_id=rng.choice(COMPANY_SECTION_IDS),
                insurer_code_id=rng.choice(INSURER_CODE_IDS),
                healthcare_network_id=rng.choice(HEALTHCARE_NETWORK_IDS),
                domain_id=rng.choice(DOMAIN_IDS),
                environment_id=rng.choice(ENVIRONMENTS),
            )
        )
    return users


# ---------------------------------------------------------------------------
# Session planning
# ---------------------------------------------------------------------------

def choose_business_actions(rng: random.Random, count: int) -> List[ActionSpec]:
    steps: List[ActionSpec] = []
    for _ in range(count):
        route = weighted_choice(ROUTE_WEIGHTS, rng)
        steps.append(rng.choice(ROUTE_ACTIONS[route]))
    return steps


def build_normal_session(user: UserProfile, session_start: datetime, rng: random.Random) -> List[EventPlan]:
    login = rng.choice(LOGIN_ACTIONS)
    logout = LOGOUT_ACTIONS[login.action]
    middle_count = rng.randint(0, 4)
    middle_actions = choose_business_actions(rng, middle_count)

    plans: List[EventPlan] = []
    current = session_start
    plans.append(EventPlan(login, current))
    for action in middle_actions:
        current += timedelta(seconds=rng.randint(8, 180))
        plans.append(EventPlan(action, current))
    current += timedelta(seconds=rng.randint(8, 180))
    plans.append(EventPlan(logout, current))
    return plans


def build_anomaly_session(user: UserProfile, session_start: datetime, rng: random.Random, anomaly_type: str) -> List[EventPlan]:
    anomaly_type = anomaly_type or "repeated_fail"
    campaign_id = f"cmp-{anomaly_type}-{rng.randint(100, 999)}"

    if anomaly_type == "skip_login":
        actions = choose_business_actions(rng, rng.randint(2, 4))
        plans: List[EventPlan] = []
        current = session_start
        for idx, action in enumerate(actions):
            if idx:
                current += timedelta(seconds=rng.randint(5, 45))
            plans.append(EventPlan(action, current, campaign_id=campaign_id))
        current += timedelta(seconds=rng.randint(5, 40))
        plans.append(EventPlan(rng.choice(list(LOGOUT_ACTIONS.values())), current, campaign_id=campaign_id))
        return plans

    if anomaly_type == "unusual_hour":
        weird = session_start.replace(hour=rng.choice([1, 2, 3, 4]), minute=rng.randint(0, 59), second=rng.randint(0, 59))
        return [
            EventPlan(LOGIN_ACTIONS[0], weird, campaign_id=campaign_id),
            EventPlan(rng.choice(ROUTE_ACTIONS["refunds"]), weird + timedelta(seconds=20), campaign_id=campaign_id),
            EventPlan(LOGOUT_ACTIONS["Connexion"], weird + timedelta(seconds=60), campaign_id=campaign_id),
        ]

    if anomaly_type == "repeated_fail":
        login = LOGIN_ACTIONS[0]
        bad_action = rng.choice(ROUTE_ACTIONS["banking_info"] + ROUTE_ACTIONS["requests"])
        current = session_start
        return [
            EventPlan(login, current, campaign_id=campaign_id),
            EventPlan(bad_action, current + timedelta(seconds=10), force_status="KO", force_http_code=422, campaign_id=campaign_id),
            EventPlan(bad_action, current + timedelta(seconds=18), force_status="KO", force_http_code=422, campaign_id=campaign_id),
            EventPlan(bad_action, current + timedelta(seconds=28), force_status="KO", force_http_code=409, campaign_id=campaign_id),
            EventPlan(LOGOUT_ACTIONS[login.action], current + timedelta(seconds=70), campaign_id=campaign_id),
        ]

    if anomaly_type == "geo_jump":
        login = LOGIN_ACTIONS[0]
        current = session_start
        other_country = rng.choice([c for c in GEO_PROFILE.keys() if c != user.country_code])
        other_prefix = rng.choice(GEO_PROFILE[other_country]["prefixes"])
        far_ip = random_public_ip(other_prefix, rng)
        return [
            EventPlan(login, current, campaign_id=campaign_id),
            EventPlan(rng.choice(ROUTE_ACTIONS["refunds"]), current + timedelta(seconds=2), force_ip=far_ip, campaign_id=campaign_id),
            EventPlan(LOGOUT_ACTIONS[login.action], current + timedelta(seconds=8), force_ip=far_ip, campaign_id=campaign_id),
        ]

    if anomaly_type == "impossible_device_switch":
        login = LOGIN_ACTIONS[0]
        current = session_start
        alt_device, alt_ua = rng.choice([d for d in DEVICE_POOL if d[0] != user.device])
        return [
            EventPlan(login, current, campaign_id=campaign_id),
            EventPlan(rng.choice(ROUTE_ACTIONS["documents"]), current + timedelta(seconds=5), force_device=alt_device, force_user_agent=alt_ua, campaign_id=campaign_id),
            EventPlan(LOGOUT_ACTIONS[login.action], current + timedelta(seconds=15), force_device=alt_device, force_user_agent=alt_ua, campaign_id=campaign_id),
        ]

    if anomaly_type == "data_exfiltration":
        login = LOGIN_ACTIONS[1]
        current = session_start
        plans: List[EventPlan] = [EventPlan(login, current, campaign_id=campaign_id)]
        for i in range(12):
            current += timedelta(seconds=8)
            plans.append(EventPlan(rng.choice(ROUTE_ACTIONS["refunds"] + ROUTE_ACTIONS["tp_card"]), current, campaign_id=campaign_id))
        current += timedelta(seconds=20)
        plans.append(EventPlan(LOGOUT_ACTIONS[login.action], current, campaign_id=campaign_id))
        return plans

    if anomaly_type == "ping_pong_loop":
        login = LOGIN_ACTIONS[0]
        a = rng.choice(ROUTE_ACTIONS["refunds"])
        b = rng.choice(ROUTE_ACTIONS["tp_card"])
        current = session_start
        plans = [EventPlan(login, current, campaign_id=campaign_id)]
        for step in [a, b, a, b, a, b]:
            current += timedelta(seconds=12)
            plans.append(EventPlan(step, current, campaign_id=campaign_id))
        current += timedelta(seconds=12)
        plans.append(EventPlan(LOGOUT_ACTIONS[login.action], current, campaign_id=campaign_id))
        return plans

    if anomaly_type == "impossible_seq":
        current = session_start
        return [
            EventPlan(LOGOUT_ACTIONS["Connexion"], current, campaign_id=campaign_id),
            EventPlan(LOGIN_ACTIONS[0], current + timedelta(seconds=5), campaign_id=campaign_id),
            EventPlan(rng.choice(ROUTE_ACTIONS["beneficiaries"]), current + timedelta(seconds=15), campaign_id=campaign_id),
            EventPlan(LOGIN_ACTIONS[0], current + timedelta(seconds=20), campaign_id=campaign_id),
            EventPlan(LOGOUT_ACTIONS["Connexion"], current + timedelta(seconds=40), campaign_id=campaign_id),
        ]

    if anomaly_type == "zombie_session":
        login = LOGIN_ACTIONS[0]
        current = session_start
        return [
            EventPlan(login, current, campaign_id=campaign_id),
            EventPlan(rng.choice(ROUTE_ACTIONS["documents"]), current + timedelta(seconds=100), campaign_id=campaign_id),
            EventPlan(rng.choice(ROUTE_ACTIONS["documents"]), current + timedelta(seconds=1300), campaign_id=campaign_id),
        ]

    # Fallback anomaly = repeated_fail
    return build_anomaly_session(user, session_start, rng, "repeated_fail")


# ---------------------------------------------------------------------------
# Event materialization
# ---------------------------------------------------------------------------

def materialize_session_events(
    user: UserProfile,
    plans: Sequence[EventPlan],
    session_id: str,
    session_number: int,
    anomaly_type: str,
    rng: random.Random,
) -> List[Dict]:
    if not plans:
        return []

    session_start = plans[0].created_at
    session_end = plans[-1].created_at
    session_duration_seconds = max(0, int((session_end - session_start).total_seconds()))
    session_length = len(plans)

    unique_ips: set = set()
    unique_devices: set = set()
    cumulative_kos = 0
    longest_ko_streak = 0
    current_ko_streak = 0
    has_logged_in = 0
    download_actions_in_session = 0
    download_times: Deque[datetime] = deque()
    actions_so_far: List[str] = []

    initial_ip = user.ip
    initial_device = user.device

    events: List[Dict] = []
    for idx, plan in enumerate(plans):
        action_spec = plan.action
        created_at = plan.created_at.astimezone(timezone.utc)
        prev_action = plans[idx - 1].action.action if idx > 0 else ""
        next_action = plans[idx + 1].action.action if idx < session_length - 1 else ""

        device = plan.force_device or user.device
        user_agent = plan.force_user_agent or user.user_agent
        ip = plan.force_ip or user.ip

        status = plan.force_status or choose_status(action_spec.route, rng)
        http_code = plan.force_http_code if plan.force_http_code is not None else choose_http_code(status, action_spec.route, action_spec.action, rng)

        unique_ips.add(ip)
        unique_devices.add(device)
        is_ip_changed = 1 if ip != initial_ip else 0
        is_device_changed = 1 if device != initial_device else 0

        if status == "KO":
            cumulative_kos += 1
            current_ko_streak += 1
            longest_ko_streak = max(longest_ko_streak, current_ko_streak)
        else:
            current_ko_streak = 0

        if is_login_action(action_spec.action):
            has_logged_in = 1

        is_download = 1 if action_spec.is_download else 0
        if is_download:
            download_actions_in_session += 1
            download_times.append(created_at)
        while download_times and (created_at - download_times[0]).total_seconds() > 120:
            download_times.popleft()
        downloads_last_2m = len(download_times)

        actions_so_far.append(action_spec.action)
        ping_pong_count = compute_ping_pong(actions_so_far)

        hour_of_day = created_at.hour
        day_of_week = created_at.weekday()   # matches sample style: 2025-01-01 => 2
        is_weekend = 1 if day_of_week >= 5 else 0
        skip_login = 1 if idx == 0 and not is_login_action(action_spec.action) else 0
        unusual_hour = hour_of_day in {1, 2, 3, 4}
        session_risk_score = compute_risk_score(
            is_ip_changed,
            is_device_changed,
            cumulative_kos,
            downloads_last_2m,
            ping_pong_count,
            unusual_hour,
            bool(skip_login),
        )

        if idx == 0:
            delta_since_last = 0
        else:
            delta_since_last = int((created_at - plans[idx - 1].created_at).total_seconds())

        event = {
            "id": str(uuid.uuid4()),
            "insuredId": user.insured_id,
            "status": status,
            "sessionId": session_id,
            "action": action_spec.action,
            "httpCode": http_code,
            "ip": ip,
            "userAgent": user_agent,
            "requestData": build_request_data(action_spec, user, rng),
            "requestReturn": build_request_return(status, http_code, rng),
            "createdAt": iso_z(created_at),
            "type": action_spec.type_name,
            "environmentId": user.environment_id,
            "device": device,
            "persona": user.persona,
            "route": action_spec.route,
            "prevAction": prev_action,
            "nextAction": next_action,
            "companyIdList": [user.company_id],
            "companyGroupIdList": [user.company_group_id],
            "insurerIdList": [user.insurer_id],
            "companySectionIdList": [user.company_section_id],
            "insurerCodeIdList": [user.insurer_code_id],
            "healthcareNetworkIdList": [user.healthcare_network_id],
            "domainIdList": [user.domain_id],
            "subType": action_spec.sub_type,
            "countryCode": user.country_code,
            "city": user.city,
            "month": created_at.strftime("%Y-%m"),
            "sessionNumber": session_number,
            "sequenceInSession": idx + 1,
            "sessionLength": session_length,
            "sessionDurationSeconds": session_duration_seconds,
            "timeDeltaSinceLastAction": delta_since_last,
            "hourOfDay": hour_of_day,
            "dayOfWeek": day_of_week,
            "isWeekend": is_weekend,
            "isIpChanged": is_ip_changed,
            "uniqueIpsInSession": len(unique_ips),
            "cumulativeKOs": cumulative_kos,
            "longestKoStreak": longest_ko_streak,
            "hasLoggedIn": has_logged_in,
            "isDeviceChanged": is_device_changed,
            "uniqueDevicesInSession": len(unique_devices),
            "isDownloadAction": is_download,
            "downloadActionsInSession": download_actions_in_session,
            "downloadsLast2Minutes": downloads_last_2m,
            "pingPongCount": ping_pong_count,
            "sessionRiskScore": round(session_risk_score, 2),
            "is_anomaly": 0 if anomaly_type == "normal" else 1,
            "anomaly_type": anomaly_type,
            "campaignId": plan.campaign_id or None,
        }
        events.append(event)

    return events


# ---------------------------------------------------------------------------
# Kafka / CLI
# ---------------------------------------------------------------------------

def build_producer(args) -> "KafkaProducer":
    if KafkaProducer is None:
        raise RuntimeError("kafka-python is required. Install with: pip install kafka-python")
    return KafkaProducer(
        bootstrap_servers=[server.strip() for server in args.bootstrap_servers.split(",")],
        value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
        key_serializer=lambda value: value.encode("utf-8") if value else None,
        linger_ms=args.linger_ms,
        acks=args.acks,
        request_timeout_ms=args.request_timeout_ms,
        max_block_ms=args.max_block_ms,
        retries=args.retries,
    )


def close_producer(producer) -> None:
    if producer is None:
        return
    try:
        producer.flush(timeout=10)
    finally:
        try:
            producer.close(timeout=10)
        except Exception:
            pass


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Spring-Boot-aligned audit trail Kafka simulator")
    parser.add_argument("--bootstrap-servers", default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    parser.add_argument("--topic", default=os.getenv("KAFKA_TOPIC", "topic-audit-trail"))
    parser.add_argument("--rate", type=float, default=4.0, help="approximate event send rate per second")
    parser.add_argument("--duration", type=int, default=0, help="seconds to run (0 = bounded by session-count)")
    parser.add_argument("--session-count", type=int, default=20, help="number of sessions to generate")
    parser.add_argument("--insured-count", type=int, default=12, help="size of synthetic insured pool")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true", help="print JSON events instead of producing to Kafka")
    parser.add_argument("--start-at", default="2025-01-01T08:00:00+00:00", help="ISO datetime for virtual start")
    parser.add_argument("--normal-ratio", type=float, default=1.0, help="0..1 share of normal sessions")
    parser.add_argument("--anomaly-rate", type=float, default=0.0, help="0..1 chance to force anomaly session when normal-ratio < 1")
    parser.add_argument("--anomaly-type", default="auto", choices=["auto"] + [t for t in ANOMALY_TYPES if t != "normal"], help="specific anomaly to inject when anomaly session is chosen")
    parser.add_argument("--linger-ms", type=int, default=0)
    parser.add_argument("--acks", type=int, choices=[0, 1, -1], default=1)
    parser.add_argument("--request-timeout-ms", type=int, default=10000)
    parser.add_argument("--max-block-ms", type=int, default=10000)
    parser.add_argument("--retries", type=int, default=0)
    parser.add_argument("--async-send", action="store_true")
    return parser.parse_args(argv)


def choose_anomaly_type(args: argparse.Namespace, rng: random.Random) -> str:
    if args.anomaly_type != "auto":
        return args.anomaly_type
    return rng.choice([t for t in ANOMALY_TYPES if t != "normal"])


def emit_events(events: Sequence[Dict], args: argparse.Namespace, producer, rng: random.Random) -> int:
    sent = 0
    for event in events:
        if args.dry_run:
            print(json.dumps(event, ensure_ascii=False))
        else:
            future = producer.send(args.topic, key=event["insuredId"], value=event)
            if not args.async_send:
                metadata = future.get(timeout=max(1, args.request_timeout_ms // 1000))
                print(f"-> {metadata.topic}:{metadata.partition}@{metadata.offset} action={event['action']!r} anomaly={event['anomaly_type']}")
        sent += 1
        if args.rate > 0:
            time.sleep(max(0.0, 1.0 / args.rate))
    return sent


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    if args.session_count <= 0:
        print("--session-count must be > 0", file=sys.stderr)
        return 2
    if args.insured_count <= 0:
        print("--insured-count must be > 0", file=sys.stderr)
        return 2
    if not (0.0 <= args.normal_ratio <= 1.0):
        print("--normal-ratio must be between 0.0 and 1.0", file=sys.stderr)
        return 2
    if not (0.0 <= args.anomaly_rate <= 1.0):
        print("--anomaly-rate must be between 0.0 and 1.0", file=sys.stderr)
        return 2

    rng = random.Random(args.seed)
    users = build_users(rng, args.insured_count)
    cursor = parse_iso_dt(args.start_at)
    producer = None

    sessions_sent = 0
    events_sent = 0
    session_labels = Counter()
    start_wall = time.time()

    try:
        if not args.dry_run:
            producer = build_producer(args)

        while sessions_sent < args.session_count:
            if args.duration > 0 and (time.time() - start_wall) >= args.duration:
                break

            user = rng.choice(users)
            user.session_counter += 1
            session_id = f"{rng.randint(1000000, 9999999)}-{user.insured_id[-4:]}-{user.session_counter}"

            make_normal = rng.random() < args.normal_ratio
            if not make_normal and args.anomaly_rate > 0 and rng.random() > args.anomaly_rate:
                make_normal = True

            if make_normal:
                anomaly_type = "normal"
                plans = build_normal_session(user, cursor, rng)
            else:
                anomaly_type = choose_anomaly_type(args, rng)
                plans = build_anomaly_session(user, cursor, rng, anomaly_type)

            # advance cursor so sessions do not overlap too aggressively
            if plans:
                cursor = plans[-1].created_at + timedelta(seconds=rng.randint(20, 180))

            events = materialize_session_events(user, plans, session_id, user.session_counter, anomaly_type, rng)
            events_sent += emit_events(events, args, producer, rng)
            sessions_sent += 1
            session_labels[anomaly_type] += 1

        print(json.dumps({
            "sessions_sent": sessions_sent,
            "events_sent": events_sent,
            "session_labels": dict(session_labels),
            "topic": args.topic,
            "dry_run": args.dry_run,
        }, ensure_ascii=False, indent=2), file=sys.stderr)
        return 0
    except KeyboardInterrupt:
        print("Stopped by user", file=sys.stderr)
        return 130
    finally:
        close_producer(producer)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
