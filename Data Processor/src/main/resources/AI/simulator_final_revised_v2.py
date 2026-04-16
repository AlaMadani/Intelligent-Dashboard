#!/usr/bin/env python3
"""
simulator_final_revised_v2.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Updated Kafka simulator aligned with simulator_yearly_dataset_v3.py.

Reads audit_trail_2025.csv (events) and uses the same architecture:
  • Loads backend-apis-actions.json and actions_order-v2.json
  • Replays or generates events matching the new schema with all enriched features
  • Sends to Kafka in real-time with configurable rate

Features:
  • Reads historical data from CSV
  • Replays sessions with time warping (virtual-start parameter)
  • Generates new synthetic sessions aligned with v3 anomaly types
  • Enriches events with all feature engineering (timeDelta, sessionLength, risk scores, etc.)
  • Supports both dry-run (stdout) and Kafka output
"""

import argparse
import csv
import json
import os
import random
import sys
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import uuid

try:
    from kafka import KafkaProducer
except Exception:
    KafkaProducer = None

import simulator_yearly_dataset_v3 as training_sim


DEFAULT_BACKEND_APIS = Path(__file__).with_name("backend-apis-actions.json")
DEFAULT_ACTIONS_ORDER = Path(__file__).with_name("actions_order-v2.json")


