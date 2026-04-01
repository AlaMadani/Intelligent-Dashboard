#!/usr/bin/env python3
"""
simulator_yearly_dataset_revised.py
====================================
Generates one full year of realistic audit-trail training data.

Key improvements over the original:
  1. Action catalog loaded from backend-apis-actions.json (no Java scanning).
  2. Route-aware session generation driven by actions_order-v2.json logic:
       auth-flow  →  [route visit × N]  →  logout
  3. Three new CSV columns for better ML signal:
       • route          — frontend page where the action was triggered
       • prevAction     — previous action value in the same session
       • sessionLength  — total events in this session (known at generation time)
  4. HTTP codes mapped to real API semantics (match login flow status codes 202/206/208).
  5. Realistic login-flow variants (plain, MFA, SSO, account-creation, spam-warning…).
"""
import argparse
import csv
import json
import os
import random
import sys
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Action catalog  (derived from backend-apis-actions.json + Java-scan extras)
# Format: (value, type_name, sub_type, route)
# ---------------------------------------------------------------------------
_ALL_ACTIONS: List[Tuple[str, str, str, str]] = [
    # ── AUTH / LOGIN ──────────────────────────────────────────────────────
    ("Connexion",                                    "LOGGING_ACTIONS", "logging_login",                   "auth"),
    ("Connexion SSO",                                "LOGGING_ACTIONS", "logging_login_sso",               "auth"),
    ("Connexion en tant que",                        "LOGGING_ACTIONS", "logging_login_as_insured",        "auth"),
    ("Activation de compte",                         "LOGGING_ACTIONS", "logging_account_activation",     "auth"),
    ("Création de compte",                           "LOGGING_ACTIONS", "logging_account_creation",       "auth"),
    ("Demande de réinitialisation de mot de passe",  "LOGGING_ACTIONS", "",                               "auth"),
    ("Sélection email MFA",                          "LOGGING_ACTIONS", "logging_mfa_email_selection",    "auth"),
    ("Validation MFA",                               "LOGGING_ACTIONS", "logging_mfa_validation",         "auth"),
    ("Régénération code MFA",                        "LOGGING_ACTIONS", "logging_mfa_regeneration",       "auth"),
    ("Suppression email de la spam liste",           "LOGGING_ACTIONS", "",                               "auth"),
    # ── LOGOUT ────────────────────────────────────────────────────────────
    ("Déconnexion",                                  "LOGGING_ACTIONS", "logging_logout",                  "logout"),
    ("SSO Disconnect",                               "LOGGING_ACTIONS", "",                                "logout"),
    # ── PERSONAL INFO ─────────────────────────────────────────────────────
    ("Changer ses informations personnel",           "OPEN_ACTIONS",    "update_personal_information",    "personal_info"),
    ("Changement de Mot de passe",                   "LOGGING_ACTIONS", "logging_password_change",        "personal_info"),
    # ── BANKING INFO ──────────────────────────────────────────────────────
    ("Changer les coordonnées bancaire",             "BANKING_ACTIONS", "",                               "banking_info"),
    ("Signature électronique d'un mandat sepa",      "INSURED_ACTIONS", "sign_sepa_mandate",              "banking_info"),
    ("Changer les coordonnées bancaire, changement de RIB pour les cotisations",
                                                     "BANKING_ACTIONS", "update_bank_details_contributions","banking_info"),
    ("Changer les coordonnées bancaire, changement de RIB de bénéficiaire",
                                                     "BANKING_ACTIONS", "update_bank_details_beneficiary","banking_info"),
    ("Changer les coordonnées bancaire, changement de RIB pour les remboursements",
                                                     "BANKING_ACTIONS", "update_bank_details_refunds",    "banking_info"),
    # ── BENEFICIARIES ─────────────────────────────────────────────────────
    ("Ajouter un bénéficiaire",                      "INSURED_ACTIONS", "add_beneficiary",                "beneficiaries"),
    ("Supprimer un bénéficiaire",                    "INSURED_ACTIONS", "remove_beneficiary",             "beneficiaries"),
    # ── HOME ──────────────────────────────────────────────────────────────
    ("Exporter remboursement",                       "INSURED_ACTIONS", "export_refund",                  "home"),
    ("Télécharger un décompte",                      "INSURED_ACTIONS", "download_refund_statement",      "home"),
    ("Télécharger le certificat d'adhésion",         "INSURED_ACTIONS", "download_membership_certificate","home"),
    ("Ajout de la carte tiers payant dans le wallet","INSURED_ACTIONS", "add_third_party_card_to_wallet_ios","home"),
    # ── DOCUMENTS ─────────────────────────────────────────────────────────
    ("Envoi d'un document",                          "DOCUMENT_ACTIONS","",                               "documents"),
    ("Ouverture d'un document contractuel",          "INSURED_ACTIONS", "open_contract_document",         "documents"),
    ("Partager un justificatif du PACS",             "DOCUMENT_ACTIONS","share_pacs_proof_document",      "documents"),
    ("Partager un justificatif sur l'honneur de vie commune",
                                                     "DOCUMENT_ACTIONS","share_cohabitation_affidavit",  "documents"),
    # ── PREFERENCES ───────────────────────────────────────────────────────
    ("Changer l'organisme de rattachement sécu. Social",
                                                     "OPEN_ACTIONS",   "change_social_security_provider","preferences"),
    ("Renvoi carte TP papier",                       "INSURED_ACTIONS", "",                               "preferences"),
    ("Envoi carte TP par mail",                      "OPEN_ACTIONS",    "send_third_party_card_by_email", "preferences"),
    ("Téléchargement de la carte de tiers payant",   "INSURED_ACTIONS", "download_third_party_card",      "preferences"),
    ("Téléchargement carte TP",                      "INSURED_ACTIONS", "download_third_party_card",      "tp_card"),
    # ── CONTACT (shared across home/requests/refunds/banking_info/preferences)
    ("Envoi d'un message",                           "CONTACT_ACTIONS", "contact_send_message",           "requests"),
    ("Envoi d'une réclamation",                      "CONTACT_ACTIONS", "contact_submit_complaint",       "requests"),
    ("Contactez nous : via message (Mes remboursements)",
                                                     "CONTACT_ACTIONS","contact_message_my_refunds",     "requests"),
    ("Contactez nous : via message (Mes prises en charge/devis)",
                                                     "CONTACT_ACTIONS","contact_message_my_coverage_quotes","requests"),
    ("Contactez nous : via message (Mes garanties)", "CONTACT_ACTIONS","contact_message_my_coverage",    "requests"),
    ("Contactez nous : via message (Ma carte tiers-payant)",
                                                     "CONTACT_ACTIONS","contact_message_my_third_party_card","home"),
    ("Contactez nous : via message (Mon adhésion, Autres)",
                                                     "CONTACT_ACTIONS","contact_message_my_membership_other","home"),
    ("Contactez nous : via message (Mes cotisations)",
                                                     "CONTACT_ACTIONS","contact_message_my_contributions","banking_info"),
    ("Contactez nous : via message (Ma télétransmission - rattachement automatique avec votre Régime Obligatoire)",
                                                     "CONTACT_ACTIONS","contact_message_my_teletransmission","preferences"),
    ("demander un renvoi par mail d'échéancier",     "CONTACT_ACTIONS","request_payment_schedule_by_email","documents"),
    ("envoi d'une demande de résiliation",           "CONTACT_ACTIONS","request_cancellation_by_message","preferences"),
    # ── BACKOFFICE (very rare background event) ───────────────────────────
    ("Suppression liaison suite à suppression de l'entité",
                                                     "BACKOFFICE_ACTIONS","suppression_liaison_suite_a_suppression_d_entite","backoffice"),
]

