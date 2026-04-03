"""
Train a lightweight XGBoost session-level anomaly scorer from raw audit data.

This script is separate from the runtime ONNX models. It is intended for
experimentation around aggregate session classification and exports both the
model file and the feature list used during training.
"""

import pandas as pd
import numpy as np
import xgboost as xgb
import json
import os
from sklearn.model_selection import train_test_split


def train_session_scorer(csv_path, output_model_path, output_features_path):
    print(f"Reading data from {csv_path}...")
    df = pd.read_csv(csv_path)

    # 1. Feature Engineering per session
    print("Aggregating features at the session level...")
    df['CREATED_AT'] = pd.to_datetime(df['CREATED_AT'])

    # Identify sequence anomalies/ko rates per session
    df['is_ko'] = (df['STATUS'] == 'KO').astype(int)

    # Collapse raw events into one aggregate row per session.
    agg = df.groupby('SESSION_ID').agg(
        session_duration=('CREATED_AT', lambda x: (x.max() - x.min()).total_seconds()),
        session_length=('ACTION', 'count'),
        unique_action_count=('ACTION', 'nunique'),
        ko_count=('is_ko', 'sum'),
        hour_of_first_event=('CREATED_AT', lambda x: x.min().hour),
        # You can add more complex features here
    ).reset_index()

    agg['ko_rate'] = agg['ko_count'] / agg['session_length']

    # In a real environment, you need an 'is_anomaly' label. 
    # For this mock script, we synthetically generate it based on expert rules
    # IF duration > 3600 or ko_rate > 0.5 or session_length > 50 -> 1 else 0
    agg['is_anomaly'] = ((agg['session_duration'] > 3600) | 
                         (agg['ko_rate'] > 0.5) | 
                         (agg['session_length'] > 50)).astype(int)

    # Separate the feature matrix from the derived target label.
    features = ['session_duration', 'session_length', 'unique_action_count', 'ko_rate', 'hour_of_first_event']
    X = agg[features]
    y = agg['is_anomaly']

    print(f"Training on {len(X)} sessions. Anomalies: {y.sum()}")

    # Keep a small holdout split to monitor training quality.
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    dtrain = xgb.DMatrix(X_train, label=y_train, feature_names=features)
    dtest = xgb.DMatrix(X_test, label=y_test, feature_names=features)

    params = {
        'max_depth': 4,
        'eta': 0.1,
        'objective': 'binary:logistic',
        'eval_metric': 'auc'
    }

    evals = [(dtrain, 'train'), (dtest, 'eval')]

    print("Training XGBoost session scorer...")
    model = xgb.train(params, dtrain, num_boost_round=100, evals=evals, early_stopping_rounds=10)

    print(f"Saving model to {output_model_path}...")
    os.makedirs(os.path.dirname(output_model_path), exist_ok=True)
    model.save_model(output_model_path)

    # Save the feature configuration
    feature_config = {
        "features": features
    }
    with open(output_features_path, 'w') as f:
        json.dump(feature_config, f, indent=2)


if __name__ == "__main__":
    # Default output paths keep the trained artifacts beside the other AI resources.
    csv_file = "src/main/resources/AI/audit_trail_2025.csv"
    output_model = "src/main/resources/AI/session_scorer_xgboost.json"
    output_features = "src/main/resources/AI/session_scorer_features.json"
    train_session_scorer(csv_file, output_model, output_features)
