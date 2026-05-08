#!/usr/bin/env python3
"""
Shared helpers for the live Kafka simulators.

The worker derives session features from event streams, but the reference
training data already contains those engineered fields. For demo fidelity we
materialize each session with the same generator and enrichment logic used to
produce the dataset, then filter or strengthen sessions so the live showcase
behaves predictably.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Sequence, Tuple

import simulator_yearly_dataset_v3 as training_sim

LOGIN_ACTIONS = training_sim.primary_login_actions() | {"Activation de compte"}
REPEATED_FAIL_TYPES = {"BANKING_ACTIONS", "LOGGING_ACTIONS"}
RAPID_FIRE_MIN_EVENTS = 3
RAPID_FIRE_WINDOW_SECONDS = 2
SESSION_TIMEOUT_SECONDS = 1200
IMPOSSIBLE_SEQ_MIN_PROBABILITY = 0.005
PATH_DEVIATION_MIN_PROBABILITY = 0.02


@dataclass(frozen=True)
class SessionRuntimeView:
    events: List[Dict[str, object]]
    summary: Dict[str, object]
    triggered_rules: Tuple[str, ...]
    rare_transition_count: int


def load_markov_lookup(path: str | Path) -> Dict[str, Dict[str, float]]:
    with open(path, encoding="utf-8") as handle:
        raw = json.load(handle)
    lookup: Dict[str, Dict[str, float]] = {}
    for from_action, transitions in raw.items():
        lookup[from_action] = {
            item["to_action"]: float(item.get("probability", 0.0))
            for item in transitions or []
            if item.get("to_action")
        }
    return lookup


def retime_plans(plans: Sequence[training_sim.EventPlan], new_start: datetime) -> None:
    if not plans:
        return
    offset = new_start - plans[0].timestamp
    for plan in plans:
        plan.timestamp += offset


def live_anchor(current_virtual: datetime, anomaly_type: str | None, rng) -> datetime:
    if anomaly_type != "unusual_hour":
        return current_virtual

    utc_now = current_virtual.astimezone(timezone.utc)
    target_utc = utc_now.replace(
        hour=rng.randint(2, 4),
        minute=rng.randint(0, 59),
        second=rng.randint(0, 59),
        microsecond=0,
    )
    if target_utc > utc_now:
        target_utc -= timedelta(days=1)
    return target_utc.astimezone(current_virtual.tzinfo or timezone.utc)


def strengthen_plans_for_demo(plans: Sequence[training_sim.EventPlan], anomaly_type: str | None) -> None:
    if anomaly_type != "rapid_fire" or not plans:
        return

    base_time = plans[0].timestamp
    for index, plan in enumerate(plans):
        plan.timestamp = base_time + timedelta(seconds=min(index, 2))
    for index in range(3, len(plans)):
        plans[index].timestamp = plans[index - 1].timestamp + timedelta(seconds=1)


def materialize_session(
    user: training_sim.UserProfile,
    plans: Sequence[training_sim.EventPlan],
    rng,
    markov_lookup: Mapping[str, Mapping[str, float]],
    created_at_format: str = "kibana",
) -> SessionRuntimeView:
    events: List[Dict[str, object]] = []
    session_length = len(plans)

    for index, plan in enumerate(plans):
        events.append(
            training_sim.build_event_record(
                plan,
                user,
                session_length,
                created_at_format,
                rng,
                clear_session_after=index == session_length - 1,
            )
        )

    enriched_events, summaries = training_sim.enrich_events_and_build_summaries(events, created_at_format)
    summary = summaries[0] if summaries else {}
    triggered_rules = tuple(evaluate_worker_rules(enriched_events, markov_lookup))
    rare_transition_count = count_rare_transitions(enriched_events, markov_lookup, PATH_DEVIATION_MIN_PROBABILITY)
    return SessionRuntimeView(
        events=enriched_events,
        summary=summary,
        triggered_rules=triggered_rules,
        rare_transition_count=rare_transition_count,
    )


def event_for_emit(event: Mapping[str, object]) -> Dict[str, object]:
    emitted = dict(event)
    emitted.pop("_timestamp", None)
    return emitted


def is_showcase_safe_normal(view: SessionRuntimeView) -> bool:
    summary = view.summary
    if int(summary.get("is_anomaly", 0)) != 0:
        return False
    if str(summary.get("primary_anomaly_type", "normal")) != "normal":
        return False
    if int(summary.get("ipChanged", 0)) != 0 or int(summary.get("deviceChanged", 0)) != 0:
        return False
    if int(summary.get("totalKOs", 0)) >= 3:
        return False
    if int(summary.get("maxDownloadsIn2Minutes", 0)) > 5:
        return False
    if float(summary.get("riskScoreMax", 0.0) or 0.0) >= 80.0:
        return False
    if view.rare_transition_count > 0:
        return False
    return True


def anomaly_matches_target(view: SessionRuntimeView, anomaly_type: str) -> bool:
    summary = view.summary
    rules = set(view.triggered_rules)

    if str(summary.get("primary_anomaly_type", "")) != anomaly_type:
        return False

    if anomaly_type == "rapid_fire":
        return "rapid_fire" in rules
    if anomaly_type == "unusual_hour":
        return "unusual_hour" in rules
    if anomaly_type == "geo_jump":
        return "geo_jump" in rules and int(summary.get("ipChanged", 0)) == 1
    if anomaly_type == "repeated_fail":
        return "repeated_fail" in rules and int(summary.get("totalKOs", 0)) >= 3
    if anomaly_type == "skip_login":
        return "skip_login" in rules
    if anomaly_type == "impossible_seq":
        return "impossible_seq" in rules or view.rare_transition_count > 0
    if anomaly_type == "ping_pong_loop":
        return int(summary.get("pingPongCount", 0)) >= 2
    if anomaly_type == "data_exfiltration":
        return int(summary.get("maxDownloadsIn2Minutes", 0)) >= 10
    if anomaly_type == "impossible_device_switch":
        return int(summary.get("deviceChanged", 0)) == 1
    if anomaly_type == "distributed_brute_force":
        return int(summary.get("totalKOs", 0)) >= 3
    if anomaly_type == "zombie_session":
        return "session_timeout" in rules and int(summary.get("endedAbruptly", 0)) == 1
    return False


def evaluate_worker_rules(
    events: Sequence[Mapping[str, object]],
    markov_lookup: Mapping[str, Mapping[str, float]],
) -> List[str]:
    ordered = sorted(
        events,
        key=lambda event: (
            event.get("sequenceInSession") or 0,
            event.get("_timestamp") or datetime.min.replace(tzinfo=timezone.utc),
        ),
    )
    if not ordered:
        return []

    rules: List[str] = []
    if any(_event_hour_utc(event) in {2, 3, 4} for event in ordered):
        rules.append("unusual_hour")
    if str(ordered[0].get("action") or "") not in LOGIN_ACTIONS:
        rules.append("skip_login")
    if _has_repeated_fail(ordered):
        rules.append("repeated_fail")
    if _is_rapid_fire(ordered):
        rules.append("rapid_fire")
    if _is_geo_jump(ordered):
        rules.append("geo_jump")
    if _has_session_timeout(ordered):
        rules.append("session_timeout")
    if _has_impossible_transition(ordered, markov_lookup, IMPOSSIBLE_SEQ_MIN_PROBABILITY):
        rules.append("impossible_seq")
    return rules


def count_rare_transitions(
    events: Sequence[Mapping[str, object]],
    markov_lookup: Mapping[str, Mapping[str, float]],
    threshold: float,
) -> int:
    ordered = list(
        sorted(
            events,
            key=lambda event: (
                event.get("sequenceInSession") or 0,
                event.get("_timestamp") or datetime.min.replace(tzinfo=timezone.utc),
            ),
        )
    )
    count = 0
    for previous, current in zip(ordered, ordered[1:]):
        if transition_probability(markov_lookup, previous.get("action"), current.get("action")) < threshold:
            count += 1
    return count


def transition_probability(
    markov_lookup: Mapping[str, Mapping[str, float]],
    from_action,
    to_action,
) -> float:
    return float(markov_lookup.get(str(from_action or ""), {}).get(str(to_action or ""), 0.0))


def _event_hour_utc(event: Mapping[str, object]) -> int:
    timestamp = event.get("_timestamp")
    if isinstance(timestamp, datetime):
        return timestamp.astimezone(timezone.utc).hour
    return -1


def _has_repeated_fail(events: Sequence[Mapping[str, object]]) -> bool:
    consecutive = 0
    for event in events:
        if str(event.get("status")) == "KO" and str(event.get("type")) in REPEATED_FAIL_TYPES:
            consecutive += 1
            if consecutive >= 3:
                return True
        else:
            consecutive = 0
    return False


def _is_rapid_fire(events: Sequence[Mapping[str, object]]) -> bool:
    if len(events) < RAPID_FIRE_MIN_EVENTS:
        return False
    timestamps = [event.get("_timestamp") for event in events]
    for index in range(len(timestamps) - RAPID_FIRE_MIN_EVENTS + 1):
        start = timestamps[index]
        end = timestamps[index + RAPID_FIRE_MIN_EVENTS - 1]
        if isinstance(start, datetime) and isinstance(end, datetime):
            if abs(int((end - start).total_seconds())) <= RAPID_FIRE_WINDOW_SECONDS:
                return True
    return False


def _is_geo_jump(events: Iterable[Mapping[str, object]]) -> bool:
    first_country = None
    for event in events:
        country = str(event.get("countryCode") or "").strip()
        if not country:
            continue
        if first_country is None:
            first_country = country
        elif country.lower() != first_country.lower():
            return True
    return False


def _has_session_timeout(events: Sequence[Mapping[str, object]]) -> bool:
    for previous, current in zip(events, events[1:]):
        prev_ts = previous.get("_timestamp")
        curr_ts = current.get("_timestamp")
        if isinstance(prev_ts, datetime) and isinstance(curr_ts, datetime):
            if int((curr_ts - prev_ts).total_seconds()) > SESSION_TIMEOUT_SECONDS:
                return True
    return False


def _has_impossible_transition(
    events: Sequence[Mapping[str, object]],
    markov_lookup: Mapping[str, Mapping[str, float]],
    threshold: float,
) -> bool:
    for previous, current in zip(events, events[1:]):
        probability = transition_probability(markov_lookup, previous.get("action"), current.get("action"))
        if probability < threshold:
            return True
    return False