@dataclass(frozen=True)
class RouteAction:
    value: str
    type_name: str
    sub_type: str
    route: str

# Build catalog and per-route lookup
ALL_ACTIONS: List[RouteAction] = [RouteAction(*t) for t in _ALL_ACTIONS]
ACTION_BY_VALUE: Dict[str, RouteAction] = {a.value: a for a in ALL_ACTIONS}
ACTIONS_BY_ROUTE: Dict[str, List[RouteAction]] = defaultdict(list)
for _a in ALL_ACTIONS:
    ACTIONS_BY_ROUTE[_a.route].append(_a)

# ---------------------------------------------------------------------------
# Navigation patterns   (from actions_order-v2.json)
# ---------------------------------------------------------------------------

# Route visit weights – probability that a user visits this route in a session
ROUTE_WEIGHTS: List[Tuple[str, float]] = [
    ("home",          0.30),
    ("requests",      0.22),
    ("refunds",       0.16),
    ("documents",     0.13),
    ("beneficiaries", 0.06),
    ("personal_info", 0.05),
    ("banking_info",  0.04),
    ("preferences",   0.03),
    ("tp_card",       0.01),
]

# How many actions to pick per route visit (weighted)
ROUTE_DEPTH_WEIGHTS: List[Tuple[int, float]] = [(1, 0.50), (2, 0.35), (3, 0.15)]

