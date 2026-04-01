#!/usr/bin/env python3
"""
simulator_final_revised.py
===========================
Real-time Kafka simulator with route-aware user journeys.

Sends realistic audit-trail events to topic-audit-trail.
All events include countryCode + city so Spring Boot can encode
country_id correctly for the ML feature vector.

Usage (normal):
    python simulator_final_revised.py --bootstrap-servers localhost:9092

Usage (with anomaly injection ~25% sessions):
    python simulator_final_revised.py --bootstrap-servers localhost:9092 --anomaly-mode
"""
import argparse
import json
import math
import os
import random
import sys
import time
import uuid
from collections import deque, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Deque, Dict, List, Optional, Tuple

try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

# ---------------------------------------------------------------------------
# Shared action catalog  (43 actions — matches training vocab exactly)
# ---------------------------------------------------------------------------
_ALL_ACTIONS: List[Tuple[str, str, str, str]] = [
    ("Connexion",                                                                                   "LOGGING_ACTIONS",    "logging_login",                                    "auth"),
    ("Connexion SSO",                                                                               "LOGGING_ACTIONS",    "logging_login_sso",                               "auth"),
    ("Connexion en tant que",                                                                       "LOGGING_ACTIONS",    "logging_login_as_insured",                        "auth"),
    ("Activation de compte",                                                                        "LOGGING_ACTIONS",    "logging_account_activation",                      "auth"),
    ("Création de compte",                                                                          "LOGGING_ACTIONS",    "logging_account_creation",                        "auth"),
    ("Demande de réinitialisation de mot de passe",                                                 "LOGGING_ACTIONS",    "",                                                "auth"),
    ("Sélection email MFA",                                                                         "LOGGING_ACTIONS",    "logging_mfa_email_selection",                     "auth"),
    ("Validation MFA",                                                                              "LOGGING_ACTIONS",    "logging_mfa_validation",                          "auth"),
    ("Régénération code MFA",                                                                       "LOGGING_ACTIONS",    "logging_mfa_regeneration",                        "auth"),
    ("Déconnexion",                                                                                 "LOGGING_ACTIONS",    "logging_logout",                                  "logout"),
    ("SSO Disconnect",                                                                              "LOGGING_ACTIONS",    "",                                                "logout"),
    ("Changement de Mot de passe",                                                                  "LOGGING_ACTIONS",    "logging_password_change",                         "personal_info"),
    ("Changer ses informations personnel",                                                          "OPEN_ACTIONS",       "update_personal_information",                     "personal_info"),
    ("Changer les coordonnées bancaire",                                                            "BANKING_ACTIONS",    "",                                                "banking_info"),
    ("Signature électronique d'un mandat sepa",                                                     "INSURED_ACTIONS",    "sign_sepa_mandate",                               "banking_info"),
    ("Changer les coordonnées bancaire, changement de RIB pour les cotisations",                    "BANKING_ACTIONS",    "update_bank_details_contributions",               "banking_info"),
    ("Changer les coordonnées bancaire, changement de RIB de bénéficiaire",                         "BANKING_ACTIONS",    "update_bank_details_beneficiary",                 "banking_info"),
    ("Changer les coordonnées bancaire, changement de RIB pour les remboursements",                 "BANKING_ACTIONS",    "update_bank_details_refunds",                     "banking_info"),
    ("Exporter remboursement",                                                                      "INSURED_ACTIONS",    "export_refund",                                   "home"),
    ("Télécharger un décompte",                                                                     "INSURED_ACTIONS",    "download_refund_statement",                       "home"),
    ("Télécharger le certificat d'adhésion",                                                        "INSURED_ACTIONS",    "download_membership_certificate",                 "home"),
    ("Ajout de la carte tiers payant dans le wallet",                                               "INSURED_ACTIONS",    "add_third_party_card_to_wallet_ios",               "home"),
    ("Envoi d'un document",                                                                         "DOCUMENT_ACTIONS",   "",                                                "documents"),
    ("Ouverture d'un document contractuel",                                                         "INSURED_ACTIONS",    "open_contract_document",                          "documents"),
    ("Partager un justificatif du PACS",                                                            "DOCUMENT_ACTIONS",   "share_pacs_proof_document",                       "documents"),
    ("Partager un justificatif sur l'honneur de vie commune",                                       "DOCUMENT_ACTIONS",   "share_cohabitation_affidavit",                    "documents"),
    ("Changer l'organisme de rattachement sécu. Social",                                            "OPEN_ACTIONS",       "change_social_security_provider",                 "preferences"),
    ("Renvoi carte TP papier",                                                                      "INSURED_ACTIONS",    "",                                                "preferences"),
    ("Envoi carte TP par mail",                                                                     "OPEN_ACTIONS",       "send_third_party_card_by_email",                  "preferences"),
    ("Téléchargement de la carte de tiers payant",                                                  "INSURED_ACTIONS",    "download_third_party_card",                       "preferences"),
    ("Téléchargement carte TP",                                                                     "INSURED_ACTIONS",    "download_third_party_card",                       "tp_card"),
    ("Envoi d'un message",                                                                          "CONTACT_ACTIONS",    "contact_send_message",                            "requests"),
    ("Envoi d'une réclamation",                                                                     "CONTACT_ACTIONS",    "contact_submit_complaint",                        "requests"),
    ("Contactez nous : via message (Mes remboursements)",                                           "CONTACT_ACTIONS",    "contact_message_my_refunds",                      "requests"),
    ("Contactez nous : via message (Mes prises en charge/devis)",                                   "CONTACT_ACTIONS",    "contact_message_my_coverage_quotes",              "requests"),
    ("Contactez nous : via message (Mes garanties)",                                                "CONTACT_ACTIONS",    "contact_message_my_coverage",                     "requests"),
    ("Contactez nous : via message (Ma carte tiers-payant)",                                        "CONTACT_ACTIONS",    "contact_message_my_third_party_card",             "home"),
    ("Contactez nous : via message (Mon adhésion, Autres)",                                         "CONTACT_ACTIONS",    "contact_message_my_membership_other",             "home"),
    ("Contactez nous : via message (Mes cotisations)",                                              "CONTACT_ACTIONS",    "contact_message_my_contributions",                "banking_info"),
    ("Contactez nous : via message (Ma télétransmission - rattachement automatique avec votre Régime Obligatoire)", "CONTACT_ACTIONS", "contact_message_my_teletransmission", "preferences"),
    ("demander un renvoi par mail d'échéancier",                                                    "CONTACT_ACTIONS",    "request_payment_schedule_by_email",               "documents"),
    ("envoi d'une demande de résiliation",                                                          "CONTACT_ACTIONS",    "request_cancellation_by_message",                 "preferences"),
    ("Suppression liaison suite à suppression de l'entité",                                         "BACKOFFICE_ACTIONS", "suppression_liaison_suite_a_suppression_d_entite","backoffice"),
]

