#!/usr/bin/env python3
"""
anomaly_test_generator.py
━━━━━━━━━━━━━━━━━━━━━━━━━━
Generates a LABELED TEST SET of anomalous and normal sequences.

Purpose
───────
After training the LSTM Autoencoder + Anomaly Type Classifier in the notebook,
you need a separate evaluation set with KNOWN ground-truth labels to measure:

  • Binary detection   — Precision / Recall / AUC-ROC of the LSTM AE
  • Type classification — Per-type accuracy of the Anomaly Type Classifier

This script generates:
  • N normal sessions per anomaly type   (e.g. 300)
  • N anomalous sessions per anomaly type (one of 6 types × N)

Output CSV has the same columns as the training data (including is_anomaly /
anomaly_type), so you can feed it directly to the evaluation cells in the
notebook.

Usage
─────
python anomaly_test_generator.py \
    --backend-apis backend-apis-actions.json \
    --actions-order actions_order-v2.json \
    --output anomaly_test_set.csv \
    --samples-per-type 300 \
    --seed 42

Then in Colab:
    uploaded = files.upload()   # upload anomaly_test_set.csv
    df_test = pd.read_csv("anomaly_test_set.csv")
    # Run evaluate_on_test_set(df_test, autoencoder, threshold, type_classifier)
"""

import argparse, csv, json, random, sys, uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# Re-use catalog from v3 dataset generator
from simulator_yearly_dataset_v3 import (
    ActionDef, UserProfile, EventPlan,
    load_actions_from_json, build_users, build_pools, initialize_navigation,
    build_event_record, serialize_for_csv, random_public_ip,
    random_foreign_ip, random_time_in_month, inter_action_seconds,
    build_normal_session, build_anomaly_session,
    ANOMALY_TYPES, CSV_COLUMNS, format_ts, weighted_choice,
    EU_GEO_PROFILE, BROWSER_POOL, PERSONAS,
)

# ─── Anomaly definition: precise behavioral signatures ────────────────────────
#
# Each anomaly type has a CLEAR signature that differs from normal:
#
# rapid_fire   : delta_seconds < 8 for ALL events in session
# unusual_hour : createdAt.hour ∈ [2, 4]
# geo_jump     : IP changes to a "foreign" prefix after step 2
# repeated_fail: ≥3 consecutive KO on BANKING or LOGGING actions
# skip_login   : first action is NOT a login (Connexion*/Activation*)
# impossible_seq: Déconnexion/SSO Disconnect appears in the middle,
#                 followed by more actions in the SAME session
#
# These signatures are what the LSTM AE reconstruction error will be high for
# (because the model never saw them during training on normal data).
# The Type Classifier learns to associate each signature with its label.

def generate_test_session(
    anomaly_type: Optional[str],       # None = normal
    user: UserProfile,
    month_start: datetime,
    month_end: datetime,
    catalog: Dict[str, ActionDef],
    rng: random.Random,
    session_number: int,
) -> List[EventPlan]:
    """
    For anomaly_type=None → generates a normal session.
    For anomaly_type!=None → generates a session with THAT specific anomaly.
    """
    if anomaly_type is None:
        return build_normal_session(user, month_start, month_end, catalog, rng, session_number)
    else:
        return build_anomaly_session(user, month_start, month_end, catalog, rng,
                                     session_number, anomaly_type=anomaly_type)


def parse_args(argv):
    p = argparse.ArgumentParser(description="Generate labeled anomaly test set")
    p.add_argument("--backend-apis",     required=True)
    p.add_argument(
        "--actions-order",
        default=str(Path(__file__).with_name("actions_order-v2.json")),
    )
    p.add_argument("--output",           default="anomaly_test_set.csv")
    p.add_argument("--samples-per-type", type=int, default=300,
                   help="Number of sessions per anomaly type (same number of normal sessions)")
    p.add_argument("--seed",             type=int, default=42)
    p.add_argument("--created-at-format", choices=["java", "kibana"], default="kibana")
    return p.parse_args(argv)


def main(argv) -> int:
    args = parse_args(argv)
    rng  = random.Random(args.seed)

    catalog = load_actions_from_json(args.backend_apis)
    print(f"Loaded {len(catalog)} actions.")
    nav_summary = initialize_navigation(args.actions_order, args.backend_apis, catalog)
    print(
        "Loaded navigation from "
        f"{args.actions_order} "
        f"({nav_summary['auth_steps_resolved']} auth steps, "
        f"{nav_summary['json_routes_with_actions']} JSON routes with tracked actions)"
    )

    pools = build_pools()
    # Create a small user pool for the test set
    users = build_users(rng, 50, 100, pools)

    # Fixed month window for test data (use a different period than training)
    month_start = datetime(2025, 11, 1, tzinfo=timezone.utc)
    month_end   = datetime(2025, 11, 30, 23, 59, 59, tzinfo=timezone.utc)

    N = args.samples_per_type
    all_events: List[Dict] = []
    session_counter = 0

    # ── Generate N sessions for each anomaly type ──────────────────────────────
    for atype in ANOMALY_TYPES:
        print(f"  Generating {N} anomalous sessions: {atype} ...")
        for _ in range(N):
            user = rng.choice(users)
            session_counter += 1
            plans = generate_test_session(atype, user, month_start, month_end,
                                          catalog, rng, session_counter)
            if not plans:
                continue
            true_len = len(plans)
            for idx, plan in enumerate(plans):
                ev = build_event_record(
                    plan,
                    user,
                    true_len,
                    args.created_at_format,
                    rng,
                    clear_session_after=(idx == true_len - 1),
                )
                all_events.append(ev)

    # ── Generate N × len(ANOMALY_TYPES) normal sessions ────────────────────────
    n_normal = N * len(ANOMALY_TYPES)
    print(f"  Generating {n_normal} normal sessions ...")
    for _ in range(n_normal):
        user = rng.choice(users)
        session_counter += 1
        plans = generate_test_session(None, user, month_start, month_end,
                                      catalog, rng, session_counter)
        if not plans:
            continue
        true_len = len(plans)
        for idx, plan in enumerate(plans):
            ev = build_event_record(
                plan,
                user,
                true_len,
                args.created_at_format,
                rng,
                clear_session_after=(idx == true_len - 1),
            )
            all_events.append(ev)

    # ── Write CSV ──────────────────────────────────────────────────────────────
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for ev in all_events:
            row = serialize_for_csv(ev)
            writer.writerow({c: row.get(c, "") for c in CSV_COLUMNS})

    by_type: Dict[str, int] = defaultdict(int)
    for ev in all_events:
        key = ev.get("anomaly_type") or "normal"
        by_type[key] += 1

    print(json.dumps({
        "total_events":    len(all_events),
        "events_by_label": dict(by_type),
    }, ensure_ascii=False, indent=2))
    print(f"\nSaved -> {out_path.resolve()}")
    print("\nUsage in Colab:")
    print("  uploaded = files.upload()   # upload anomaly_test_set.csv")
    print("  evaluate_on_test_set('anomaly_test_set.csv', autoencoder, threshold, type_classifier)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
