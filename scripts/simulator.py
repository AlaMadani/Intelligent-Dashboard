#!/usr/bin/env python3
import argparse
import json
import os
import random
import re
import sys
import time
import uuid
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Optional, Tuple

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


@dataclass(frozen=True)
class ActionDef:
    value: str
    type_name: str
    sub_type: str
    excludes: Tuple[str, ...]
    source: str


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


def device_type_from_user_agent(user_agent: str) -> str:
    ua = user_agent.lower()
    if ua.startswith("mobileapp"):
        if "android" in ua:
            return "MOBILE_ANDROID"
        return "MOBILE_IOS"
    return "WEB"


def random_ip(rng: random.Random) -> str:
    pools = [
        (10, rng.randint(0, 255), rng.randint(0, 255), rng.randint(1, 254)),
        (172, rng.randint(16, 31), rng.randint(0, 255), rng.randint(1, 254)),
        (192, 168, rng.randint(0, 255), rng.randint(1, 254)),
        (80, rng.randint(0, 255), rng.randint(0, 255), rng.randint(1, 254)),
    ]
    pick = pools[rng.randint(0, len(pools) - 1)]
    return ".".join(str(part) for part in pick)


def random_user_agent(rng: random.Random) -> str:
    mobile = rng.random() < 0.45
    if mobile:
        os_name = "android" if rng.random() < 0.55 else "ios"
        version = f"{rng.randint(1, 6)}.{rng.randint(0, 9)}.{rng.randint(0, 9)}"
        return f"mobileapp/{version} ({os_name})"

    browsers = [
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_4) AppleWebKit/605.1.15 "
        "(KHTML, like Gecko) Version/16.5 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
    ]
    return browsers[rng.randint(0, len(browsers) - 1)]


def build_request_data(action: ActionDef, insured_id: Optional[str], rng: random.Random) -> Optional[str]:
    if action.type_name == "LOGGING_ACTIONS":
        payload = {
            "login": insured_id or f"user{rng.randint(1000, 9999)}",
            "channel": "mobile" if rng.random() < 0.4 else "web",
        }
    elif action.type_name == "CONTACT_ACTIONS":
        payload = {
            "subject": action.value,
            "message": "Demande d'information",
            "priority": rng.choice(["low", "normal", "high"]),
        }
    elif action.type_name == "BANKING_ACTIONS":
        payload = {
            "iban_last4": f"{rng.randint(1000, 9999)}",
            "bic": rng.choice(["AGRIFRPP", "BNPAFRPP", "SOGEFRPP"]),
        }
    elif action.type_name in ("INSURED_ACTIONS", "OPEN_ACTIONS"):
        payload = {
            "insuredId": insured_id,
            "action": action.value,
        }
    elif action.type_name == "DOCUMENT_ACTIONS":
        payload = {
            "document": action.value,
            "tag": rng.choice(["medical", "administrative", "other"]),
        }
    else:
        payload = {"action": action.value}
    return json.dumps(payload, ensure_ascii=True)


def build_request_return(status: str, rng: random.Random, action: ActionDef) -> Optional[str]:
    if status == "OK":
        if rng.random() < 0.2:
            payload = {"status": "success", "reference": f"REF-{rng.randint(100000, 999999)}"}
            return json.dumps(payload, ensure_ascii=True)
        return None

    if action.type_name == "CONTACT_ACTIONS" and rng.random() < 0.5:
        return "SubTheme should not be empty !"

    payload = {"status": "error", "code": rng.choice(["AUTH_FAILED", "VALIDATION_ERROR", "SERVER_ERROR"])}
    return json.dumps(payload, ensure_ascii=True)


def choose_http_code(status: str, rng: random.Random) -> str:
    if status == "OK":
        return str(rng.choice([200, 201, 204]))
    return str(rng.choice([400, 401, 403, 404, 409, 422, 500]))


def refund_action_from_document(rng: random.Random) -> Tuple[str, str]:
    labels = [
        ("Consultation", "consultation", "Sante"),
        ("Pharmacie", "pharmacy", "Sante"),
        ("Hospitalisation", "hospitalization", "Sante"),
        ("Optique", "optical", "Optique"),
        ("Dentaire", "dental", "Dentaire"),
    ]
    label, label_en, tag = labels[rng.randint(0, len(labels) - 1)]
    action = f"Demande de remboursement {label} - Categorie {tag}"
    sub_type = "refund_" + re.sub(r"\s+", "_", label_en.strip().lower())
    return action, sub_type


def generate_id_list(
    rng: random.Random,
    pool: List[int],
    max_len: int = 1,
    empty_prob: float = 0.05,
) -> List[int]:
    if rng.random() < empty_prob:
        return []
    size = rng.randint(1, max_len)
    return rng.sample(pool, k=min(size, len(pool)))