def parse_api_version(value: str):
    try:
        return tuple(int(part) for part in value.split("."))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("--api-version must be like 3.7.0") from exc


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Kafka simulator replaying/generating events aligned with simulator_yearly_dataset_v3"
    )
    parser.add_argument("--bootstrap-servers", default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--backend-apis", default=str(DEFAULT_BACKEND_APIS))
    parser.add_argument("--actions-order", default=str(DEFAULT_ACTIONS_ORDER))
    parser.add_argument("--input-csv", required=True, help="Path to audit_trail_2025.csv")
    parser.add_argument(
        "--mode",
        choices=["replay", "generate"],
        default="replay",
        help="replay: send CSV events to Kafka; generate: create new synthetic sessions",
    )
    parser.add_argument("--session-count", type=int, default=20, help="For generate mode: number of sessions to emit.")
    parser.add_argument("--insured-count", type=int, default=10, help="For generate mode: size of synthetic user pool.")
    parser.add_argument("--rate", type=float, default=4.0, help="Approximate event rate per second.")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true", help="Print JSON to stdout instead of Kafka.")
    parser.add_argument("--virtual-start", default=None, help="Retime events to start from this ISO 8601 timestamp.")
    parser.add_argument("--created-at-format", choices=["java", "kibana"], default="kibana")
    parser.add_argument("--api-version", type=parse_api_version, default=(3, 7, 0))
    parser.add_argument("--acks", type=int, choices=[0, 1, -1], default=1)
    parser.add_argument("--linger-ms", type=int, default=0)
    parser.add_argument("--request-timeout-ms", type=int, default=10000)
    parser.add_argument("--max-block-ms", type=int, default=10000)
    parser.add_argument("--retries", type=int, default=0)
    parser.add_argument("--async-send", action="store_true")
    return parser.parse_args(argv)


def build_producer(args) -> "KafkaProducer":
    return KafkaProducer(
        bootstrap_servers=[server.strip() for server in args.bootstrap_servers.split(",")],
        value_serializer=lambda value: json.dumps(value, ensure_ascii=True).encode("utf-8"),
        key_serializer=lambda value: value.encode("utf-8") if value else None,
        linger_ms=args.linger_ms,
        acks=args.acks,
        request_timeout_ms=args.request_timeout_ms,
        max_block_ms=args.max_block_ms,
        retries=args.retries,
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


def parse_virtual_start(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("--virtual-start must be ISO 8601") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def load_csv_events(csv_path: str) -> List[Dict]:
    events = []
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            events.append(row)
    return events


def retime_events(events: List[Dict], virtual_start: datetime) -> Tuple[List[Dict], datetime]:
    if not events:
        return events, virtual_start

    original_start = None
    for event in events:
        try:
            original_ts = datetime.fromisoformat(event.get("createdAt", "").replace("Z", "+00:00"))
            if original_start is None or original_ts < original_start:
                original_start = original_ts
        except Exception:
            pass

    if original_start is None:
        return events, virtual_start

    time_delta = virtual_start - original_start
    retimed_events = []
    for event in events:
        try:
            original_ts = datetime.fromisoformat(event.get("createdAt", "").replace("Z", "+00:00"))
            new_ts = original_ts + time_delta
            event_copy = dict(event)
            event_copy["createdAt"] = new_ts.isoformat().replace("+00:00", "Z")
            retimed_events.append(event_copy)
        except Exception:
            retimed_events.append(event)

    end_time = virtual_start + timedelta(days=30)
    return retimed_events, end_time


def parse_csv_event_to_kafka_event(row: Dict) -> Dict:
    event = {
        "id": row.get("id", str(uuid.uuid4())),
        "insuredId": row.get("insuredId", ""),
        "status": row.get("status", "OK"),
        "sessionId": row.get("sessionId", ""),
        "action": row.get("action", ""),
        "httpCode": row.get("httpCode", "200"),
        "ip": row.get("ip", "0.0.0.0"),
        "userAgent": row.get("userAgent", ""),
        "requestData": row.get("requestData", "{}"),
        "requestReturn": row.get("requestReturn"),
        "createdAt": row.get("createdAt", ""),
        "type": row.get("type", ""),
        "environmentId": row.get("environmentId", ""),
        "device": row.get("device", ""),
        "persona": row.get("persona", ""),
        "route": row.get("route", ""),
        "prevAction": row.get("prevAction", ""),
        "nextAction": row.get("nextAction", ""),
        "companyIdList": parse_id_list(row.get("companyIdList", "[]")),
        "companyGroupIdList": parse_id_list(row.get("companyGroupIdList", "[]")),
        "insurerIdList": parse_id_list(row.get("insurerIdList", "[]")),
        "companySectionIdList": parse_id_list(row.get("companySectionIdList", "[]")),
        "insurerCodeIdList": parse_id_list(row.get("insurerCodeIdList", "[]")),
        "healthcareNetworkIdList": parse_id_list(row.get("healthcareNetworkIdList", "[]")),
        "domainIdList": parse_id_list(row.get("domainIdList", "[]")),
        "subType": row.get("subType") or None,
        "countryCode": row.get("countryCode", "FR"),
        "city": row.get("city", ""),
        "month": row.get("month", ""),
        "sessionNumber": int(row.get("sessionNumber", "0")) if row.get("sessionNumber") else 0,
        "sequenceInSession": int(row.get("sequenceInSession", "0")) if row.get("sequenceInSession") else 0,
        "sessionLength": int(row.get("sessionLength", "0")) if row.get("sessionLength") else 0,
        "sessionDurationSeconds": int(row.get("sessionDurationSeconds", "0")) if row.get("sessionDurationSeconds") else 0,
        "timeDeltaSinceLastAction": int(row.get("timeDeltaSinceLastAction", "0")) if row.get("timeDeltaSinceLastAction") else 0,
        "hourOfDay": int(row.get("hourOfDay", "0")) if row.get("hourOfDay") else 0,
        "dayOfWeek": int(row.get("dayOfWeek", "0")) if row.get("dayOfWeek") else 0,
        "isWeekend": int(row.get("isWeekend", "0")) if row.get("isWeekend") else 0,
        "isIpChanged": int(row.get("isIpChanged", "0")) if row.get("isIpChanged") else 0,
        "uniqueIpsInSession": int(row.get("uniqueIpsInSession", "0")) if row.get("uniqueIpsInSession") else 0,
        "cumulativeKOs": int(row.get("cumulativeKOs", "0")) if row.get("cumulativeKOs") else 0,
        "longestKoStreak": int(row.get("longestKoStreak", "0")) if row.get("longestKoStreak") else 0,
        "hasLoggedIn": int(row.get("hasLoggedIn", "0")) if row.get("hasLoggedIn") else 0,
        "isDeviceChanged": int(row.get("isDeviceChanged", "0")) if row.get("isDeviceChanged") else 0,
        "uniqueDevicesInSession": int(row.get("uniqueDevicesInSession", "0")) if row.get("uniqueDevicesInSession") else 0,
        "isDownloadAction": int(row.get("isDownloadAction", "0")) if row.get("isDownloadAction") else 0,
        "downloadActionsInSession": int(row.get("downloadActionsInSession", "0")) if row.get("downloadActionsInSession") else 0,
        "downloadsLast2Minutes": int(row.get("downloadsLast2Minutes", "0")) if row.get("downloadsLast2Minutes") else 0,
        "pingPongCount": int(row.get("pingPongCount", "0")) if row.get("pingPongCount") else 0,
        "sessionRiskScore": float(row.get("sessionRiskScore", "0")) if row.get("sessionRiskScore") else 0.0,
        "is_anomaly": row.get("is_anomaly") == "1" or row.get("is_anomaly") == "true",
        "anomaly_type": row.get("anomaly_type", "normal"),
        "campaignId": row.get("campaignId") or None,
    }
    return {k: v for k, v in event.items() if v is not None}


def parse_id_list(value: str) -> List:
    if not value or value == "[]":
        return []
    try:
        return json.loads(value)
    except Exception:
        return []


def generate_synthetic_session(
    user: training_sim.UserProfile,
    month_start: datetime,
    month_end: datetime,
    catalog: Dict,
    rng: random.Random,
    session_number: int,
    anomaly_type: Optional[str] = None,
) -> List[Dict]:
    if rng.random() < 0.05:
        anomaly_type = rng.choice(training_sim.ANOMALY_TYPES) if anomaly_type is None else anomaly_type
        plans = training_sim.build_anomaly_session(
            user, month_start, month_end, catalog, rng, session_number, anomaly_type=anomaly_type
        )
    else:
        plans = training_sim.build_normal_session(
            user, month_start, month_end, catalog, rng, session_number
        )

    if not plans:
        return []

    session_length = len(plans)
    events = []
    prev_action = ""

    for index, plan in enumerate(plans):
        event = training_sim.build_event_record(
            plan,
            user,
            session_length,
            "kibana",
            rng,
            clear_session_after=(index == session_length - 1),
        )
        event["prevAction"] = prev_action
        event["route"] = training_sim.infer_route_for_action(plan.action.value)
        events.append(event)
        prev_action = plan.action.value

    return events


def main(argv) -> int:
    args = parse_args(argv)

    catalog = training_sim.load_actions_from_json(args.backend_apis)
    nav_summary = training_sim.initialize_navigation(args.actions_order, args.backend_apis, catalog)

    print(
        f"Loaded navigation from {args.actions_order} "
        f"({nav_summary['auth_steps_resolved']} auth steps, "
        f"{nav_summary['json_routes_with_actions']} JSON routes with tracked actions)",
        file=sys.stderr,
    )

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

    events_sent = 0
    sessions_sent = 0

    try:
        if args.mode == "replay":
            print(f"Loading events from {args.input_csv}", file=sys.stderr)
            csv_events = load_csv_events(args.input_csv)
            print(f"Loaded {len(csv_events)} events", file=sys.stderr)

            if args.virtual_start:
                try:
                    virtual_start = parse_virtual_start(args.virtual_start)
                    csv_events, _ = retime_events(csv_events, virtual_start)
                    print(f"Retimed events to start from {virtual_start.isoformat()}", file=sys.stderr)
                except argparse.ArgumentTypeError as exc:
                    print(str(exc), file=sys.stderr)
                    return 2

            grouped_by_session = defaultdict(list)
            for event in csv_events:
                grouped_by_session[event.get("sessionId", "")].append(event)

            sorted_sessions = sorted(grouped_by_session.items())

            for session_id, session_events in sorted_sessions:
                session_events.sort(key=lambda e: e.get("sequenceInSession", "0"))
                session_label = session_events[0].get("anomaly_type", "normal")

                for event_row in session_events:
                    event = parse_csv_event_to_kafka_event(event_row)

                    if args.dry_run:
                        print(json.dumps(event, ensure_ascii=True), flush=True)
                    else:
                        try:
                            future = producer.send(
                                args.topic,
                                key=event.get("insuredId"),
                                value=event,
                            )
                            if args.async_send:
                                future.add_callback(
                                    lambda msg: print(
                                        f"-> {msg.topic}:{msg.partition}@{msg.offset}",
                                        flush=True,
                                    )
                                )
                                future.add_errback(
                                    lambda err: print(f"send failed: {err}", file=sys.stderr, flush=True)
                                )
                            else:
                                metadata = future.get(timeout=max(1, args.request_timeout_ms // 1000))
                                print(
                                    f"-> {metadata.topic}:{metadata.partition}@{metadata.offset} "
                                    f"action={event['action']!r} anomaly={session_label}",
                                    flush=True,
                                )
                        except Exception as exc:
                            print(f"send failed: {exc}", file=sys.stderr, flush=True)

                    events_sent += 1
                    if args.rate > 0:
                        time.sleep(max(0.0, 1.0 / args.rate))

                sessions_sent += 1

        else:
            rng = random.Random(args.seed)
            virtual_start = parse_virtual_start(args.virtual_start) if args.virtual_start else datetime.now(timezone.utc)
            month_start = datetime(virtual_start.year, virtual_start.month, 1, tzinfo=timezone.utc)
            if virtual_start.month == 12:
                month_end = datetime(virtual_start.year + 1, 1, 1, tzinfo=timezone.utc) - timedelta(seconds=1)
            else:
                month_end = datetime(virtual_start.year, virtual_start.month + 1, 1, tzinfo=timezone.utc) - timedelta(seconds=1)

            pools = training_sim.build_pools()
            users = training_sim.build_users(rng, args.insured_count, args.session_count, pools)

            print(
                f"Generating {args.session_count} sessions with {args.insured_count} users",
                file=sys.stderr,
            )

            for session_idx in range(args.session_count):
                user = rng.choice(users)
                user.sessions_generated += 1
                session_number = user.sessions_generated

                session_events = generate_synthetic_session(
                    user, month_start, month_end, catalog, rng, session_number
                )

                for event in session_events:
                    if args.dry_run:
                        print(json.dumps(event, ensure_ascii=True), flush=True)
                    else:
                        try:
                            future = producer.send(
                                args.topic,
                                key=user.insured_id,
                                value=event,
                            )
                            if args.async_send:
                                future.add_callback(
                                    lambda msg: print(
                                        f"-> {msg.topic}:{msg.partition}@{msg.offset}",
                                        flush=True,
                                    )
                                )
                                future.add_errback(
                                    lambda err: print(f"send failed: {err}", file=sys.stderr, flush=True)
                                )
                            else:
                                metadata = future.get(timeout=max(1, args.request_timeout_ms // 1000))
                                print(
                                    f"-> {metadata.topic}:{metadata.partition}@{metadata.offset} "
                                    f"action={event['action']!r}",
                                    flush=True,
                                )
                        except Exception as exc:
                            print(f"send failed: {exc}", file=sys.stderr, flush=True)

                    events_sent += 1
                    if args.rate > 0:
                        time.sleep(max(0.0, 1.0 / args.rate))

                sessions_sent += 1

    except KeyboardInterrupt:
        print("stopped", file=sys.stderr)
    finally:
        close_producer(producer)

    print(
        json.dumps(
            {
                "mode": args.mode,
                "sessions_sent": sessions_sent,
                "events_sent": events_sent,
            },
            ensure_ascii=False,
            indent=2,
        ),
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
