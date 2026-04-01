#!/usr/bin/env python3
"""Graph-aware yearly dataset generator.

Key improvements over the previous generator:
- uses actions_order-v2.json as the normal navigation state machine
- generates API-level fields (apiCall, routeState, prevApiCall, transitionValid)
- separates structural and behavioural anomalies
- keeps compatibility with the previous CSV schema while extending it
"""

from __future__ import annotations

import argparse
import csv
import json
import random
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

from flow_graph_runtime import build_users, build_session_plan, load_action_order_graph, planned_step_to_event

CSV_COLUMNS = [
    "id",
    "insuredId",
    "status",
    "sessionId",
    "action",
    "httpCode",
    "ip",
    "userAgent",
    "requestData",
    "requestReturn",
    "createdAt",
    "type",
    "environmentId",
    "device",
    "companyIdList",
    "companyGroupIdList",
    "insurerIdList",
    "companySectionIdList",
    "insurerCodeIdList",
    "healthcareNetworkIdList",
    "domainIdList",
    "subType",
    "countryCode",
    "city",
    "month",
    "sessionNumber",
    "sequenceInSession",
    "sessionLength",
    "is_anomaly",
    "anomaly_type",
    "routeState",
    "frontendAction",
    "apiCall",
    "prevApiCall",
    "transitionValid",
    "flowFamily",
    "expectedNextStates",
]

WEEKDAY_WEIGHTS = [1.25, 1.25, 1.20, 1.15, 1.05, 0.50, 0.40]


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate one year of graph-aware audit-trail data")
    parser.add_argument("--actions-order-file", default="actions_order-v2.json")
    parser.add_argument("--output", default="audit_trail_2025_graph.csv")
    parser.add_argument("--start-date", default="2025-01-01")
    parser.add_argument("--end-date", default="2025-12-31")
    parser.add_argument("--users", type=int, default=250)
    parser.add_argument("--actions-per-month", type=int, default=12000)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--structural-anomaly-rate", type=float, default=0.03)
    parser.add_argument("--behavioural-anomaly-rate", type=float, default=0.05)
    parser.add_argument("--created-at-format", choices=["java", "kibana"], default="kibana")
    parser.add_argument("--file-format", choices=["csv", "jsonl"], default="csv")
    return parser.parse_args(argv)


def parse_date(value: str) -> datetime:
    return datetime.fromisoformat(value).replace(tzinfo=timezone.utc)


def month_range(start: datetime, end: datetime) -> List[Tuple[datetime, datetime]]:
    months: List[Tuple[datetime, datetime]] = []
    cursor = datetime(start.year, start.month, 1, tzinfo=timezone.utc)
    while cursor <= end:
        if cursor.month == 12:
            next_month = datetime(cursor.year + 1, 1, 1, tzinfo=timezone.utc)
        else:
            next_month = datetime(cursor.year, cursor.month + 1, 1, tzinfo=timezone.utc)
        months.append((cursor, min(end, next_month - timedelta(seconds=1))))
        cursor = next_month
    return months


def weighted_choice(items, rng: random.Random):
    total = sum(weight for _, weight in items)
    pick = rng.random() * total
    upto = 0.0
    for item, weight in items:
        upto += weight
        if upto >= pick:
            return item
    return items[-1][0]


def random_time_in_month(month_start: datetime, month_end: datetime, rng: random.Random) -> datetime:
    days = []
    cursor = month_start
    while cursor <= month_end:
        days.append(cursor)
        cursor += timedelta(days=1)
    day_weights = []
    for day in days:
        weight = WEEKDAY_WEIGHTS[day.weekday()]
        if day.day in {1, 2, 3, 28, 29, 30, 31}:
            weight *= 1.08
        day_weights.append(weight)
    chosen_day = weighted_choice(list(zip(days, day_weights)), rng)
    hour_peaks = [(8.5, 0.30), (10.5, 0.12), (12.5, 0.18), (15.0, 0.12), (18.0, 0.28)]
    peak_hour = weighted_choice(hour_peaks, rng)
    hour = int(peak_hour)
    minute = int((peak_hour - hour) * 60)
    dt = chosen_day.replace(hour=hour, minute=minute, second=0, microsecond=0)
    dt += timedelta(minutes=rng.randint(-45, 45), seconds=rng.randint(0, 59))
    if dt < month_start:
        dt = month_start + timedelta(minutes=5)
    if dt > month_end:
        dt = month_end - timedelta(minutes=5)
    return dt