# Login-flow variants with their probability weights
# Each flow is a list of (route, action_value) pairs
_LOGIN_FLOWS: List[Tuple[List[str], float]] = [
    (["Connexion"],                                                         0.58),
    (["Connexion", "Validation MFA"],                                       0.14),
    (["Connexion SSO"],                                                      0.10),
    (["Connexion", "Sélection email MFA", "Validation MFA"],                0.07),
    (["Connexion", "Régénération code MFA", "Validation MFA"],              0.04),
    (["Connexion en tant que"],                                              0.03),
    (["Suppression email de la spam liste", "Connexion"],                   0.02),
    (["Activation de compte"],                                              0.015),
    (["Création de compte"],                                                0.005),
]

_LOGOUT_FLOWS: List[Tuple[str, float]] = [
    ("Déconnexion", 0.76),
    ("SSO Disconnect", 0.24),
]

# ---------------------------------------------------------------------------
# Geo / device / time constants  (unchanged from original)
# ---------------------------------------------------------------------------
EU_GEO_PROFILE = {
    "FR": {"weight": 0.60, "cities": ["Paris","Lyon","Marseille","Lille","Bordeaux","Nantes","Toulouse","Strasbourg"],
           "prefixes": [("2",),("80","12"),("81","64"),("82","64"),("83","112"),("86","192"),("90",)]},
    "BE": {"weight": 0.08, "cities": ["Brussels","Antwerp","Ghent","Liège"],
           "prefixes": [("37","60"),("46","18"),("62","235"),("91","176")]},
    "DE": {"weight": 0.08, "cities": ["Berlin","Hamburg","Munich","Frankfurt","Cologne"],
           "prefixes": [("46","5"),("80","128"),("87","123"),("91","0"),("95","90")]},
    "ES": {"weight": 0.07, "cities": ["Madrid","Barcelona","Valencia","Seville","Bilbao"],
           "prefixes": [("80","58"),("81","32"),("83","32"),("88","0"),("95","16")]},
    "IT": {"weight": 0.06, "cities": ["Milan","Rome","Turin","Bologna","Naples"],
           "prefixes": [("79","0"),("80","16"),("82","48"),("87","0"),("93","32")]},
    "NL": {"weight": 0.05, "cities": ["Amsterdam","Rotterdam","Utrecht","Eindhoven"],
           "prefixes": [("77","160"),("80","56"),("83","80"),("84","104")]},
    "PT": {"weight": 0.03, "cities": ["Lisbon","Porto","Braga"],
           "prefixes": [("85","240"),("88","157"),("94","60")]},
    "CH": {"weight": 0.02, "cities": ["Geneva","Lausanne","Zurich","Basel"],
           "prefixes": [("77","56"),("80","218"),("85","0")]},
    "LU": {"weight": 0.01, "cities": ["Luxembourg"],
           "prefixes": [("188","42"),("188","43")]},
}
BROWSER_POOL = [
    ("WEB",          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123.0.0.0 Safari/537.36"),
    ("WEB",          "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 Version/17.4 Safari/605.1.15"),
    ("WEB",          "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36"),
    ("MOBILE_ANDROID","mobileapp/4.2.1 (android; sdk=34; locale=fr-FR)"),
    ("MOBILE_ANDROID","mobileapp/4.1.7 (android; sdk=33; locale=fr-FR)"),
    ("MOBILE_IOS",   "mobileapp/4.3.0 (ios; build=812; locale=fr-FR)"),
    ("MOBILE_IOS",   "mobileapp/4.2.4 (ios; build=805; locale=fr-FR)"),
]
WEEKDAY_WEIGHTS = [1.25, 1.25, 1.20, 1.15, 1.05, 0.50, 0.40]

CSV_COLUMNS = [
    "id","insuredId","status","sessionId","action","httpCode","ip","userAgent",
    "requestData","requestReturn","createdAt","type","environmentId","device",
    "companyIdList","companyGroupIdList","insurerIdList","companySectionIdList",
    "insurerCodeIdList","healthcareNetworkIdList","domainIdList","subType",
    "countryCode","city","month","sessionNumber","sequenceInSession",
    # ── NEW ──
    "route","prevAction","sessionLength",
]

# ---------------------------------------------------------------------------
# User profile
# ---------------------------------------------------------------------------
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
    sessions_generated: int = 0
    session_id: Optional[str] = None

# ---------------------------------------------------------------------------
# Utility helpers
# ---------------------------------------------------------------------------
def weighted_choice(items: List[Tuple], rng: random.Random):
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
        parts[0] = rng.choice([2, 37, 46, 62, 77, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 93, 95])
    return ".".join(str(p) for p in parts[:4])


def format_ts(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def maybe_rotate_ip(user: UserProfile, rng: random.Random) -> None:
    if user.device.startswith("MOBILE") and rng.random() < 0.12:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)
    elif user.device == "WEB" and rng.random() < 0.04:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)


