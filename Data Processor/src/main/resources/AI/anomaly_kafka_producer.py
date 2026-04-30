#!/usr/bin/env python3
"""
anomaly_kafka_producer.py
===========================
Updated Live Anomaly Generator (v3)

Focuses on emitting anomalous sessions to Kafka for model testing and demo.

Features:
  - Continuous injection of specific anomaly families.
  - Interleaved session execution.
  - Aligned with training logic (v3).
"""

import argparse
import json
import math
import os
import random
import sys
import time
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional
from itertools import cycle

try:
    from kafka import KafkaProducer
except ImportError:
    KafkaProducer = None

import simulator_yearly_dataset_v3 as training_sim
import live_simulator_support as live_support

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
DEFAULT_BACKEND_APIS = Path(__file__).with_name("backend-apis-actions.json")
DEFAULT_ACTIONS_ORDER = Path(__file__).with_name("actions_order-v2.json")
DEFAULT_MARKOV_LOOKUP = Path(__file__).with_name("markov_transition_lookup.json")
DEFAULT_TYPE_LABELS = Path(__file__).with_name("rf_type_labels.json")


def load_anomaly_tags() -> List[str]:
    if DEFAULT_TYPE_LABELS.exists():
        with DEFAULT_TYPE_LABELS.open(encoding="utf-8") as handle:
            loaded = [str(value) for value in json.load(handle).values() if value]
        ordered = list(dict.fromkeys(loaded))
        if ordered:
            return ordered
    return list(training_sim.ANOMALY_TYPES)


ANOMALY_TAGS = load_anomaly_tags()
SHOWCASE_ANOMALY_ORDER = [tag for tag in ANOMALY_TAGS if tag != "distributed_brute_force"] + (
    ["distributed_brute_force"] if "distributed_brute_force" in ANOMALY_TAGS else []
)

@dataclass
class LiveSession:
    user: training_sim.UserProfile
    runtime_view: live_support.SessionRuntimeView
    anomaly_type: str
    next_index: int = 0

    @property
    def is_finished(self) -> bool:
        return self.next_index >= len(self.runtime_view.events)

    @property
    def next_event(self) -> Dict[str, object]:
        return self.runtime_view.events[self.next_index]

# ---------------------------------------------------------------------------
# Virtual Clock
# ---------------------------------------------------------------------------
class SimpleClock:
    def __init__(self, start: datetime, speed: float):
        self.current_virtual = start
        self.speed = speed
        self.last_wall_time = time.monotonic()

    def tick(self) -> datetime:
        now_wall = time.monotonic()
        elapsed_wall = now_wall - self.last_wall_time
        self.current_virtual += timedelta(seconds=elapsed_wall * self.speed)
        self.last_wall_time = now_wall
        return self.current_virtual