def choose_session_length(remaining: int, rng: random.Random) -> int:
    if remaining <= 4:
        return remaining
    choices = []
    for length in range(4, min(13, remaining + 1)):
        weight = {
            4: 0.08, 5: 0.14, 6: 0.18, 7: 0.18, 8: 0.16,
            9: 0.10, 10: 0.07, 11: 0.05, 12: 0.04,
        }.get(length, 0.03)
        choices.append((length, weight))
    chosen = weighted_choice(choices, rng)
    if remaining - chosen in {1, 2, 3} and remaining > chosen:
        return max(4, chosen + (remaining - chosen))
    return chosen


def serialize_lists(row: Dict[str, object]) -> Dict[str, object]:
    formatted = dict(row)
    for key in [
        "companyIdList", "companyGroupIdList", "insurerIdList", "companySectionIdList",
        "insurerCodeIdList", "healthcareNetworkIdList", "domainIdList",
    ]:
        if key in formatted:
            formatted[key] = json.dumps(formatted[key], ensure_ascii=True)
    return formatted


def write_output(events: List[Dict[str, object]], output: str, file_format: str) -> None:
    path = Path(output)
    path.parent.mkdir(parents=True, exist_ok=True)
    if file_format == "csv":
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=CSV_COLUMNS)
            writer.writeheader()
            for event in events:
                row = serialize_lists(event)
                writer.writerow({col: row.get(col, "") for col in CSV_COLUMNS})
    else:
        with path.open("w", encoding="utf-8") as fh:
            for event in events:
                fh.write(json.dumps(event, ensure_ascii=True) + "\n")


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    rng = random.Random(args.seed)
    graph = load_action_order_graph(args.actions_order_file)
    users = build_users(rng, args.users)

    start_date = parse_date(args.start_date)
    end_date = parse_date(args.end_date) + timedelta(hours=23, minutes=59, seconds=59)
    months = month_range(start_date, end_date)

    if args.actions_per_month % args.users != 0:
        raise SystemExit("actions-per-month must be divisible by users")

    target_per_user_per_month = args.actions_per_month // args.users
    all_events: List[Dict[str, object]] = []

    for month_start, month_end in months:
        month_events: List[Tuple[datetime, Dict[str, object]]] = []
        for user in users:
            remaining = target_per_user_per_month
            while remaining > 0:
                session_len = choose_session_length(remaining, rng)
                plan_start = random_time_in_month(month_start, month_end, rng)
                plan = build_session_plan(
                    graph=graph,
                    user=user,
                    start_time=plan_start,
                    desired_len=session_len,
                    rng=rng,
                    structural_anomaly_rate=args.structural_anomaly_rate,
                    behavioural_anomaly_rate=args.behavioural_anomaly_rate,
                )
                session_length = len(plan)
                for step in plan:
                    event = planned_step_to_event(step, user, graph, rng, created_at_format=args.created_at_format)
                    event["sessionLength"] = session_length
                    month_events.append((step.timestamp, event))
                remaining -= session_length
        month_events.sort(key=lambda item: item[0])
        all_events.extend(event for _, event in month_events)

    write_output(all_events, args.output, args.file_format)

    anomaly_counts = defaultdict(int)
    for event in all_events:
        if event.get("is_anomaly"):
            anomaly_counts[event.get("anomaly_type", "unknown")] += 1

    summary = {
        "output": str(Path(args.output).resolve()),
        "total_events": len(all_events),
        "normal_events": sum(1 for e in all_events if not e.get("is_anomaly")),
        "anomaly_events": sum(1 for e in all_events if e.get("is_anomaly")),
        "unique_actions": len({e.get("action") for e in all_events}),
        "unique_api_calls": len({e.get("apiCall") for e in all_events}),
        "months": len(months),
        "users": args.users,
        "anomaly_breakdown": dict(sorted(anomaly_counts.items())),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(__import__("sys").argv[1:]))
