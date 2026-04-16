#!/usr/bin/env python3
"""
Focused anomaly Kafka producer aligned with the Spring Boot AuditTrailEvent DTO.

This script reuses the updated simulator helpers and emits mostly or only anomaly
sessions, which is handy for testing AuditTrailConsumer rule triggering,
ModelInferenceService heuristics, Redis buffering, and alert publishing.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import time
from collections import Counter
from datetime import timedelta
from typing import Sequence

from simulator_final_revised_updated import (
    ANOMALY_TYPES,
    build_anomaly_session,
    build_normal_session,
    build_producer,
    build_users,
    close_producer,
    emit_events,
    materialize_session_events,
    parse_iso_dt,
)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Anomaly-first Kafka producer for audit trail sessions")
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--tag", required=True, choices=["all"] + [t for t in ANOMALY_TYPES if t != "normal"], help="single anomaly family or 'all' to cycle across all")
    parser.add_argument("--normal-ratio", type=float, default=0.0, help="fraction of normal sessions to mix in (0..1)")
    parser.add_argument("--session-count", type=int, default=20)
    parser.add_argument("--insured-count", type=int, default=10)
    parser.add_argument("--rate", type=float, default=4.0)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--start-at", default="2025-01-01T08:00:00+00:00")
    parser.add_argument("--linger-ms", type=int, default=0)
    parser.add_argument("--acks", type=int, choices=[0, 1, -1], default=1)
    parser.add_argument("--request-timeout-ms", type=int, default=10000)
    parser.add_argument("--max-block-ms", type=int, default=10000)
    parser.add_argument("--retries", type=int, default=0)
    parser.add_argument("--async-send", action="store_true")
    return parser.parse_args(argv)


def anomaly_picker(tag: str, rng: random.Random):
    all_tags = [t for t in ANOMALY_TYPES if t != "normal"]
    index = 0
    while True:
        if tag == "all":
            yield all_tags[index % len(all_tags)]
            index += 1
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
    users = build_users(rng, args.insured_count)
    cursor = parse_iso_dt(args.start_at)
    tag_iter = anomaly_picker(args.tag, rng)
    producer = None

    sessions_sent = 0
    events_sent = 0
    labels = Counter()

    try:
        if not args.dry_run:
            producer = build_producer(args)

        for _ in range(args.session_count):
            user = rng.choice(users)
            user.session_counter += 1
            session_id = f"{rng.randint(1000000, 9999999)}-{user.insured_id[-4:]}-{user.session_counter}"

            if rng.random() < args.normal_ratio:
                label = "normal"
                plans = build_normal_session(user, cursor, rng)
            else:
                label = next(tag_iter)
                plans = build_anomaly_session(user, cursor, rng, label)

            if plans:
                cursor = plans[-1].created_at + timedelta(seconds=rng.randint(20, 180))

            events = materialize_session_events(user, plans, session_id, user.session_counter, label, rng)
            events_sent += emit_events(events, args, producer, rng)
            labels[label] += 1
            sessions_sent += 1

        print(json.dumps({
            "sessions_sent": sessions_sent,
            "events_sent": events_sent,
            "session_labels": dict(labels),
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