# ---------------------------------------------------------------------------
# Anomaly Generator Logic
# ---------------------------------------------------------------------------
class AnomalyGenerator:
    def __init__(self, args, rng: random.Random):
        self.args = args
        self.rng = rng
        self.catalog = training_sim.load_actions_from_json(args.backend_apis)
        training_sim.initialize_navigation(args.actions_order, args.backend_apis, self.catalog)
        self.markov_lookup = live_support.load_markov_lookup(DEFAULT_MARKOV_LOOKUP)
        
        self.pools = training_sim.build_pools()
        self.all_users = training_sim.build_users(rng, args.insured_count, 1000, self.pools)
        self.active_sessions: List[LiveSession] = []
        
        self.clock = SimpleClock(self.parse_start_time(args.virtual_start), args.rate)
        self.producer = self.build_producer() if not args.dry_run else None
        
        self.anomaly_cycle = cycle(SHOWCASE_ANOMALY_ORDER)

    def parse_start_time(self, ts_str: str) -> datetime:
        dt = datetime.fromisoformat(ts_str)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt

    def build_producer(self):
        if KafkaProducer is None:
            print("Error: kafka-python not installed.", file=sys.stderr)
            sys.exit(1)
        return KafkaProducer(
            bootstrap_servers=[s.strip() for s in self.args.bootstrap_servers.split(",")],
            value_serializer=lambda v: json.dumps(v, ensure_ascii=True).encode("utf-8"),
            key_serializer=lambda v: v.encode("utf-8") if v else None,
            api_version=self.args.api_version
        )

    def start_anomaly_session(self, user: training_sim.UserProfile):
        label = self.args.tag
        if label == "all":
            label = next(self.anomaly_cycle)
        session_number = user.sessions_generated + 1
        month_start = self.clock.current_virtual.replace(day=1, hour=0, minute=0, second=0)
        month_end = (month_start + timedelta(days=32)).replace(day=1) - timedelta(seconds=1)

        runtime_view, working_user = self.prepare_runtime_view(
            user=user,
            month_start=month_start,
            month_end=month_end,
            session_number=session_number,
            anomaly_type=label,
        )
        if runtime_view is None or working_user is None:
            raise RuntimeError(f"Could not build a detectable {label} session.")

        user.sessions_generated = session_number
        user.current_ip = working_user.current_ip
        self.active_sessions.append(
            LiveSession(
                user=user,
                runtime_view=runtime_view,
                anomaly_type=label,
            )
        )

    def prepare_runtime_view(self, user, month_start, month_end, session_number: int, anomaly_type: str):
        max_attempts = 16 if anomaly_type == "distributed_brute_force" else 10
        for _ in range(max_attempts):
            working_user = deepcopy(user)
            plans = training_sim.build_anomaly_session(
                working_user,
                month_start,
                month_end,
                self.catalog,
                self.rng,
                session_number,
                anomaly_type=anomaly_type,
            )
            if not plans:
                continue

            live_support.retime_plans(
                plans,
                live_support.live_anchor(self.clock.current_virtual, anomaly_type, self.rng),
            )
            live_support.strengthen_plans_for_demo(plans, anomaly_type)
            runtime_view = live_support.materialize_session(
                working_user,
                plans,
                self.rng,
                self.markov_lookup,
            )
            if live_support.anomaly_matches_target(runtime_view, anomaly_type):
                return runtime_view, working_user

        return None, None

    def run(self):
        print(f"Starting Anomaly Generator (Target: {self.args.tag})", file=sys.stderr)
        
        sessions_sent = 0
        try:
            while sessions_sent < self.args.session_count or self.args.session_count == 0:
                now_virtual = self.clock.tick()
                
                # Maintain active sessions
                while len(self.active_sessions) < self.args.concurrent_sessions:
                    available_users = [u for u in self.all_users if not any(s.user.insured_id == u.insured_id for s in self.active_sessions)]
                    if not available_users: break
                    self.start_anomaly_session(self.rng.choice(available_users))

                remaining_sessions = []
                for session in self.active_sessions:
                    if session.is_finished:
                        sessions_sent += 1
                        continue
                        
                    next_timestamp = session.next_event.get("_timestamp")
                    if next_timestamp and next_timestamp <= now_virtual:
                        self.emit_event(session)
                        session.next_index += 1
                    
                    if not session.is_finished:
                        remaining_sessions.append(session)
                
                self.active_sessions = remaining_sessions
                time.sleep(0.01)
                
        except KeyboardInterrupt:
            print("\nStopped", file=sys.stderr)
        finally:
            if self.producer:
                self.producer.flush()
                self.producer.close()

    def emit_event(self, session: LiveSession):
        user = session.user
        event = live_support.event_for_emit(session.next_event)
        
        if self.args.dry_run:
            print(json.dumps(event, ensure_ascii=False))
        else:
            try:
                self.producer.send(self.args.topic, key=user.insured_id, value=event)
                print(f"[{event['createdAt']}] ANOMALY: {session.anomaly_type:20} | {event['action'][:30]:30} | {user.insured_id}", flush=True)
            except Exception as e:
                print(f"Error: {e}", file=sys.stderr)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--backend-apis", default=str(DEFAULT_BACKEND_APIS))
    parser.add_argument("--actions-order", default=str(DEFAULT_ACTIONS_ORDER))
    parser.add_argument("--tag", choices=["all"] + ANOMALY_TAGS, required=True)
    parser.add_argument("--session-count", type=int, default=0, help="Total sessions to emit (0=inf)")
    parser.add_argument("--concurrent-sessions", type=int, default=5)
    parser.add_argument("--insured-count", type=int, default=100)
    parser.add_argument("--rate", type=float, default=120.0, help="Virtual speed")
    parser.add_argument("--virtual-start", default="2026-03-24T10:00:00+01:00")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--api-version", default="3.7.0")
    parser.add_argument("--seed", type=int, default=None)
    
    args = parser.parse_args()
    rng = random.Random(args.seed)
    
    gen = AnomalyGenerator(args, rng)
    gen.run()

if __name__ == "__main__":
    main()