class SessionState:
    def __init__(self, rng: random.Random, insured_ids: List[str]) -> None:
        self.rng = rng
        self.insured_ids = insured_ids
        self.session_by_insured: Dict[str, str] = {}

    def choose_insured(self) -> Optional[str]:
        if self.rng.random() < 0.05:
            return None
        return self.insured_ids[self.rng.randint(0, len(self.insured_ids) - 1)]

    def choose_session(self, insured_id: Optional[str], action_value: str) -> Optional[str]:
        if insured_id is None:
            return None

        normalized = unicodedata.normalize("NFKD", action_value).encode("ascii", "ignore").decode("ascii").lower()
        if any(token in normalized for token in ["connexion", "login", "sso"]):
            session_id = str(self.rng.randint(100000, 9999999))
            self.session_by_insured[insured_id] = session_id
            return session_id

        if "deconnexion" in normalized or "logout" in normalized:
            return self.session_by_insured.pop(insured_id, None)

        if insured_id in self.session_by_insured and self.rng.random() < 0.85:
            return self.session_by_insured[insured_id]

        session_id = str(self.rng.randint(100000, 9999999))
        self.session_by_insured[insured_id] = session_id
        return session_id


def apply_excludes(event: Dict[str, object], excludes: Tuple[str, ...]) -> Dict[str, object]:
    for exclude in excludes:
        field = AUDIT_TRAIL_VARIABLE_TO_FIELD.get(exclude)
        if field and field in event:
            event[field] = None
    return event


def prune_nones(event: Dict[str, object]) -> Dict[str, object]:
    return {k: v for k, v in event.items() if v is not None}


def format_created_at(now: datetime, mode: str) -> str:
    if mode == "java":
        return now.strftime("%Y-%m-%dT%H:%M:%S")
    return now.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def generate_event(
    action_def: ActionDef,
    rng: random.Random,
    state: SessionState,
    pools: Dict[str, List[int]],
    created_at_mode: str,
) -> Dict[str, object]:
    insured_id = state.choose_insured()
    user_agent = random_user_agent(rng)
    device = device_type_from_user_agent(user_agent)
    ip = random_ip(rng)
    action_value = action_def.value
    type_name = action_def.type_name
    sub_type = action_def.sub_type

    if action_value == "Envoi d'un document":
        action_value, sub_type = refund_action_from_document(rng)
        type_name = "REFUND_ACTIONS"
    if action_value.startswith("Demande de remboursement"):
        type_name = "REFUND_ACTIONS"
    if sub_type == "":
        sub_type = None

    status = "OK" if rng.random() < 0.9 else "KO"
    http_code = choose_http_code(status, rng)
    session_id = state.choose_session(insured_id, action_value)
    now = datetime.now(timezone.utc).replace(microsecond=0)
    created_at = format_created_at(now, created_at_mode)

    company_ids = generate_id_list(rng, pools["company_id"])
    company_group_ids = generate_id_list(rng, pools["company_group_id"])
    company_section_ids = generate_id_list(rng, pools["company_section_id"])
    insurer_ids = generate_id_list(rng, pools["insurer_id"])
    insurer_code_ids = generate_id_list(rng, pools["insurer_code_id"])
    healthcare_ids = generate_id_list(rng, pools["healthcare_network_id"])
    domain_ids = generate_id_list(rng, pools["domain_id"])
    environment_ids = generate_id_list(rng, pools["environment_id"], max_len=1, empty_prob=0.02)
    environment_id = str(environment_ids[0]) if environment_ids else None

    request_data = build_request_data(action_def, insured_id, rng)
    request_return = build_request_return(status, rng, action_def)

    event = {
        "id": str(uuid.uuid4()),
        "insuredId": insured_id,
        "status": status,
        "sessionId": session_id,
        "action": action_value,
        "httpCode": http_code,
        "ip": ip,
        "userAgent": user_agent,
        "requestData": request_data,
        "requestReturn": request_return,
        "createdAt": created_at,
        "type": type_name,
        "environmentId": environment_id,
        "device": device,
        "companyIdList": company_ids,
        "companyGroupIdList": company_group_ids,
        "insurerIdList": insurer_ids,
        "companySectionIdList": company_section_ids,
        "insurerCodeIdList": insurer_code_ids,
        "healthcareNetworkIdList": healthcare_ids,
        "domainIdList": domain_ids,
        "subType": sub_type,
    }
    event = apply_excludes(event, action_def.excludes)
    return prune_nones(event)


