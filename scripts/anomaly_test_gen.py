#!/usr/bin/env python3
"""
anomaly_test_generator.py
=========================
Sends labeled anomalous AND normal sessions directly to Kafka so you can
validate your Spring Boot anomaly detection pipeline end-to-end.

Each event carries two extra fields:
  "is_anomaly"   : 0 or 1
  "anomaly_type" : "" | "rapid_fire" | "unusual_hour" | "geo_jump" |
                   "repeated_fail" | "skip_login" | "impossible_seq"

Your Spring Boot consumer can log or compare these against model outputs
to measure real detection accuracy during live testing.

The 6 anomaly types and their clear signatures:
  rapid_fire    — delta between events < 5 s across whole session
  unusual_hour  — session starts at 02:00-04:00
  geo_jump      — IP changes from EU prefix to foreign (Africa/Asia) after step 2
  repeated_fail — 4+ consecutive KO on BANKING_ACTIONS in same session
  skip_login    — first action is NOT a login/activation action
  impossible_seq— Deconnexion appears mid-session, followed by more actions

Standalone — no dependency on simulator_yearly_dataset_v3.py.

Usage:
    # Send 50 sessions of each type (300 anomalous + 300 normal total)
    python anomaly_test_generator.py \\
        --bootstrap-servers localhost:9092 \\
        --topic topic-audit-trail \\
        --samples-per-type 50

    # Only one anomaly type
    python anomaly_test_generator.py \\
        --bootstrap-servers localhost:9092 \\
        --samples-per-type 20 \\
        --type geo_jump

    # Dry-run to inspect event structure
    python anomaly_test_generator.py --dry-run --samples-per-type 2
"""

import argparse
import json
import os
import random
import sys
import time
import uuid
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Tuple

try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

