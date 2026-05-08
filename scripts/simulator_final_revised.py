#!/usr/bin/env python3
"""
simulator_final_revised.py
===========================
Updated Live Kafka Simulator (v3)

Driven by:
  - simulator_yearly_dataset_v3.py (Persona & Anomaly logic)
  - backend-apis-actions.json (Action Catalog)
  - actions_order-v2.json (Navigation Graph)

Features:
  - Multi-user interleaved sessions (Persona-driven).
  - Real-time or accelerated virtual clock.
  - Aligned with updated datasets (audit_trail_2025).
  - Supports --anomaly-mode to inject realistic anomalies.
"""

import argparse
import json
import math
import os
import random
import sys
import time
import uuid
from copy import deepcopy
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

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

@dataclass
class LiveSession:
    user: training_sim.UserProfile
    runtime_view: live_support.SessionRuntimeView
    anomaly_type: str = "normal"
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
class VirtualClock:
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

    def load_factor(self) -> float:
        """Calculate load factor based on time of day (0.1 to 1.0)."""
        m = self.current_virtual.hour * 60 + self.current_virtual.minute
        value = 0.2
        # Three peaks: 8:30, 12:30, 18:00
        for peak, width in [(510, 90), (750, 60), (1080, 90)]:
            value += 0.5 * math.exp(-((m - peak) ** 2) / (2 * width ** 2))
        if self.current_virtual.weekday() >= 5:
            value *= 0.6
        return min(1.0, max(0.1, value))