@dataclass(frozen=True)
class RouteAction:
    value: str
    type_name: str
    sub_type: str
    route: str

ALL_ACTIONS: List[RouteAction] = [RouteAction(*t) for t in _ALL_ACTIONS]
ACTION_BY_VALUE: Dict[str, RouteAction] = {a.value: a for a in ALL_ACTIONS}
ACTIONS_BY_ROUTE: Dict[str, List[RouteAction]] = defaultdict(list)
for _a in ALL_ACTIONS:
    ACTIONS_BY_ROUTE[_a.route].append(_a)

# ---------------------------------------------------------------------------
# Session flow templates
# ---------------------------------------------------------------------------
ROUTE_WEIGHTS = [
    ("home",          0.30), ("requests",      0.22), ("documents",    0.16),
    ("banking_info",  0.13), ("personal_info", 0.06), ("beneficiaries",0.05),
    ("preferences",   0.04), ("tp_card",        0.03), ("backoffice",   0.01),
]
ROUTE_DEPTH_WEIGHTS = [(1, 0.50), (2, 0.35), (3, 0.15)]

_LOGIN_FLOWS = [
    (["Connexion"],                                                      0.55),
    (["Connexion", "Validation MFA"],                                    0.15),
    (["Connexion SSO"],                                                   0.12),
    (["Connexion", "Sélection email MFA", "Validation MFA"],             0.07),
    (["Connexion", "Régénération code MFA", "Validation MFA"],           0.04),
    (["Connexion en tant que"],                                           0.04),
    (["Activation de compte"],                                            0.02),
    (["Création de compte"],                                              0.01),
]
_LOGOUT_FLOWS = [("Déconnexion", 0.76), ("SSO Disconnect", 0.24)]