# ---------------------------------------------------------------------------
# Action catalog — 43 actions matching training vocab exactly
# (value, type_name, sub_type, route)
# ---------------------------------------------------------------------------
_ALL_ACTIONS: List[Tuple[str, str, str, str]] = [
    ("Connexion",                                                                                                    "LOGGING_ACTIONS",    "logging_login",                                    "auth"),
    ("Connexion SSO",                                                                                                "LOGGING_ACTIONS",    "logging_login_sso",                               "auth"),
    ("Connexion en tant que",                                                                                        "LOGGING_ACTIONS",    "logging_login_as_insured",                        "auth"),
    ("Activation de compte",                                                                                         "LOGGING_ACTIONS",    "logging_account_activation",                      "auth"),
    ("Creation de compte",                                                                                           "LOGGING_ACTIONS",    "logging_account_creation",                        "auth"),
    ("Demande de reinitialisation de mot de passe",                                                                  "LOGGING_ACTIONS",    "",                                                "auth"),
    ("Selection email MFA",                                                                                          "LOGGING_ACTIONS",    "logging_mfa_email_selection",                     "auth"),
    ("Validation MFA",                                                                                               "LOGGING_ACTIONS",    "logging_mfa_validation",                          "auth"),
    ("Regeneration code MFA",                                                                                        "LOGGING_ACTIONS",    "logging_mfa_regeneration",                        "auth"),
    ("Deconnexion",                                                                                                  "LOGGING_ACTIONS",    "logging_logout",                                  "logout"),
    ("SSO Disconnect",                                                                                               "LOGGING_ACTIONS",    "",                                                "logout"),
    ("Changement de Mot de passe",                                                                                   "LOGGING_ACTIONS",    "logging_password_change",                         "personal_info"),
    ("Changer ses informations personnel",                                                                           "OPEN_ACTIONS",       "update_personal_information",                     "personal_info"),
    ("Changer les coordonnees bancaire",                                                                             "BANKING_ACTIONS",    "",                                                "banking_info"),
    ("Signature electronique d'un mandat sepa",                                                                      "INSURED_ACTIONS",    "sign_sepa_mandate",                               "banking_info"),
    ("Changer les coordonnees bancaire, changement de RIB pour les cotisations",                                     "BANKING_ACTIONS",    "update_bank_details_contributions",               "banking_info"),
    ("Changer les coordonnees bancaire, changement de RIB de beneficiaire",                                          "BANKING_ACTIONS",    "update_bank_details_beneficiary",                 "banking_info"),
    ("Changer les coordonnees bancaire, changement de RIB pour les remboursements",                                  "BANKING_ACTIONS",    "update_bank_details_refunds",                     "banking_info"),
    ("Exporter remboursement",                                                                                       "INSURED_ACTIONS",    "export_refund",                                   "home"),
    ("Telecharger un decompte",                                                                                      "INSURED_ACTIONS",    "download_refund_statement",                       "home"),
    ("Telecharger le certificat d'adhesion",                                                                         "INSURED_ACTIONS",    "download_membership_certificate",                 "home"),
    ("Ajout de la carte tiers payant dans le wallet",                                                                "INSURED_ACTIONS",    "add_third_party_card_to_wallet_ios",               "home"),
    ("Envoi d'un document",                                                                                          "DOCUMENT_ACTIONS",   "",                                                "documents"),
    ("Ouverture d'un document contractuel",                                                                          "INSURED_ACTIONS",    "open_contract_document",                          "documents"),
    ("Partager un justificatif du PACS",                                                                             "DOCUMENT_ACTIONS",   "share_pacs_proof_document",                       "documents"),
    ("Partager un justificatif sur l'honneur de vie commune",                                                        "DOCUMENT_ACTIONS",   "share_cohabitation_affidavit",                    "documents"),
    ("Changer l'organisme de rattachement secu. Social",                                                             "OPEN_ACTIONS",       "change_social_security_provider",                 "preferences"),
    ("Renvoi carte TP papier",                                                                                       "INSURED_ACTIONS",    "",                                                "preferences"),
    ("Envoi carte TP par mail",                                                                                      "OPEN_ACTIONS",       "send_third_party_card_by_email",                  "preferences"),
    ("Telechargement de la carte de tiers payant",                                                                   "INSURED_ACTIONS",    "download_third_party_card",                       "preferences"),
    ("Telechargement carte TP",                                                                                      "INSURED_ACTIONS",    "download_third_party_card",                       "tp_card"),
    ("Envoi d'un message",                                                                                           "CONTACT_ACTIONS",    "contact_send_message",                            "requests"),
    ("Envoi d'une reclamation",                                                                                      "CONTACT_ACTIONS",    "contact_submit_complaint",                        "requests"),
    ("Contactez nous : via message (Mes remboursements)",                                                            "CONTACT_ACTIONS",    "contact_message_my_refunds",                      "requests"),
    ("Contactez nous : via message (Mes prises en charge/devis)",                                                    "CONTACT_ACTIONS",    "contact_message_my_coverage_quotes",              "requests"),
    ("Contactez nous : via message (Mes garanties)",                                                                 "CONTACT_ACTIONS",    "contact_message_my_coverage",                     "requests"),
    ("Contactez nous : via message (Ma carte tiers-payant)",                                                         "CONTACT_ACTIONS",    "contact_message_my_third_party_card",             "home"),
    ("Contactez nous : via message (Mon adhesion, Autres)",                                                          "CONTACT_ACTIONS",    "contact_message_my_membership_other",             "home"),
    ("Contactez nous : via message (Mes cotisations)",                                                               "CONTACT_ACTIONS",    "contact_message_my_contributions",                "banking_info"),
    ("Contactez nous : via message (Ma teletransmission - rattachement automatique avec votre Regime Obligatoire)",  "CONTACT_ACTIONS",    "contact_message_my_teletransmission",             "preferences"),
    ("demander un renvoi par mail d'echeancier",                                                                     "CONTACT_ACTIONS",    "request_payment_schedule_by_email",               "documents"),
    ("envoi d'une demande de resiliation",                                                                           "CONTACT_ACTIONS",    "request_cancellation_by_message",                 "preferences"),
    ("Suppression liaison suite a suppression de l'entite",                                                          "BACKOFFICE_ACTIONS", "suppression_liaison_suite_a_suppression_d_entite","backoffice"),
]