def pick_action(actions: List[ActionDef], rng: random.Random) -> ActionDef:
    type_weights = {
        "LOGGING_ACTIONS": 0.25,
        "INSURED_ACTIONS": 0.25,
        "CONTACT_ACTIONS": 0.15,
        "BANKING_ACTIONS": 0.10,
        "DOCUMENT_ACTIONS": 0.10,
        "REFUND_ACTIONS": 0.10,
        "OPEN_ACTIONS": 0.04,
        "BACKOFFICE_ACTIONS": 0.01,
    }
    by_type: Dict[str, List[ActionDef]] = {}
    for action in actions:
        by_type.setdefault(action.type_name, []).append(action)

    weighted_types = [(type_name, weight) for type_name, weight in type_weights.items() if by_type.get(type_name)]
    if not weighted_types:
        return actions[rng.randint(0, len(actions) - 1)]

    total = sum(weight for _, weight in weighted_types)
    pick = rng.random() * total
    upto = 0.0
    for type_name, weight in weighted_types:
        upto += weight
        if upto >= pick:
            candidates = by_type[type_name]
            return candidates[rng.randint(0, len(candidates) - 1)]

    return actions[rng.randint(0, len(actions) - 1)]


def build_pools(_: random.Random) -> Dict[str, List[int]]:
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


def build_insured_ids(rng: random.Random, count: int) -> List[str]:
    ids = ["14097905"]
    for _ in range(count):
        ids.append(f"{rng.randint(10000000, 99999999)}")
    return ids


def parse_api_version(value: str) -> Tuple[int, ...]:
    parts = [part.strip() for part in value.split(".") if part.strip()]
    if not parts:
        raise argparse.ArgumentTypeError("--api-version must look like 3.7.0")
    try:
        return tuple(int(part) for part in parts)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("--api-version must contain only integers") from exc


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="AuditTrail Kafka simulator")
    parser.add_argument("--bootstrap-servers", default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--rate", type=float, default=1.0, help="events per second (average)")
    parser.add_argument("--duration", type=int, default=0, help="seconds to run (0 = infinite)")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--project-root", default=os.getcwd())
    parser.add_argument("--dry-run", action="store_true", help="print events to stdout instead of Kafka")
    parser.add_argument("--insured-count", type=int, default=200)
    parser.add_argument("--pattern", choices=["steady", "bursty"], default="bursty")
    parser.add_argument("--created-at-format", choices=["java", "kibana"], default="kibana")
    parser.add_argument("--api-version", type=parse_api_version, default=parse_api_version("3.7.0"))
    parser.add_argument("--acks", type=int, choices=[0, 1, -1], default=1)
    parser.add_argument("--linger-ms", type=int, default=0)
    parser.add_argument("--request-timeout-ms", type=int, default=10000)
    parser.add_argument("--max-block-ms", type=int, default=10000)
    parser.add_argument("--retries", type=int, default=0)
    parser.add_argument(
        "--async-send",
        action="store_true",
        help="use async send callbacks instead of waiting for the broker ack on each event",
    )
    return parser.parse_args(argv)


def jitter_sleep(base: float, pattern: str, rng: random.Random) -> None:
    if base <= 0:
        return
    if pattern == "steady":
        time.sleep(base)
        return
    jitter = rng.uniform(0.6, 1.6)
    if rng.random() < 0.02:
        time.sleep(rng.uniform(4.0, 12.0))
    time.sleep(base * jitter)


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


def main(argv: List[str]) -> int:
    args = parse_args(argv)
    rng = random.Random(args.seed)
    actions = scan_action_tracking(args.project_root)
    if not actions:
        actions = [
            ActionDef(value="Connexion", type_name="LOGGING_ACTIONS", sub_type="logging_login", excludes=(), source="fallback"),
            ActionDef(value="Déconnexion", type_name="LOGGING_ACTIONS", sub_type="logging_logout", excludes=(), source="fallback"),
            ActionDef(value="Envoi d'un document", type_name="DOCUMENT_ACTIONS", sub_type="", excludes=(), source="fallback"),
            ActionDef(value="Envoi d'un message", type_name="CONTACT_ACTIONS", sub_type="", excludes=(), source="fallback"),
        ]

    pools = build_pools(rng)
    insured_ids = build_insured_ids(rng, args.insured_count)
    state = SessionState(rng, insured_ids)
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

    start = time.monotonic()

    try:
        while True:
            action_def = pick_action(actions, rng)
            event = generate_event(action_def, rng, state, pools, args.created_at_format)
            key = str(event.get("insuredId") or uuid.uuid4())

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
                        future.add_errback(
                            lambda exc: print(f"send failed: {exc}", file=sys.stderr, flush=True)
                        )
                    else:
                        metadata = future.get(timeout=max(1, args.request_timeout_ms // 1000))
                        print(
                            f"sent to {metadata.topic} partition {metadata.partition} offset {metadata.offset}",
                            flush=True,
                        )
                except Exception as exc:
                    print(f"send failed: {exc}", file=sys.stderr, flush=True)

            if args.duration > 0:
                elapsed = time.monotonic() - start
                if elapsed >= args.duration:
                    break

            if args.rate > 0:
                base = 1.0 / args.rate
                jitter_sleep(base, args.pattern, rng)

    except KeyboardInterrupt:
        print("stopped by user", file=sys.stderr, flush=True)
    finally:
        close_producer(producer)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))