def random_time_in_month(month_start: datetime, month_end: datetime, rng: random.Random) -> datetime:
    days, day_weights = [], []
    cursor = month_start
    while cursor <= month_end:
        w = WEEKDAY_WEIGHTS[cursor.weekday()]
        if cursor.day in {1, 2, 3, 28, 29, 30, 31}:
            w *= 1.08
        days.append(cursor)
        day_weights.append(w)
        cursor += timedelta(days=1)
    day = weighted_choice(list(zip(days, day_weights)), rng)
    peak_hour = weighted_choice([(8.5, 0.33),(12.5, 0.17),(18.0, 0.27),(10.5, 0.13),(15.0, 0.10)], rng)
    h = int(peak_hour)
    dt = day.replace(hour=h, minute=int((peak_hour - h)*60), second=0, microsecond=0)
    dt += timedelta(minutes=rng.randint(-50, 50), seconds=rng.randint(0, 59))
    return max(month_start + timedelta(minutes=1), min(month_end - timedelta(minutes=1), dt))


def event_delay_seconds(route: str, rng: random.Random) -> int:
    ranges = {
        "auth":         (10, 60),
        "home":         (30, 180),
        "requests":     (60, 420),
        "refunds":      (40, 300),
        "documents":    (60, 480),
        "beneficiaries":(90, 600),
        "personal_info":(60, 360),
        "banking_info": (90, 540),
        "preferences":  (30, 240),
        "tp_card":      (20, 120),
        "logout":       (10, 60),
        "backoffice":   (5, 30),
    }
    lo, hi = ranges.get(route, (30, 300))
    return rng.randint(lo, hi)


def choose_status(route: str, rng: random.Random) -> str:
    failure_rates = {
        "auth": 0.03, "logout": 0.01, "banking_info": 0.08,
        "refunds": 0.06, "documents": 0.05, "requests": 0.05,
        "beneficiaries": 0.04, "personal_info": 0.03,
        "home": 0.02, "preferences": 0.03, "tp_card": 0.02,
    }
    return "KO" if rng.random() < failure_rates.get(route, 0.04) else "OK"