# Use exact UTF-8 strings from training vocab for the actions that matter most
# We override the ASCII-safe versions above with the real French strings here
_EXACT_STRINGS = {
    "Deconnexion":                                          "Déconnexion",
    "Selection email MFA":                                  "Sélection email MFA",
    "Regeneration code MFA":                                "Régénération code MFA",
    "Creation de compte":                                   "Création de compte",
    "Demande de reinitialisation de mot de passe":          "Demande de réinitialisation de mot de passe",
    "Changer les coordonnees bancaire":                     "Changer les coordonnées bancaire",
    "Signature electronique d'un mandat sepa":              "Signature électronique d'un mandat sepa",
    "Changer les coordonnees bancaire, changement de RIB pour les cotisations":
        "Changer les coordonnées bancaire, changement de RIB pour les cotisations",
    "Changer les coordonnees bancaire, changement de RIB de beneficiaire":
        "Changer les coordonnées bancaire, changement de RIB de bénéficiaire",
    "Changer les coordonnees bancaire, changement de RIB pour les remboursements":
        "Changer les coordonnées bancaire, changement de RIB pour les remboursements",
    "Telecharger un decompte":                              "Télécharger un décompte",
    "Telecharger le certificat d'adhesion":                 "Télécharger le certificat d'adhésion",
    "Changer les coordonnees bancaire":                     "Changer les coordonnées bancaire",
    "Changer l'organisme de rattachement secu. Social":     "Changer l'organisme de rattachement sécu. Social",
    "Telechargement de la carte de tiers payant":           "Téléchargement de la carte de tiers payant",
    "Telechargement carte TP":                              "Téléchargement carte TP",
    "Envoi d'une reclamation":                              "Envoi d'une réclamation",
    "Contactez nous : via message (Mon adhesion, Autres)":  "Contactez nous : via message (Mon adhésion, Autres)",
    "Contactez nous : via message (Mes cotisations)":       "Contactez nous : via message (Mes cotisations)",
    "Contactez nous : via message (Ma teletransmission - rattachement automatique avec votre Regime Obligatoire)":
        "Contactez nous : via message (Ma télétransmission - rattachement automatique avec votre Régime Obligatoire)",
    "demander un renvoi par mail d'echeancier":             "demander un renvoi par mail d'échéancier",
    "envoi d'une demande de resiliation":                   "envoi d'une demande de résiliation",
    "Suppression liaison suite a suppression de l'entite":  "Suppression liaison suite à suppression de l'entité",
    "Changer ses informations personnel":                   "Changer ses informations personnel",
}


@dataclass(frozen=True)
class Action:
    value: str
    type_name: str
    sub_type: str
    route: str


def _make_action(t):
    value = _EXACT_STRINGS.get(t[0], t[0])
    return Action(value, t[1], t[2], t[3])


ALL_ACTIONS: List[Action] = [_make_action(t) for t in _ALL_ACTIONS]
BY_VALUE: Dict[str, Action] = {a.value: a for a in ALL_ACTIONS}
BY_ROUTE: Dict[str, List[Action]] = defaultdict(list)
for _a in ALL_ACTIONS:
    BY_ROUTE[_a.route].append(_a)

LOGIN_ACTIONS   = [a for a in ALL_ACTIONS if a.route == "auth" and
                   a.value in ("Connexion", "Connexion SSO", "Connexion en tant que", "Activation de compte")]
LOGOUT_ACTIONS  = [a for a in ALL_ACTIONS if a.route == "logout"]
BANKING_ACTIONS = [a for a in ALL_ACTIONS if a.type_name == "BANKING_ACTIONS"]
CONTENT_ACTIONS = [a for a in ALL_ACTIONS if a.route not in ("auth", "logout", "backoffice")]