# ---------------------------------------------------------------------------
# Simulator Logic
# ---------------------------------------------------------------------------
class LiveSimulator:
    def __init__(self, args, rng: random.Random):
        self.args = args
        self.rng = rng
        self.catalog = training_sim.load_actions_from_json(args.backend_apis)
        training_sim.initialize_navigation(args.actions_order, args.backend_apis, self.catalog)
        self.markov_lookup = live_support.load_markov_lookup(DEFAULT_MARKOV_LOOKUP)
        
        self.pools = training_sim.build_pools()
        self.all_users = training_sim.build_users(rng, args.insured_count, 1000, self.pools)
        self.active_sessions: List[LiveSession] = []
        
        self.clock = VirtualClock(self.parse_start_time(args.virtual_day_start), args.virtual_speed)
        self.producer = self.build_producer() if not args.dry_run else None

    def parse_start_time(self, ts_str: str) -> datetime:
        dt = datetime.fromisoformat(ts_str)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt

    def build_producer(self):
        if KafkaProducer is None:
            print("Error: kafka-python not installed. Use --dry-run or pip install kafka-python", file=sys.stderr)
            sys.exit(1)
        return KafkaProducer(
            bootstrap_servers=[s.strip() for s in self.args.bootstrap_servers.split(",")],
            value_serializer=lambda v: json.dumps(v, ensure_ascii=True).encode("utf-8"),
            key_serializer=lambda v: v.encode("utf-8") if v else None,
            api_version=self.args.api_version,
            acks=self.args.acks
        )

    def start_new_session(self, user: training_sim.UserProfile):
        is_anomaly = self.args.anomaly_mode and self.rng.random() < 0.05
        session_number = user.sessions_generated + 1
        month_start = self.clock.current_virtual.replace(day=1, hour=0, minute=0, second=0)
        month_end = (month_start + timedelta(days=32)).replace(day=1) - timedelta(seconds=1)

        anomaly_type = None
        if is_anomaly:
            anomaly_type = self.rng.choice(training_sim.SESSION_SCOPED_ANOMALY_TYPES)

        runtime_view, working_user = self.prepare_runtime_view(
            user=user,
            month_start=month_start,
            month_end=month_end,
            session_number=session_number,
            anomaly_type=anomaly_type,
        )

        if runtime_view is None or working_user is None:
            return

        user.sessions_generated = session_number
        user.current_ip = working_user.current_ip
        self.active_sessions.append(
            LiveSession(
                user=user,
                runtime_view=runtime_view,
                anomaly_type=anomaly_type or "normal",
            )
        )

    def prepare_runtime_view(self, user, month_start, month_end, session_number: int, anomaly_type: Optional[str]):
        max_attempts = 24 if anomaly_type is None else 12
        last_view = None
        for _ in range(max_attempts):
            working_user = deepcopy(user)
            if anomaly_type:
                plans = training_sim.build_anomaly_session(
                    working_user,
                    month_start,
                    month_end,
                    self.catalog,
                    self.rng,
                    session_number,
                    anomaly_type=anomaly_type,
                )
            else:
                plans = training_sim.build_normal_session(
                    working_user,
                    month_start,
                    month_end,
                    self.catalog,
                    self.rng,
                    session_number,
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
            last_view = runtime_view

            if anomaly_type:
                if live_support.anomaly_matches_target(runtime_view, anomaly_type):
                    return runtime_view, working_user
                continue

            if live_support.is_showcase_safe_normal(runtime_view):
                return runtime_view, working_user

        if anomaly_type:
            print(
                f"Warning: could not build a detectable {anomaly_type} session after {max_attempts} attempts.",
                file=sys.stderr,
            )
        elif last_view is not None:
            print(
                "Warning: could not build a showcase-safe normal session after "
                f"{max_attempts} attempts; skipping one user slot.",
                file=sys.stderr,
            )
        return None, None

    def run(self):
        print(f"Starting Live Simulator at {self.clock.current_virtual} (Speed: {self.clock.speed}x)", file=sys.stderr)
        wall_start = time.monotonic()
        
        try:
            while True:
                now_virtual = self.clock.tick()
                load = self.clock.load_factor()
                
                # Maintain active session count based on load
                target_count = max(1, int(self.args.insured_count * load))
                while len(self.active_sessions) < target_count:
                    available_users = [u for u in self.all_users if not any(s.user.insured_id == u.insured_id for s in self.active_sessions)]
                    if not available_users: break
                    self.start_new_session(self.rng.choice(available_users))

                # Process due events
                remaining_sessions = []
                for session in self.active_sessions:
                    if session.is_finished:
                        continue
                        
                    next_timestamp = session.next_event.get("_timestamp")
                    if next_timestamp and next_timestamp <= now_virtual:
                        self.emit_event(session)
                        session.next_index += 1
                    
                    if not session.is_finished:
                        remaining_sessions.append(session)
                
                self.active_sessions = remaining_sessions

                # Exit condition
                if self.args.duration > 0 and (time.monotonic() - wall_start) >= self.args.duration:
                    break
                
                # Sleep briefly to avoid 100% CPU
                time.sleep(0.01)
                
        except KeyboardInterrupt:
            print("\nStopped by user", file=sys.stderr)
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
                print(
                    f"[{event['createdAt']}] {user.persona:15} | "
                    f"{event['action'][:40]:40} | {event['sessionId']}",
                    flush=True,
                )
            except Exception as e:
                print(f"Error sending to Kafka: {e}", file=sys.stderr)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--topic", default="topic-audit-trail")
    parser.add_argument("--backend-apis", default=str(DEFAULT_BACKEND_APIS))
    parser.add_argument("--actions-order", default=str(DEFAULT_ACTIONS_ORDER))
    parser.add_argument("--insured-count", type=int, default=50)
    parser.add_argument("--virtual-day-start", default="2026-03-24T08:00:00+01:00")
    parser.add_argument("--virtual-speed", type=float, default=60.0, help="1.0 = real time, 60.0 = 1 min per wall sec")
    parser.add_argument("--duration", type=int, default=0, help="seconds to run")
    parser.add_argument("--anomaly-mode", action="store_true", help="inject 5% anomaly sessions into the normal traffic stream")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--api-version", default="3.7.0")
    parser.add_argument("--acks", type=int, default=1)
    parser.add_argument("--seed", type=int, default=None)
    
    args = parser.parse_args()
    rng = random.Random(args.seed)
    
    sim = LiveSimulator(args, rng)
    sim.run()

if __name__ == "__main__":
    main()