def choose_http_code(status: str, route: str, action_value: str, rng: random.Random) -> str:
    """HTTP codes aligned with actions_order-v2.json login branches."""
    if status == "OK":
        # Login has special status codes per branch in actions_order-v2.json
        if action_value == "Connexion":
            return str(rng.choice([200, 202, 206, 208]))  # 202=mfa, 206=mfa_email_list, 208=spam
        if action_value in ("Connexion SSO", "Connexion en tant que"):
            return "200"
        if route in ("documents", "requests", "beneficiaries", "banking_info"):
            return str(rng.choice([200, 201, 202]))
        return str(rng.choice([200, 204]))
    failure_codes = {
        "auth":         [401, 403],
        "banking_info": [400, 409, 422],
        "refunds":      [400, 409, 422],
        "requests":     [400, 422],
        "documents":    [400, 422],
    }
    return str(rng.choice(failure_codes.get(route, [400, 403, 409, 422, 500])))


def build_request_data(action: RouteAction, user: UserProfile, rng: random.Random) -> Optional[str]:
    r = action.route
    if r == "auth":
        return json.dumps({"login": user.insured_id,
                           "channel": "mobile" if user.device.startswith("MOBILE") else "web",
                           "country": user.country_code, "city": user.city})
    if r == "banking_info":
        return json.dumps({"iban_last4": str(rng.randint(1000,9999)),
                           "bic": rng.choice(["AGRIFRPP","BNPAFRPP","SOGEFRPP","CMCIFRPP"])})
    if r in ("requests", "home") and "contact" in action.type_name.lower():
        return json.dumps({"subject": action.value,
                           "message": rng.choice(["Besoin d'assistance","Question sur un remboursement","Demande d'information"]),
                           "priority": rng.choice(["low","normal","normal","high"])})
    if r == "documents":
        return json.dumps({"document": action.value,
                           "tag": rng.choice(["medical","administrative","identity","family"])})
    if r == "refunds":
        return json.dumps({"action": action.value, "claimAmount": round(rng.uniform(18,240),2), "currency": "EUR"})
    return json.dumps({"action": action.value})


def build_request_return(status: str, route: str, rng: random.Random) -> Optional[str]:
    if status == "OK":
        if route in ("auth","home") and rng.random() < 0.55:
            return None
        payload = {"status": "success", "reference": f"REF-{rng.randint(100000,999999)}"}
        if route == "refunds":
            payload["claimId"] = f"CLM-{rng.randint(1000000,9999999)}"
        return json.dumps(payload)
    if route in ("requests","home") and rng.random() < 0.5:
        return "SubTheme should not be empty !"
    return json.dumps({"status": "error", "code": rng.choice(["AUTH_FAILED","VALIDATION_ERROR","SERVER_ERROR","BUSINESS_RULE_REJECTED"])})


def gen_id_list(base: int, rng: random.Random, miss: float = 0.10) -> str:
    return json.dumps([] if rng.random() < miss else [base])


# ---------------------------------------------------------------------------
# Session building
# ---------------------------------------------------------------------------
def pick_login_actions(rng: random.Random) -> List[RouteAction]:
    flow_actions = weighted_choice(_LOGIN_FLOWS, rng)
    return [ACTION_BY_VALUE[v] for v in flow_actions]


def pick_route_actions(budget: int, rng: random.Random) -> List[Tuple[RouteAction, str]]:
    """Return list of (RouteAction, route_name) for the navigation phase."""
    result: List[Tuple[RouteAction, str]] = []
    visited: List[str] = []
    remaining = budget

    while remaining > 0:
        if len(visited) >= 4:   # cap at 4 route visits per session
            break
        route = weighted_choice(ROUTE_WEIGHTS, rng)
        pool = ACTIONS_BY_ROUTE.get(route, [])
        if not pool:
            remaining -= 1
            continue
        depth = weighted_choice(ROUTE_DEPTH_WEIGHTS, rng)
        depth = min(depth, remaining, len(pool))
        chosen = rng.sample(pool, depth)
        for act in chosen:
            result.append((act, route))
        visited.append(route)
        remaining -= depth
        if rng.random() < 0.20:     # 20% chance to stop early
            break

    return result


