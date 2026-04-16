#!/usr/bin/env python3
"""
JSON-driven anomaly-first Kafka producer.

Uses the same contract sources as the normal simulator:
- backend-apis-actions.json
- actions_order-v2.json

It derives valid journeys from those JSON files and then mutates them into the
training anomaly families, including a coordinated distributed_brute_force mode.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter
from datetime import timedelta
from typing import Sequence

from simulator_final_revised_json_driven import (
    ANOMALY_TYPES,
    DEFAULT_ACTIONS_ORDER,
    DEFAULT_BACKEND_APIS,
    GEO_PROFILE,
    build_anomaly_session,
    build_normal_session,
    build_producer,
    build_users,
    close_producer,
    emit_events,
    load_catalog,
    materialize_session_events,
    parse_iso_dt,
    random_public_ip,
)


def parse_args(argv: Sequence[str]):
    p = argparse.ArgumentParser(description="JSON-driven anomaly Kafka producer")
    p.add_argument("--bootstrap-servers", default="localhost:9092")
    p.add_argument("--topic", default="topic-audit-trail")
    p.add_argument("--backend-apis", default=str(DEFAULT_BACKEND_APIS))
    p.add_argument("--actions-order", default=str(DEFAULT_ACTIONS_ORDER))
    p.add_argument("--tag", required=True, choices=["all"] + ANOMALY_TYPES)
    p.add_argument("--normal-ratio", type=float, default=0.0)
    p.add_argument("--session-count", type=int, default=20)
    p.add_argument("--insured-count", type=int, default=10)
    p.add_argument("--rate", type=float, default=4.0)
    p.add_argument("--seed", type=int, default=None)
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--start-at", default="2025-01-01T08:00:00+00:00")
    p.add_argument("--linger-ms", type=int, default=0)
    p.add_argument("--acks", type=int, choices=[0, 1, -1], default=1)
    p.add_argument("--request-timeout-ms", type=int, default=10000)
    p.add_argument("--max-block-ms", type=int, default=10000)
    p.add_argument("--retries", type=int, default=0)
    p.add_argument("--async-send", action="store_true")
    return p.parse_args(argv)


def anomaly_picker(tag: str):
    idx = 0
    while True:
        if tag == "all":
            yield ANOMALY_TYPES[idx % len(ANOMALY_TYPES)]
            idx += 1
        else:
            yield tag


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

    rng = random.Random(args.seed)
    catalog = load_catalog(args.backend_apis, args.actions_order)
    users = build_users(rng, args.insured_count)
    cursor = parse_iso_dt(args.start_at)
    pick = anomaly_picker(args.tag)
    producer = None
    sessions_sent = 0
    events_sent = 0
    labels = Counter()
    shared_attack_ip = random_public_ip(("185", "220"), rng)

    try:
        if not args.dry_run:
            producer = build_producer(args)

        for _ in range(args.session_count):
            user = rng.choice(users)
            user.session_counter += 1
            session_id = f"{rng.randint(1000000, 9999999)}-{user.insured_id[-4:]}-{user.session_counter}"
            if rng.random() < args.normal_ratio:
                label = "normal"
                plans = build_normal_session(catalog, user, cursor, rng)
            else:
                label = next(pick)
                plans = build_anomaly_session(catalog, user, cursor, rng, label, shared_attack_ip=shared_attack_ip)
            if plans:
                cursor = plans[-1].created_at + timedelta(seconds=rng.randint(20, 180))
            events = materialize_session_events(user, plans, session_id, user.session_counter, label, rng)
            events_sent += emit_events(events, args, producer)
            sessions_sent += 1
            labels[label] += 1

        print(json.dumps({
            "sessions_sent": sessions_sent,
            "events_sent": events_sent,
            "session_labels": dict(labels),
            "tracked_api_count": catalog.tracked_api_count,
            "resolved_routes": {k: len(v) for k, v in catalog.route_actions.items()},
            "shared_attack_ip": shared_attack_ip,
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
