#!/usr/bin/env python3
"""
anomaly_kafka_producer.py

Publish training-aligned anomaly sessions to Kafka.

This producer reuses the same JSON-driven catalog and session builders as the
training dataset generator. It can emit one anomaly family only or cycle
through all supported anomaly types, with an optional mix of normal sessions.
"""

import argparse
import json
import os
import random
import sys
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from itertools import cycle
from pathlib import Path
from typing import Dict, List

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
    parser = argparse.ArgumentParser(description="Kafka anomaly producer aligned with training generators")
    parser.add_argument("--bootstrap-servers", default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--backend-apis", default=str(DEFAULT_BACKEND_APIS))
    parser.add_argument("--actions-order", default=str(DEFAULT_ACTIONS_ORDER))
    parser.add_argument("--tag", choices=["all"] + training_sim.ANOMALY_TYPES, required=True)
    parser.add_argument(
        "--normal-ratio",
        type=float,
        default=0.0,
        help="Fraction of sessions that should be normal (0.0 to 1.0).",
    )
    parser.add_argument("--session-count", type=int, default=20, help="Number of sessions to emit.")
    parser.add_argument("--insured-count", type=int, default=10, help="Size of synthetic user pool.")
    parser.add_argument("--rate", type=float, default=4.0, help="Approximate event rate per second.")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true", help="Print JSON to stdout instead of Kafka.")
    parser.add_argument("--virtual-start", default="2026-03-24T06:30:00+01:00")
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


def month_bounds(reference: datetime):
    start = datetime(reference.year, reference.month, 1, tzinfo=timezone.utc)
    if reference.month == 12:
        nxt = datetime(reference.year + 1, 1, 1, tzinfo=timezone.utc)
    else:
        nxt = datetime(reference.year, reference.month + 1, 1, tzinfo=timezone.utc)
    return start, nxt - timedelta(seconds=1)


def build_action_route_map() -> Dict[str, str]:
    route_map: Dict[str, str] = {}

    for sequence in training_sim.LOGIN_SEQUENCES.values():
        for action in sequence:
            route_map[action] = "auth"

    for sequence in training_sim.LOGOUT_BY_LOGIN.values():
        for action in sequence:
            route_map[action] = "logout"

    for route_name, weighted_actions in training_sim.ROUTE_ACTIONS.items():
        for action_value, _ in weighted_actions:
            route_map.setdefault(action_value, route_name)

    return route_map


def retime_session(plans, session_start: datetime, rng: random.Random) -> datetime:
    if not plans:
        return session_start

    origin = plans[0].timestamp
    for plan in plans:
        plan.timestamp = session_start + (plan.timestamp - origin)

    return plans[-1].timestamp + timedelta(seconds=rng.randint(30, 180))


def pick_anomaly_tag(tag_arg: str, anomaly_cycle) -> str:
    if tag_arg == "all":
        return next(anomaly_cycle)
    return tag_arg


def main(argv) -> int:
    args = parse_args(argv)
    if not 0.0 <= args.normal_ratio <= 1.0:
        print("--normal-ratio must be between 0.0 and 1.0", file=sys.stderr)
        return 2
    if args.session_count <= 0:
        print("--session-count must be > 0", file=sys.stderr)
        return 2
    if args.insured_count <= 0:
        print("--insured-count must be > 0", file=sys.stderr)
        return 2

    rng = random.Random(args.seed)

    try:
        virtual_start = parse_virtual_start(args.virtual_start)
    except argparse.ArgumentTypeError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    catalog = training_sim.load_actions_from_json(args.backend_apis)
    nav_summary = training_sim.initialize_navigation(args.actions_order, args.backend_apis, catalog)
    route_map = build_action_route_map()

    pools = training_sim.build_pools()
    users = training_sim.build_users(rng, args.insured_count, args.session_count, pools)
    month_start, month_end = month_bounds(virtual_start)
    cursor = virtual_start
    anomaly_cycle = cycle(training_sim.ANOMALY_TYPES)
    by_label = defaultdict(int)

    print(
        f"Loaded navigation from {args.actions_order} "
        f"({nav_summary['auth_steps_resolved']} auth steps, "
        f"{nav_summary['json_routes_with_actions']} JSON routes with tracked actions)",
        file=sys.stderr,
    )
    print(
        f"Producing {args.session_count} sessions with tag={args.tag!r} "
        f"and normal_ratio={args.normal_ratio:.2f}",
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
        for _ in range(args.session_count):
            user = rng.choice(users)
            user.sessions_generated += 1
            session_number = user.sessions_generated

            produce_normal = rng.random() < args.normal_ratio
            if produce_normal:
                plans = training_sim.build_normal_session(
                    user, month_start, month_end, catalog, rng, session_number
                )
                label = "normal"
            else:
                label = pick_anomaly_tag(args.tag, anomaly_cycle)
                plans = training_sim.build_anomaly_session(
                    user, month_start, month_end, catalog, rng, session_number, anomaly_type=label
                )

            if not plans:
                continue

            cursor = retime_session(plans, cursor, rng)
            session_length = len(plans)
            prev_action = ""

            for index, plan in enumerate(plans):
                event = training_sim.build_event_record(
                    plan,
                    user,
                    session_length,
                    args.created_at_format,
                    rng,
                    clear_session_after=(index == session_length - 1),
                )
                event["prevAction"] = prev_action
                event["route"] = route_map.get(plan.action.value, "unknown")

                if args.dry_run:
                    print(json.dumps(event, ensure_ascii=True), flush=True)
                else:
                    try:
                        future = producer.send(args.topic, key=user.insured_id, value=event)
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
                                f"action={event['action']!r} tag={label}",
                                flush=True,
                            )
                    except Exception as exc:
                        print(f"send failed: {exc}", file=sys.stderr, flush=True)

                prev_action = plan.action.value
                events_sent += 1
                if args.rate > 0:
                    time.sleep(max(0.0, 1.0 / args.rate))

            by_label[label] += 1
            sessions_sent += 1

    except KeyboardInterrupt:
        print("stopped", file=sys.stderr)
    finally:
        close_producer(producer)

    print(
        json.dumps(
            {
                "sessions_sent": sessions_sent,
                "events_sent": events_sent,
                "session_labels": dict(by_label),
            },
            ensure_ascii=False,
            indent=2,
        ),
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