def build_session_events(
    user: UserProfile,
    session_number: int,
    month_start: datetime,
    month_end: datetime,
    target_length: int,
    rng: random.Random,
) -> List[Dict]:
    user.sessions_generated += 1
    session_id = str(rng.randint(100000, 9999999))
    user.session_id = session_id

    # Phase 1 – login
    login_acts = pick_login_actions(rng)

    # Phase 2 – navigation  (budget = target - login - 1 for logout)
    budget = max(1, target_length - len(login_acts) - 1)
    nav_pairs = pick_route_actions(budget, rng)

    # Phase 3 – logout
    logout_value = weighted_choice(_LOGOUT_FLOWS, rng)
    logout_act = ACTION_BY_VALUE[logout_value]

    all_actions: List[Tuple[RouteAction, str]] = (
        [(a, "auth") for a in login_acts]
        + nav_pairs
        + [(logout_act, "logout")]
    )
    session_length = len(all_actions)

    # Assign timestamps
    start_ts = random_time_in_month(month_start, month_end, rng)
    timestamps = [start_ts]
    for i in range(1, session_length):
        prev_route = all_actions[i-1][0].route
        delay = event_delay_seconds(prev_route, rng)
        next_ts = timestamps[-1] + timedelta(seconds=delay)
        if next_ts > month_end:
            next_ts = month_end - timedelta(minutes=max(0, session_length - i))
        timestamps.append(next_ts)

    # Build event records
    events = []
    prev_action_value = ""
    for seq_idx, ((action, route), ts) in enumerate(zip(all_actions, timestamps), start=1):
        maybe_rotate_ip(user, rng)
        status = choose_status(route, rng)
        http_code = choose_http_code(status, route, action.value, rng)

        event = {
            "id":                str(uuid.uuid4()),
            "insuredId":         user.insured_id,
            "status":            status,
            "sessionId":         session_id,
            "action":            action.value,
            "httpCode":          http_code,
            "ip":                user.current_ip,
            "userAgent":         user.user_agent,
            "requestData":       build_request_data(action, user, rng),
            "requestReturn":     build_request_return(status, route, rng),
            "createdAt":         format_ts(ts.astimezone(timezone.utc)),
            "type":              action.type_name,
            "environmentId":     str(user.environment_id),
            "device":            user.device,
            "companyIdList":     gen_id_list(user.company_id, rng),
            "companyGroupIdList":gen_id_list(user.company_group_id, rng, 0.08),
            "insurerIdList":     gen_id_list(user.insurer_id, rng),
            "companySectionIdList":gen_id_list(user.company_section_id, rng),
            "insurerCodeIdList": gen_id_list(user.insurer_code_id, rng),
            "healthcareNetworkIdList":gen_id_list(user.healthcare_network_id, rng, 0.15),
            "domainIdList":      gen_id_list(user.domain_id, rng),
            "subType":           action.sub_type or "",
            "countryCode":       user.country_code,
            "city":              user.city,
            "month":             ts.strftime("%Y-%m"),
            "sessionNumber":     session_number,
            "sequenceInSession": seq_idx,
            # ── NEW ──
            "route":             route,
            "prevAction":        prev_action_value,
            "sessionLength":     session_length,
        }
        events.append(event)
        prev_action_value = action.value

    return events


# ---------------------------------------------------------------------------
# Session length distribution
# ---------------------------------------------------------------------------
def choose_session_lengths(total_actions: int, rng: random.Random) -> List[int]:
    lengths, remaining = [], total_actions
    length_weights = [(3,0.15),(4,0.20),(5,0.22),(6,0.18),(7,0.12),(8,0.08),(9,0.05)]
    while remaining > 0:
        if remaining <= 9:
            lengths.append(remaining)
            break
        length = weighted_choice(length_weights, rng)
        if remaining - length in {1, 2}:
            length += remaining - length
        lengths.append(length)
        remaining -= length
    return lengths