ANOMALY_TYPES = [
    "rapid_fire",
    "unusual_hour",
    "geo_jump",
    "repeated_fail",
    "skip_login",
    "impossible_seq",
]

# ---------------------------------------------------------------------------
# Geo constants
# ---------------------------------------------------------------------------
EU_GEO_PROFILE = {
    "FR": {"weight": 0.60, "cities": ["Paris", "Lyon", "Marseille", "Lille", "Bordeaux", "Nantes"],
           "prefixes": [("2",), ("80", "12"), ("81", "64"), ("83", "112"), ("86", "192"), ("90",)]},
    "BE": {"weight": 0.08, "cities": ["Brussels", "Antwerp", "Ghent"],
           "prefixes": [("37", "60"), ("46", "18"), ("91", "176")]},
    "DE": {"weight": 0.08, "cities": ["Berlin", "Hamburg", "Munich"],
           "prefixes": [("46", "5"), ("80", "128"), ("87", "123")]},
    "ES": {"weight": 0.07, "cities": ["Madrid", "Barcelona", "Valencia"],
           "prefixes": [("80", "58"), ("81", "32"), ("95", "16")]},
    "IT": {"weight": 0.06, "cities": ["Milan", "Rome", "Turin"],
           "prefixes": [("79", "0"), ("80", "16"), ("93", "32")]},
    "NL": {"weight": 0.05, "cities": ["Amsterdam", "Rotterdam"],
           "prefixes": [("77", "160"), ("80", "56")]},
    "PT": {"weight": 0.03, "cities": ["Lisbon", "Porto"],
           "prefixes": [("85", "240"), ("88", "157")]},
    "CH": {"weight": 0.02, "cities": ["Geneva", "Zurich"],
           "prefixes": [("77", "56"), ("80", "218")]},
    "LU": {"weight": 0.01, "cities": ["Luxembourg"],
           "prefixes": [("188", "42")]},
}