# ---------------------------------------------------------------------------
# Geo / device constants
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
    ("WEB",            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123.0.0.0 Safari/537.36"),
    ("WEB",            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 Version/17.4 Safari/605.1.15"),
    ("MOBILE_ANDROID", "mobileapp/4.2.1 (android; sdk=34; locale=fr-FR)"),
    ("MOBILE_ANDROID", "mobileapp/4.1.7 (android; sdk=33; locale=fr-FR)"),
    ("MOBILE_IOS",     "mobileapp/4.3.0 (ios; build=812; locale=fr-FR)"),
]

# ---------------------------------------------------------------------------
# User state
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
    session_id: Optional[str] = None
    connected: bool = False
    current_route: str = "auth"
    prev_action: str = ""
    action_queue: Deque[Tuple[RouteAction, str]] = field(default_factory=deque)
    last_event_at: Optional[datetime] = None
    actions_in_session: int = 0

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


def random_public_ip(prefix, rng):
    parts = [int(p) for p in prefix]
    while len(parts) < 4:
        parts.append(rng.randint(2, 250) if len(parts) == 3 else rng.randint(0, 255))
    if parts[0] in {10, 127, 169, 172, 192}:
        parts[0] = rng.choice([2,37,46,62,77,80,81,82,83,84,85,86,87,88,89,90,91,93,95])
    return ".".join(str(p) for p in parts[:4])


def format_ts(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def maybe_rotate_ip(user: UserProfile, rng: random.Random) -> None:
    if user.device.startswith("MOBILE") and rng.random() < 0.10:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)
    elif user.device == "WEB" and rng.random() < 0.03:
        user.current_ip = random_public_ip(user.home_ip_prefix, rng)


def choose_status(route: str, rng: random.Random) -> str:
    failure_rates = {
        "auth": 0.03, "logout": 0.01, "banking_info": 0.08,
        "documents": 0.05, "requests": 0.05, "beneficiaries": 0.04,
        "personal_info": 0.03, "home": 0.02, "preferences": 0.03, "tp_card": 0.02,
    }
    return "KO" if rng.random() < failure_rates.get(route, 0.04) else "OK"


def choose_http_code(status, route, action_value, rng):
    if status == "OK":
        if action_value == "Connexion":
            return str(rng.choice([200, 202, 206, 208]))
        if action_value in ("Connexion SSO", "Connexion en tant que"):
            return "200"
        if route in ("documents", "requests", "beneficiaries", "banking_info"):
            return str(rng.choice([200, 201, 202]))
        return str(rng.choice([200, 204]))
    failure_codes = {
        "auth": [401, 403],
        "banking_info": [400, 409, 422],
        "requests": [400, 422],
        "documents": [400, 422],
    }
    return str(rng.choice(failure_codes.get(route, [400, 403, 409, 422, 500])))


def build_request_data(action: RouteAction, user: UserProfile, rng: random.Random) -> str:
    r = action.route
    if r == "auth":
        return json.dumps({"login": user.insured_id,
                           "channel": "mobile" if user.device.startswith("MOBILE") else "web",
                           "country": user.country_code})
    if r == "banking_info":
        return json.dumps({"iban_last4": str(rng.randint(1000, 9999)),
                           "bic": rng.choice(["AGRIFRPP","BNPAFRPP","SOGEFRPP","CMCIFRPP"])})
    if "CONTACT" in action.type_name:
        return json.dumps({"subject": action.value,
                           "message": rng.choice(["Besoin d'assistance","Question remboursement","Demande info"]),
                           "priority": rng.choice(["low","normal","normal","high"])})
    if r == "documents":
        return json.dumps({"document": action.value,
                           "tag": rng.choice(["medical","administrative","identity"])})
    if r in ("refunds", "home") and "remboursement" in action.value.lower():
        return json.dumps({"action": action.value,
                           "claimAmount": round(rng.uniform(18, 240), 2),
                           "currency": "EUR"})
    return json.dumps({"action": action.value})


def gen_id_list(base, rng, miss=0.10):
    return [base] if rng.random() >= miss else []

# ---------------------------------------------------------------------------
# Session flow builder
# ---------------------------------------------------------------------------
def plan_new_session(user: UserProfile, rng: random.Random, anomaly_mode: bool) -> None:
    user.action_queue.clear()
    user.session_id = str(rng.randint(100000, 9999999))
    user.connected = False
    user.actions_in_session = 0

    if anomaly_mode and rng.random() < 0.25:
        _inject_anomaly_session(user, rng)
        return

    # Normal: login → routes → logout
    login_values = weighted_choice(_LOGIN_FLOWS, rng)
    for v in login_values:
        user.action_queue.append((ACTION_BY_VALUE[v], "auth"))

    for _ in range(rng.randint(1, 4)):
        route = weighted_choice(ROUTE_WEIGHTS, rng)
        pool = ACTIONS_BY_ROUTE.get(route, [])
        if not pool:
            continue
        depth = min(weighted_choice(ROUTE_DEPTH_WEIGHTS, rng), len(pool))
        for act in rng.sample(pool, depth):
            user.action_queue.append((act, route))

    logout_value = weighted_choice(_LOGOUT_FLOWS, rng)
    user.action_queue.append((ACTION_BY_VALUE[logout_value], "logout"))


def _inject_anomaly_session(user: UserProfile, rng: random.Random) -> None:
    """Inject an anomalous session for Spring Boot anomaly pipeline testing."""
    pattern = rng.choice(["out_of_order", "double_login", "post_logout", "wrong_route"])

    if pattern == "out_of_order":
        pool = ACTIONS_BY_ROUTE.get("documents", []) + ACTIONS_BY_ROUTE.get("requests", [])
        if pool:
            act = rng.choice(pool)
            user.action_queue.append((act, act.route))
        user.action_queue.append((ACTION_BY_VALUE["Connexion"], "auth"))

    elif pattern == "double_login":
        user.action_queue.append((ACTION_BY_VALUE["Connexion"], "auth"))
        user.action_queue.append((ACTION_BY_VALUE["Connexion"], "auth"))
        if ACTIONS_BY_ROUTE.get("home"):
            user.action_queue.append((rng.choice(ACTIONS_BY_ROUTE["home"]), "home"))
        user.action_queue.append((ACTION_BY_VALUE["Déconnexion"], "logout"))

    elif pattern == "post_logout":
        user.action_queue.append((ACTION_BY_VALUE["Connexion"], "auth"))
        user.action_queue.append((ACTION_BY_VALUE["Déconnexion"], "logout"))
        if ACTIONS_BY_ROUTE.get("documents"):
            act = rng.choice(ACTIONS_BY_ROUTE["documents"])
            user.action_queue.append((act, act.route))

    elif pattern == "wrong_route":
        user.action_queue.append((ACTION_BY_VALUE["Connexion"], "auth"))
        if ACTIONS_BY_ROUTE.get("banking_info"):
            act = rng.choice(ACTIONS_BY_ROUTE["banking_info"])
            wrong = RouteAction(act.value, act.type_name, act.sub_type, "home")
            user.action_queue.append((wrong, "home"))
        user.action_queue.append((ACTION_BY_VALUE["Déconnexion"], "logout"))

# ---------------------------------------------------------------------------
# Event generation
# ---------------------------------------------------------------------------
def generate_event(action: RouteAction, route: str, user: UserProfile,
                   rng: random.Random, created_at: datetime) -> Dict:
    maybe_rotate_ip(user, rng)
    status = choose_status(route, rng)
    http_code = choose_http_code(status, route, action.value, rng)

    event = {
        "id":                     str(uuid.uuid4()),
        "insuredId":              user.insured_id,
        "status":                 status,
        "sessionId":              user.session_id,
        "action":                 action.value,
        "httpCode":               http_code,
        "ip":                     user.current_ip,
        "userAgent":              user.user_agent,
        "requestData":            build_request_data(action, user, rng),
        "requestReturn":          json.dumps({"status":"success","ref":f"REF-{rng.randint(100000,999999)}"})
                                  if status == "OK" else json.dumps({"status":"error"}),
        "createdAt":              format_ts(created_at.astimezone(timezone.utc)),
        "type":                   action.type_name,
        "environmentId":          str(user.environment_id),
        "device":                 user.device,
        # ✅ FIX: countryCode and city are now included so Spring Boot can
        #         encode country_id correctly for the ML feature vector
        "countryCode":            user.country_code,
        "city":                   user.city,
        "companyIdList":          gen_id_list(user.company_id, rng),
        "companyGroupIdList":     gen_id_list(user.company_group_id, rng, 0.08),
        "insurerIdList":          gen_id_list(user.insurer_id, rng),
        "companySectionIdList":   gen_id_list(user.company_section_id, rng),
        "insurerCodeIdList":      gen_id_list(user.insurer_code_id, rng),
        "healthcareNetworkIdList":gen_id_list(user.healthcare_network_id, rng, 0.15),
        "domainIdList":           gen_id_list(user.domain_id, rng),
        "subType":                action.sub_type or "",
        "route":                  route,
        "prevAction":             user.prev_action,
    }

    # Update user state
    user.prev_action = action.value
    user.last_event_at = created_at
    user.actions_in_session += 1
    user.current_route = route

    if action.route == "auth" and action.value in (
        "Connexion", "Connexion SSO", "Connexion en tant que", "Activation de compte"
    ):
        user.connected = True
        user.actions_in_session = 0
    elif action.route == "logout":
        user.connected = False
        user.prev_action = ""

    return event

# ---------------------------------------------------------------------------
# Virtual clock
# ---------------------------------------------------------------------------
class VirtualClock:
    def __init__(self, start: datetime, step_seconds: float, rng: random.Random):
        self.current = start
        self.step = max(1.0, step_seconds)
        self.rng = rng

    def load_factor(self) -> float:
        m = self.current.hour * 60 + self.current.minute
        value = 0.18
        for peak, width in [(510, 90), (750, 60), (1080, 90)]:
            value += 0.42 * math.exp(-((m - peak) ** 2) / (2 * width ** 2))
        if self.current.weekday() >= 5:
            value *= 0.72
        return min(1.0, max(0.10, value))

    def advance(self) -> datetime:
        load = self.load_factor()
        jitter = self.rng.uniform(0.65, 1.35)
        seconds = self.step * jitter * (1.55 - load)
        self.current += timedelta(seconds=max(4.0, seconds))
        return self.current

# ---------------------------------------------------------------------------
# User pool
# ---------------------------------------------------------------------------
def build_users(rng: random.Random, count: int) -> List[UserProfile]:
    pools = {
        "co": [27011,27012,27013,27021], "cg": [12001,12002,12003],
        "cs": [110027,110028,110029],    "ins": [1,2,3],
        "ic": [302,303,304],             "hn": [1,2,3],
        "dom": [10,11,12],               "env": [10,11],
    }
    geo_items = [(cc, p["weight"]) for cc, p in EU_GEO_PROFILE.items()]
    users = []
    for _ in range(count):
        cc = weighted_choice(geo_items, rng)
        geo = EU_GEO_PROFILE[cc]
        prefix = rng.choice(geo["prefixes"])
        device, ua = rng.choice(BROWSER_POOL)
        u = UserProfile(
            insured_id=f"{rng.randint(10000000, 99999999)}",
            country_code=cc, city=rng.choice(geo["cities"]),
            current_ip=random_public_ip(prefix, rng),
            home_ip_prefix=prefix, device=device, user_agent=ua,
            company_id=rng.choice(pools["co"]),
            company_group_id=rng.choice(pools["cg"]),
            company_section_id=rng.choice(pools["cs"]),
            insurer_id=rng.choice(pools["ins"]),
            insurer_code_id=rng.choice(pools["ic"]),
            healthcare_network_id=rng.choice(pools["hn"]),
            domain_id=rng.choice(pools["dom"]),
            environment_id=rng.choice(pools["env"]),
        )
        plan_new_session(u, rng, anomaly_mode=False)
        users.append(u)
    return users


def choose_user(users: List[UserProfile], clock: VirtualClock,
                rng: random.Random) -> UserProfile:
    weighted = []
    for u in users:
        w = 1.0 + (0.5 if u.connected else 0.0)
        if u.last_event_at:
            idle = (clock.current - u.last_event_at).total_seconds()
            w += min(2.0, idle / 600.0)
        else:
            w += 1.5
        if u.country_code == "FR":
            w += 0.2
        if u.device.startswith("MOBILE") and 7 <= clock.current.hour <= 9:
            w += 0.2
        weighted.append((u, max(0.1, w)))
    return weighted_choice(weighted, rng)

# ---------------------------------------------------------------------------
# Kafka
# ---------------------------------------------------------------------------
def build_producer(args) -> "KafkaProducer":
    return KafkaProducer(
        bootstrap_servers=[s.strip() for s in args.bootstrap_servers.split(",")],
        value_serializer=lambda v: json.dumps(v, ensure_ascii=True).encode("utf-8"),
        key_serializer=lambda v: v.encode("utf-8") if v else None,
        linger_ms=args.linger_ms, acks=args.acks,
        request_timeout_ms=args.request_timeout_ms,
        max_block_ms=args.max_block_ms, retries=args.retries,
        api_version=args.api_version,
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

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def parse_api_version(v):
    try:
        return tuple(int(x) for x in v.split("."))
    except ValueError:
        raise argparse.ArgumentTypeError("--api-version must be like 3.7.0")


def parse_args(argv):
    p = argparse.ArgumentParser(description="Route-aware AuditTrail Kafka simulator")
    p.add_argument("--bootstrap-servers",  default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    p.add_argument("--topic",              default="topic-audit-trail")
    p.add_argument("--rate",               type=float, default=2.0, help="events/sec")
    p.add_argument("--duration",           type=int,   default=0,   help="seconds to run (0=infinite)")
    p.add_argument("--seed",               type=int,   default=None)
    p.add_argument("--dry-run",            action="store_true", help="print to stdout instead of Kafka")
    p.add_argument("--insured-count",      type=int,   default=50)
    p.add_argument("--virtual-day-start",  default="2026-04-01T07:30:00+01:00")
    p.add_argument("--virtual-speed",      type=float, default=120.0)
    p.add_argument("--anomaly-mode",       action="store_true",
                   help="inject ~25%% illogical sessions for model validation")
    p.add_argument("--api-version",        type=parse_api_version, default=(3, 7, 0))
    p.add_argument("--acks",               type=int, choices=[0, 1, -1], default=1)
    p.add_argument("--linger-ms",          type=int, default=0)
    p.add_argument("--request-timeout-ms", type=int, default=10000)
    p.add_argument("--max-block-ms",       type=int, default=10000)
    p.add_argument("--retries",            type=int, default=0)
    p.add_argument("--async-send",         action="store_true")
    return p.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    rng = random.Random(args.seed)

    try:
        virtual_start = datetime.fromisoformat(args.virtual_day_start)
        if virtual_start.tzinfo is None:
            virtual_start = virtual_start.replace(tzinfo=timezone.utc)
    except ValueError:
        print("--virtual-day-start must be ISO 8601", file=sys.stderr)
        return 2

    clock = VirtualClock(virtual_start, args.virtual_speed, rng)
    users = build_users(rng, args.insured_count)

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

    if args.anomaly_mode:
        print("⚠  anomaly-mode active — ~25% sessions contain illogical sequences",
              file=sys.stderr, flush=True)

    sent = 0
    wall_start = time.monotonic()
    try:
        while True:
            user = choose_user(users, clock, rng)

            if not user.action_queue:
                plan_new_session(user, rng, anomaly_mode=args.anomaly_mode)
            if not user.action_queue:
                continue

            action, route = user.action_queue.popleft()
            created_at = clock.advance()
            event = generate_event(action, route, user, rng, created_at)

            if args.dry_run:
                print(json.dumps(event, ensure_ascii=True), flush=True)
            else:
                try:
                    future = producer.send(args.topic, key=user.insured_id, value=event)
                    if args.async_send:
                        future.add_callback(
                            lambda m: print(f"→ {m.topic}:{m.partition}@{m.offset}", flush=True))
                        future.add_errback(
                            lambda e: print(f"send failed: {e}", file=sys.stderr, flush=True))
                    else:
                        m = future.get(timeout=max(1, args.request_timeout_ms // 1000))
                        print(f"→ {m.topic}:{m.partition}@{m.offset}"
                              f"  action={event['action']!r}"
                              f"  country={event['countryCode']}"
                              f"  route={route}", flush=True)
                except Exception as exc:
                    print(f"send failed: {exc}", file=sys.stderr, flush=True)

            sent += 1
            if args.duration > 0 and (time.monotonic() - wall_start) >= args.duration:
                break
            if args.rate > 0:
                time.sleep(max(0.0, 1.0 / args.rate))

    except KeyboardInterrupt:
        print(f"\nstopped — {sent} events sent", file=sys.stderr)
    finally:
        close_producer(producer)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