# ---------------------------------------------------------------------------
# User pool
# ---------------------------------------------------------------------------
def build_users(rng: random.Random, count: int) -> List[UserProfile]:
    geo_items = [(cc, p["weight"]) for cc, p in EU_GEO_PROFILE.items()]
    pools = {
        "company_id":    [27011,27012,27013,27021],
        "company_group": [12001,12002,12003],
        "section":       [110027,110028,110029],
        "insurer":       [1,2,3],
        "insurer_code":  [302,303,304],
        "network":       [1,2,3],
        "domain":        [10,11,12],
        "env":           [10,11],
    }
    users = []
    for _ in range(count):
        cc = weighted_choice(geo_items, rng)
        geo = EU_GEO_PROFILE[cc]
        prefix = rng.choice(geo["prefixes"])
        device, ua = rng.choice(BROWSER_POOL)
        users.append(UserProfile(
            insured_id=f"{rng.randint(10000000,99999999)}",
            country_code=cc, city=rng.choice(geo["cities"]),
            current_ip=random_public_ip(prefix, rng), home_ip_prefix=prefix,
            device=device, user_agent=ua,
            company_id=rng.choice(pools["company_id"]),
            company_group_id=rng.choice(pools["company_group"]),
            company_section_id=rng.choice(pools["section"]),
            insurer_id=rng.choice(pools["insurer"]),
            insurer_code_id=rng.choice(pools["insurer_code"]),
            healthcare_network_id=rng.choice(pools["network"]),
            domain_id=rng.choice(pools["domain"]),
            environment_id=rng.choice(pools["env"]),
        ))
    return users


# ---------------------------------------------------------------------------
# Month iteration
# ---------------------------------------------------------------------------
def month_range(start: datetime, end: datetime):
    cursor = datetime(start.year, start.month, 1, tzinfo=timezone.utc)
    idx = 0
    while cursor <= end:
        nm = datetime(cursor.year + (cursor.month == 12), (cursor.month % 12) + 1, 1, tzinfo=timezone.utc)
        month_end = nm - timedelta(seconds=1)
        yield idx, cursor, month_end
        cursor = nm
        idx += 1


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
def write_csv(events: List[Dict], output: str) -> None:
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    with open(output, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=CSV_COLUMNS)
        w.writeheader()
        for ev in events:
            w.writerow({col: ev.get(col, "") for col in CSV_COLUMNS})


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def parse_args(argv):
    p = argparse.ArgumentParser(description="Generate route-aware audit-trail dataset for ML training")
    p.add_argument("--output",              default="audit_trail_2025.csv")
    p.add_argument("--start-date",          default="2025-01-01")
    p.add_argument("--end-date",            default="2025-12-31")
    p.add_argument("--users",               type=int, default=50)
    p.add_argument("--actions-per-month",   type=int, default=5000)
    p.add_argument("--seed",                type=int, default=2025)
    # Legacy Java-scan args kept for backward-compat but ignored (catalog is embedded)
    p.add_argument("--project-root",        default=None, help="ignored – catalog is built from embedded JSON")
    p.add_argument("--backend-apis",        default=None, help="optional path to backend-apis-actions.json (informational)")
    p.add_argument("--actions-order",       default=None, help="optional path to actions_order-v2.json (informational)")
    return p.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    rng = random.Random(args.seed)

    start = datetime.fromisoformat(args.start_date).replace(tzinfo=timezone.utc)
    end   = datetime.fromisoformat(args.end_date).replace(hour=23, minute=59, second=59, tzinfo=timezone.utc)
    months = list(month_range(start, end))

    if args.actions_per_month % args.users != 0:
        print("Warning: actions-per-month not divisible by users – rounding", file=sys.stderr)
    apu = args.actions_per_month // args.users   # actions per user per month

    users = build_users(rng, args.users)
    all_events: List[Dict] = []
    global_session_counter = 0

    for _idx, month_start, month_end in months:
        month_events = []
        for user in users:
            session_lengths = choose_session_lengths(apu, rng)
            for length in session_lengths:
                global_session_counter += 1
                evs = build_session_events(user, global_session_counter, month_start, month_end, length, rng)
                month_events.extend(evs)
        # Sort by timestamp within the month
        month_events.sort(key=lambda e: e["createdAt"])
        all_events.extend(month_events)

    write_csv(all_events, args.output)
    print(json.dumps({
        "output":          args.output,
        "total_events":    len(all_events),
        "months":          len(months),
        "users":           args.users,
        "unique_actions":  len(ALL_ACTIONS),
        "routes":          sorted(ACTIONS_BY_ROUTE.keys()),
        "new_columns":     ["route", "prevAction", "sessionLength"],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