BROWSER_POOL = [
    ("WEB",            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/123.0.0.0 Safari/537.36"),
    ("WEB",            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) Version/17.4 Safari/605.1.15"),
    ("MOBILE_ANDROID", "mobileapp/4.2.1 (android; sdk=34; locale=fr-FR)"),
    ("MOBILE_ANDROID", "mobileapp/4.1.7 (android; sdk=33; locale=fr-FR)"),
    ("MOBILE_IOS",     "mobileapp/4.3.0 (ios; build=812; locale=fr-FR)"),
]

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def weighted_choice(items, rng):
    total = sum(w for _, w in items)
    pick = rng.random() * total
    upto = 0.0
    for item, w in items:
        upto += w
        if upto >= pick:
            return item
    return items[-1][0]


def random_eu_ip(prefix, rng):
    parts = [int(p) for p in prefix]
    while len(parts) < 4:
        parts.append(rng.randint(2, 250) if len(parts) == 3 else rng.randint(0, 255))
    if parts[0] in {10, 127, 169, 172, 192}:
        parts[0] = rng.choice([37, 46, 62, 77, 80, 81, 83, 85, 86, 88, 90, 91, 95])
    return ".".join(str(p) for p in parts[:4])


def random_foreign_ip(rng):
    """Returns a clearly non-EU IP (Africa / Asia / LatAm prefix)."""
    first = rng.choice([41, 102, 103, 105, 196, 197, 203, 210])
    return f"{first}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(2,250)}"


def format_ts(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


# ---------------------------------------------------------------------------
# User fixture
# ---------------------------------------------------------------------------
@dataclass
class UserFixture:
    user_id: str
    country_code: str
    city: str
    ip: str
    ip_prefix: Tuple
    device: str
    ua: str
    company_id: int
    insurer_id: int
    env_id: int


def random_user(rng) -> UserFixture:
    geo_items = [(cc, p["weight"]) for cc, p in EU_GEO_PROFILE.items()]
    cc = weighted_choice(geo_items, rng)
    geo = EU_GEO_PROFILE[cc]
    prefix = rng.choice(geo["prefixes"])
    device, ua = rng.choice(BROWSER_POOL)
    return UserFixture(
        user_id=str(rng.randint(10_000_000, 99_999_999)),
        country_code=cc, city=rng.choice(geo["cities"]),
        ip=random_eu_ip(prefix, rng), ip_prefix=prefix,
        device=device, ua=ua,
        company_id=rng.choice([27011, 27012, 27013, 27021]),
        insurer_id=rng.choice([1, 2, 3]),
        env_id=rng.choice([10, 11]),
    )


# ---------------------------------------------------------------------------
# Core event factory
# ---------------------------------------------------------------------------
def make_event(action: Action, user: UserFixture, session_id: str,
               ip: str, created_at: datetime, status: str,
               is_anomaly: int, anomaly_type: str, rng: random.Random) -> Dict:
    if status == "OK":
        if action.route == "auth":
            code = rng.choice([200, 202, 206])
        elif action.route in ("documents", "requests", "banking_info"):
            code = rng.choice([200, 201, 202])
        else:
            code = rng.choice([200, 204])
    else:
        code = rng.choice({
            "auth":        [401, 403],
            "banking_info":[400, 409, 422],
        }.get(action.route, [400, 403, 422, 500]))

    return {
        "id":                      str(uuid.uuid4()),
        "insuredId":               user.user_id,
        "status":                  status,
        "sessionId":               session_id,
        "action":                  action.value,
        "httpCode":                str(code),
        "ip":                      ip,
        "userAgent":               user.ua,
        "requestData":             json.dumps({"action": action.value}),
        "requestReturn":           json.dumps({"status": "success"}) if status == "OK"
                                   else json.dumps({"status": "error",
                                                    "code": rng.choice(["AUTH_FAILED",
                                                                         "VALIDATION_ERROR",
                                                                         "SERVER_ERROR"])}),
        "createdAt":               format_ts(created_at.astimezone(timezone.utc)),
        "type":                    action.type_name,
        "environmentId":           str(user.env_id),
        "device":                  user.device,
        "countryCode":             user.country_code,
        "city":                    user.city,
        "companyIdList":           [user.company_id],
        "companyGroupIdList":      [12001],
        "insurerIdList":           [user.insurer_id],
        "companySectionIdList":    [110027],
        "insurerCodeIdList":       [302],
        "healthcareNetworkIdList": [1],
        "domainIdList":            [10],
        "subType":                 action.sub_type or "",
        "route":                   action.route,
        # Ground-truth labels — compare against Spring Boot model outputs
        "is_anomaly":              is_anomaly,
        "anomaly_type":            anomaly_type,
    }


# ---------------------------------------------------------------------------
# Normal session
# ---------------------------------------------------------------------------
def build_normal_session(user: UserFixture, base_time: datetime,
                         rng: random.Random) -> List[Dict]:
    session_id = str(rng.randint(100_000, 9_999_999))
    t = base_time
    events = []

    # Login
    login = rng.choice(LOGIN_ACTIONS)
    events.append(make_event(login, user, session_id, user.ip, t, "OK", 0, "", rng))
    t += timedelta(seconds=rng.randint(30, 120))

    # 1–3 content actions
    for _ in range(rng.randint(1, 3)):
        act = rng.choice(CONTENT_ACTIONS)
        status = "KO" if rng.random() < 0.05 else "OK"
        events.append(make_event(act, user, session_id, user.ip, t, status, 0, "", rng))
        t += timedelta(seconds=rng.randint(60, 300))

    # Logout
    events.append(make_event(
        rng.choice(LOGOUT_ACTIONS), user, session_id, user.ip, t, "OK", 0, "", rng))

    return events


# ---------------------------------------------------------------------------
# Anomaly session builders
# ---------------------------------------------------------------------------
def build_rapid_fire_session(user: UserFixture, base_time: datetime,
                              rng: random.Random) -> List[Dict]:
    """All events < 5 s apart — automated bot signature."""
    session_id = str(rng.randint(100_000, 9_999_999))
    t = base_time
    events = []
    seq = ([rng.choice(LOGIN_ACTIONS)]
           + [rng.choice(CONTENT_ACTIONS) for _ in range(rng.randint(4, 8))]
           + [rng.choice(LOGOUT_ACTIONS)])
    for act in seq:
        status = "KO" if rng.random() < 0.15 else "OK"
        events.append(make_event(act, user, session_id, user.ip, t,
                                 status, 1, "rapid_fire", rng))
        t += timedelta(seconds=rng.randint(1, 4))   # ← sub-5-second gaps
    return events


def build_unusual_hour_session(user: UserFixture, base_time: datetime,
                                rng: random.Random) -> List[Dict]:
    """Session starts at 02:00–04:00."""
    t = base_time.replace(
        hour=rng.randint(2, 4), minute=rng.randint(0, 59), second=rng.randint(0, 59))
    session_id = str(rng.randint(100_000, 9_999_999))
    events = []

    events.append(make_event(
        rng.choice(LOGIN_ACTIONS), user, session_id, user.ip, t, "OK",
        1, "unusual_hour", rng))
    t += timedelta(seconds=rng.randint(60, 180))

    for act in [rng.choice(CONTENT_ACTIONS) for _ in range(rng.randint(1, 3))]:
        events.append(make_event(act, user, session_id, user.ip, t, "OK",
                                 1, "unusual_hour", rng))
        t += timedelta(seconds=rng.randint(60, 300))

    events.append(make_event(
        rng.choice(LOGOUT_ACTIONS), user, session_id, user.ip, t, "OK",
        1, "unusual_hour", rng))
    return events


def build_geo_jump_session(user: UserFixture, base_time: datetime,
                            rng: random.Random) -> List[Dict]:
    """IP switches from EU to foreign after step 2."""
    session_id = str(rng.randint(100_000, 9_999_999))
    t = base_time
    events = []
    foreign_ip = random_foreign_ip(rng)

    # Steps 1–2: normal EU IP
    for act in [rng.choice(LOGIN_ACTIONS), rng.choice(CONTENT_ACTIONS)]:
        events.append(make_event(act, user, session_id, user.ip, t,
                                 "OK", 1, "geo_jump", rng))
        t += timedelta(seconds=rng.randint(30, 120))

    # Steps 3+: foreign IP
    for act in [rng.choice(CONTENT_ACTIONS) for _ in range(rng.randint(2, 4))]:
        events.append(make_event(act, user, session_id, foreign_ip, t,
                                 "OK", 1, "geo_jump", rng))
        t += timedelta(seconds=rng.randint(30, 120))

    events.append(make_event(
        rng.choice(LOGOUT_ACTIONS), user, session_id, foreign_ip, t,
        "OK", 1, "geo_jump", rng))
    return events


def build_repeated_fail_session(user: UserFixture, base_time: datetime,
                                 rng: random.Random) -> List[Dict]:
    """4+ consecutive KO on BANKING_ACTIONS — brute-force signature."""
    session_id = str(rng.randint(100_000, 9_999_999))
    t = base_time
    events = []

    events.append(make_event(
        rng.choice(LOGIN_ACTIONS), user, session_id, user.ip, t,
        "OK", 1, "repeated_fail", rng))
    t += timedelta(seconds=rng.randint(30, 90))

    for act in [rng.choice(BANKING_ACTIONS) for _ in range(rng.randint(4, 6))]:
        events.append(make_event(act, user, session_id, user.ip, t,
                                 "KO", 1, "repeated_fail", rng))   # ← all KO
        t += timedelta(seconds=rng.randint(10, 60))

    events.append(make_event(
        rng.choice(LOGOUT_ACTIONS), user, session_id, user.ip, t,
        "OK", 1, "repeated_fail", rng))
    return events


def build_skip_login_session(user: UserFixture, base_time: datetime,
                              rng: random.Random) -> List[Dict]:
    """First action is NOT a login — session starts mid-flow."""
    session_id = str(rng.randint(100_000, 9_999_999))
    t = base_time
    events = []

    for act in [rng.choice(CONTENT_ACTIONS) for _ in range(rng.randint(2, 5))]:
        events.append(make_event(act, user, session_id, user.ip, t,
                                 "OK", 1, "skip_login", rng))
        t += timedelta(seconds=rng.randint(30, 180))

    events.append(make_event(
        rng.choice(LOGOUT_ACTIONS), user, session_id, user.ip, t,
        "OK", 1, "skip_login", rng))
    return events


def build_impossible_seq_session(user: UserFixture, base_time: datetime,
                                  rng: random.Random) -> List[Dict]:
    """Logout in the middle, followed by more actions — impossible sequence."""
    session_id = str(rng.randint(100_000, 9_999_999))
    t = base_time
    events = []

    events.append(make_event(
        rng.choice(LOGIN_ACTIONS), user, session_id, user.ip, t,
        "OK", 1, "impossible_seq", rng))
    t += timedelta(seconds=rng.randint(30, 90))

    for act in [rng.choice(CONTENT_ACTIONS) for _ in range(rng.randint(1, 2))]:
        events.append(make_event(act, user, session_id, user.ip, t,
                                 "OK", 1, "impossible_seq", rng))
        t += timedelta(seconds=rng.randint(30, 120))

    # Logout in the MIDDLE (the anomaly)
    events.append(make_event(
        rng.choice(LOGOUT_ACTIONS), user, session_id, user.ip, t,
        "OK", 1, "impossible_seq", rng))
    t += timedelta(seconds=rng.randint(10, 30))

    # More actions AFTER logout
    for act in [rng.choice(CONTENT_ACTIONS) for _ in range(rng.randint(2, 4))]:
        events.append(make_event(act, user, session_id, user.ip, t,
                                 "OK", 1, "impossible_seq", rng))
        t += timedelta(seconds=rng.randint(20, 90))

    return events


ANOMALY_BUILDERS = {
    "rapid_fire":    build_rapid_fire_session,
    "unusual_hour":  build_unusual_hour_session,
    "geo_jump":      build_geo_jump_session,
    "repeated_fail": build_repeated_fail_session,
    "skip_login":    build_skip_login_session,
    "impossible_seq":build_impossible_seq_session,
}

# ---------------------------------------------------------------------------
# Kafka helpers
# ---------------------------------------------------------------------------
def build_producer(args) -> "KafkaProducer":
    return KafkaProducer(
        bootstrap_servers=[s.strip() for s in args.bootstrap_servers.split(",")],
        value_serializer=lambda v: json.dumps(v, ensure_ascii=True).encode("utf-8"),
        key_serializer=lambda v: v.encode("utf-8") if v else None,
        linger_ms=args.linger_ms,
        acks=args.acks,
        request_timeout_ms=10000,
        max_block_ms=10000,
        retries=0,
        api_version=args.api_version,
    )


def send_event(producer, topic: str, event: Dict, dry_run: bool,
               delay: float) -> None:
    if dry_run:
        print(json.dumps(event, ensure_ascii=True), flush=True)
    else:
        try:
            future = producer.send(topic, key=event["insuredId"], value=event)
            m = future.get(timeout=10)
            label = event.get("anomaly_type") or "normal"
            print(f"  → {m.topic}:{m.partition}@{m.offset}"
                  f"  [{label:15s}]  {event['action']!r}", flush=True)
        except Exception as exc:
            print(f"  send failed: {exc}", file=sys.stderr, flush=True)
    if delay > 0:
        time.sleep(delay)

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def parse_api_version(v):
    try:
        return tuple(int(x) for x in v.split("."))
    except ValueError:
        raise argparse.ArgumentTypeError("api-version must be like 3.7.0")


def parse_args(argv):
    p = argparse.ArgumentParser(
        description="Send labeled anomalous + normal sessions to Kafka for model validation")
    p.add_argument("--bootstrap-servers",  default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    p.add_argument("--topic",              default="topic-audit-trail")
    p.add_argument("--samples-per-type",   type=int, default=50,
                   help="Sessions per anomaly type. Same count sent as normal. Default: 50")
    p.add_argument("--rate",               type=float, default=5.0,
                   help="Events per second (default: 5)")
    p.add_argument("--seed",               type=int, default=42)
    p.add_argument("--dry-run",            action="store_true",
                   help="Print events to stdout instead of sending to Kafka")
    p.add_argument("--anomaly-only",       action="store_true",
                   help="Skip normal sessions, send only anomalous ones")
    p.add_argument("--type",               choices=ANOMALY_TYPES + ["all"], default="all",
                   help="Send only one specific anomaly type (default: all)")
    p.add_argument("--api-version",        type=parse_api_version, default=(3, 7, 0))
    p.add_argument("--acks",               type=int, choices=[0, 1, -1], default=1)
    p.add_argument("--linger-ms",          type=int, default=0)
    return p.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    rng = random.Random(args.seed)

    types_to_run = ANOMALY_TYPES if args.type == "all" else [args.type]
    N = args.samples_per_type
    delay = max(0.0, 1.0 / args.rate) if args.rate > 0 else 0.0

    producer = None
    if not args.dry_run:
        if KafkaProducer is None:
            print("kafka-python required: pip install kafka-python", file=sys.stderr)
            return 2
        try:
            producer = build_producer(args)
        except Exception as exc:
            print(f"Kafka producer error: {exc}", file=sys.stderr)
            return 2

    normal_total = N * len(types_to_run)
    print(
        f"\n{'DRY-RUN ' if args.dry_run else ''}Starting anomaly test generator",
        file=sys.stderr, flush=True)
    print(
        f"  Anomalous: {N} sessions × {len(types_to_run)} type(s) = "
        f"{N * len(types_to_run)} sessions",
        file=sys.stderr, flush=True)
    if not args.anomaly_only:
        print(f"  Normal:    {normal_total} sessions", file=sys.stderr, flush=True)
    print(f"  Topic:     {args.topic}", file=sys.stderr, flush=True)
    print(f"  Rate:      {args.rate} events/sec\n", file=sys.stderr, flush=True)

    base_time = datetime(2026, 6, 15, 10, 0, 0, tzinfo=timezone.utc)
    counts: Dict[str, int] = defaultdict(int)

    # ── Anomalous sessions ────────────────────────────────────────────────────
    for atype in types_to_run:
        builder = ANOMALY_BUILDERS[atype]
        print(f"[{atype}] Sending {N} sessions...", file=sys.stderr, flush=True)
        for _ in range(N):
            user = random_user(rng)
            for event in builder(user, base_time, rng):
                send_event(producer, args.topic, event, args.dry_run, delay)
                counts[atype] += 1
            base_time += timedelta(minutes=rng.randint(5, 30))

    # ── Normal sessions ───────────────────────────────────────────────────────
    if not args.anomaly_only:
        print(f"\n[normal] Sending {normal_total} sessions...", file=sys.stderr, flush=True)
        for _ in range(normal_total):
            user = random_user(rng)
            for event in build_normal_session(user, base_time, rng):
                send_event(producer, args.topic, event, args.dry_run, delay)
                counts["normal"] += 1
            base_time += timedelta(minutes=rng.randint(2, 15))

    if producer:
        producer.flush(timeout=15)
        producer.close(timeout=10)

    total = sum(counts.values())
    print(f"\n{'='*50}", file=sys.stderr)
    print(f"Done — {total} events sent to '{args.topic}':", file=sys.stderr)
    for label in sorted(counts):
        bar = "█" * min(40, counts[label] // max(1, total // 400))
        print(f"  {label:16s} {counts[label]:5d}  {bar}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
