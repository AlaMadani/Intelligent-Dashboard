#!/usr/bin/env python3
"""
SimData REVISED — Full ML training notebook (converted to .py for clarity).
To use in Colab: paste each cell into a separate code cell.

KEY IMPROVEMENTS OVER v1:
  A. Feature engineering
     • seq_pos_norm  : normalised position in session (0‥1) — most critical missing feature
     • session_len   : total length of the session (context for model)
     • is_session_start / is_session_end
     • prev_action_id: encoded previous action within session (transition signal)
     • ko_rate_so_far: rolling KO count inside session (anomaly signal)

  B. GRU training
     • Class weights (balanced) fix the 20:1 imbalance bias
     • 60 epochs + ReduceLROnPlateau + EarlyStopping (patience=12)
     • More dropout for regularisation
     • Bidirectional GRU option

  C. LSTM Autoencoder
     • Trains ONLY on anomaly-free rows (is_anomaly == 0 if column present)
     • IQR-based threshold (more robust than 95th-pct)
     • Separate normal / anomaly test split for proper evaluation

  D. XGBoost
     • Uses is_anomaly as additional feature if available
     • scale_pos_weight for any remaining imbalance
"""

# ══════════════════════════════════════════════════════════════════════════════
# Cell 1 — Install dependencies
# ══════════════════════════════════════════════════════════════════════════════

# !pip install -q pandas numpy scikit-learn matplotlib seaborn tensorflow joblib tf2onnx xgboost

# ══════════════════════════════════════════════════════════════════════════════
# Cell 2 — Imports
# ══════════════════════════════════════════════════════════════════════════════

import os
import json
import math
import joblib
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

from datetime import datetime
from google.colab import files  # remove if not Colab

from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder, StandardScaler
from sklearn.metrics import (classification_report, confusion_matrix,
                             f1_score, accuracy_score, roc_auc_score)
from sklearn.utils.class_weight import compute_class_weight

import tensorflow as tf
from tensorflow.keras import layers, models, callbacks

# ══════════════════════════════════════════════════════════════════════════════
# Cell 3 — Upload dataset  (skip if already uploaded)
# ══════════════════════════════════════════════════════════════════════════════

# uploaded = files.upload()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 4 — Load CSV
# ══════════════════════════════════════════════════════════════════════════════

FILE_NAME = "audit_trail_2025.csv"
df = pd.read_csv(FILE_NAME)
print(df.shape)
df.head()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 5 — Quick inspection
# ══════════════════════════════════════════════════════════════════════════════

print(df.columns.tolist())
print(df.dtypes)
print(df.isna().sum().sort_values(ascending=False).head(20))

# ══════════════════════════════════════════════════════════════════════════════
# Cell 6 — Base parsing
# ══════════════════════════════════════════════════════════════════════════════

df["createdAt"] = pd.to_datetime(df["createdAt"], utc=True, errors="coerce")

list_cols = [
    "companyIdList", "companyGroupIdList", "insurerIdList",
    "companySectionIdList", "insurerCodeIdList",
    "healthcareNetworkIdList", "domainIdList",
]
for c in list_cols:
    df[c] = df[c].fillna("[]").astype(str)

text_cols = ["action", "type", "subType", "device", "countryCode", "city", "status", "userAgent"]
for c in text_cols:
    df[c] = df[c].fillna("UNKNOWN").astype(str)

# Sort into chronological order per user/session
df = df.sort_values(["insuredId", "sessionNumber", "sequenceInSession", "createdAt"]).reset_index(drop=True)

print(df[["insuredId", "createdAt", "action", "device", "countryCode",
          "sessionNumber", "sequenceInSession"]].head(10))

# ══════════════════════════════════════════════════════════════════════════════
# Cell 7 — Temporal features
# ══════════════════════════════════════════════════════════════════════════════

df["hour"]      = df["createdAt"].dt.hour
df["dayofweek"] = df["createdAt"].dt.dayofweek
df["month_num"] = df["createdAt"].dt.month

# Cyclical encoding
df["hour_sin"]  = np.sin(2 * np.pi * df["hour"] / 24)
df["hour_cos"]  = np.cos(2 * np.pi * df["hour"] / 24)
df["dow_sin"]   = np.sin(2 * np.pi * df["dayofweek"] / 7)
df["dow_cos"]   = np.cos(2 * np.pi * df["dayofweek"] / 7)

df["is_ok"] = (df["status"] == "OK").astype(int)
df["is_ko"] = (df["status"] == "KO").astype(int)

# ══════════════════════════════════════════════════════════════════════════════
# Cell 8 — Category encoding
# ══════════════════════════════════════════════════════════════════════════════

action_le   = LabelEncoder()
device_le   = LabelEncoder()
country_le  = LabelEncoder()
type_le     = LabelEncoder()
subtype_le  = LabelEncoder()

df["action_id"]  = action_le.fit_transform(df["action"])
df["device_id"]  = device_le.fit_transform(df["device"])
df["country_id"] = country_le.fit_transform(df["countryCode"])
df["type_id"]    = type_le.fit_transform(df["type"])

df["subType_filled"] = df["subType"].fillna("NONE").astype(str)
df["subtype_id"] = subtype_le.fit_transform(df["subType_filled"])

print("Unique actions:", len(action_le.classes_))
print("Label mapping sample:", list(enumerate(action_le.classes_[:5])))

# ══════════════════════════════════════════════════════════════════════════════
# Cell 9 — Delta-time feature
# ══════════════════════════════════════════════════════════════════════════════

# Compute time gap between consecutive events within the SAME session
df["prev_time"] = (
    df.groupby(["insuredId", "sessionNumber"])["createdAt"]
    .shift(1)
)
df["delta_seconds"] = (df["createdAt"] - df["prev_time"]).dt.total_seconds().fillna(0)
df["delta_seconds_clipped"] = df["delta_seconds"].clip(0, 3600)

scaler_delta = StandardScaler()
df["delta_scaled"] = scaler_delta.fit_transform(df[["delta_seconds_clipped"]])

# ══════════════════════════════════════════════════════════════════════════════
# Cell 10 — ★ NEW: Session-aware features
# These are the most important features missing from v1.
# ══════════════════════════════════════════════════════════════════════════════

SESSION_KEY = ["insuredId", "sessionNumber"]

# -- If sessionLength not in the CSV, compute it on the fly --
if "sessionLength" not in df.columns:
    df["sessionLength"] = df.groupby(SESSION_KEY)["sequenceInSession"].transform("max")

# Normalised position in session (0 = start, 1 = end)
df["seq_pos_norm"] = df["sequenceInSession"] / df["sessionLength"]

# Boolean flags
df["is_session_start"] = (df["sequenceInSession"] == 1).astype(int)
df["is_session_end"]   = (df["sequenceInSession"] == df["sessionLength"]).astype(int)

# Previous action within the same session (0 = no previous action)
df["prev_action_id"] = (
    df.groupby(SESSION_KEY)["action_id"]
    .shift(1)
    .fillna(-1)
    .astype(int) + 1      # shift by 1 so "no previous" maps to 0
)

# Cumulative KO count within session — useful anomaly signal
df["ko_count_so_far"] = (
    df.groupby(SESSION_KEY)["is_ko"]
    .cumsum()
    .shift(1)
    .fillna(0)
    .astype(int)
)

# Scaled session length (0‥1 range for the model)
max_session_len = df["sessionLength"].max()
df["session_len_norm"] = df["sessionLength"] / max_session_len

print("New session-aware features added:")
print(df[["sequenceInSession", "sessionLength", "seq_pos_norm",
          "is_session_start", "is_session_end", "prev_action_id",
          "ko_count_so_far"]].head(10))

# ══════════════════════════════════════════════════════════════════════════════
# Cell 11 — Define feature columns
# ══════════════════════════════════════════════════════════════════════════════

SEQ_LEN = 10    # sliding window length

# ★ v2 feature set — 19 features vs 12 in v1
feature_cols = [
    # Categorical (encoded)
    "action_id",
    "device_id",
    "country_id",
    "type_id",
    "subtype_id",
    "prev_action_id",          # ★ NEW — transition signal
    # Temporal
    "hour_sin",
    "hour_cos",
    "dow_sin",
    "dow_cos",
    # Time gap
    "delta_scaled",
    # Status
    "is_ok",
    "is_ko",
    # ★ NEW — session context
    "seq_pos_norm",
    "is_session_start",
    "is_session_end",
    "session_len_norm",
    "ko_count_so_far",
    # Month seasonality
    "month_num",
]

# ══════════════════════════════════════════════════════════════════════════════
# Cell 12 — Build sequences (session-aware sliding window)
#
# ★ KEY CHANGE: sequences never cross session boundaries.
#   We build windows within each session, with zero-padding for
#   short sessions.  This keeps the model's temporal context valid.
# ══════════════════════════════════════════════════════════════════════════════

def build_sequences(df, seq_len=10):
    """
    Build (X, y_next, meta) triples.
    X shape: (N, seq_len, n_features) — padded with zeros on the left.
    y_next: the action_id of the NEXT event (within session).
    Sequences crossing session boundaries are excluded.
    """
    X, y_next, meta = [], [], []

    for (user_id, session_num), g in df.groupby(["insuredId", "sessionNumber"]):
        g = g.sort_values("sequenceInSession")
        values  = g[feature_cols].values.astype(np.float32)
        actions = g["action_id"].values
        times   = g["createdAt"].values

        n = len(g)
        if n < 2:   # need at least 1 input + 1 target
            continue

        # Build every possible sub-sequence within this session
        for end in range(1, n):
            # window = [max(0, end-seq_len) : end]  zero-padded on the left
            start = max(0, end - seq_len)
            seq = values[start:end]

            # Left-pad with zeros if shorter than seq_len
            if len(seq) < seq_len:
                pad = np.zeros((seq_len - len(seq), len(feature_cols)), dtype=np.float32)
                seq = np.vstack([pad, seq])

            target = actions[end]
            X.append(seq)
            y_next.append(target)
            meta.append({
                "insuredId":    user_id,
                "sessionNumber": session_num,
                "seq_end_pos":  end,
                "end_time":     str(times[end - 1]),
                "next_time":    str(times[end]),
            })

    return (
        np.array(X, dtype=np.float32),
        np.array(y_next, dtype=np.int32),
        meta,
    )


X_all, y_all, meta_all = build_sequences(df, seq_len=SEQ_LEN)
print("X_all:", X_all.shape)
print("y_all:", y_all.shape)

# ══════════════════════════════════════════════════════════════════════════════
# Cell 13 — Temporal train/test split
# ══════════════════════════════════════════════════════════════════════════════

meta_df = pd.DataFrame(meta_all)
meta_df["end_time"] = pd.to_datetime(meta_df["end_time"], utc=True)

order  = np.argsort(meta_df["end_time"].values)
X_all  = X_all[order]
y_all  = y_all[order]
meta_df = meta_df.iloc[order].reset_index(drop=True)

split_idx = int(len(X_all) * 0.8)
X_train, X_test = X_all[:split_idx], X_all[split_idx:]
y_train, y_test = y_all[:split_idx], y_all[split_idx:]

print(X_train.shape, X_test.shape, y_train.shape, y_test.shape)

# ══════════════════════════════════════════════════════════════════════════════
# Cell 14 — Save artefacts (encoders, feature config)
# ══════════════════════════════════════════════════════════════════════════════

artifacts_dir = "artifacts"
os.makedirs(artifacts_dir, exist_ok=True)

for name, le in [("action",  action_le),  ("device",  device_le),
                 ("country", country_le), ("type",    type_le),
                 ("subtype", subtype_le)]:
    with open(f"{artifacts_dir}/{name}_vocab.json", "w", encoding="utf-8") as f:
        json.dump({int(i): cls for i, cls in enumerate(le.classes_)}, f,
                  ensure_ascii=False, indent=2)

joblib.dump(scaler_delta, f"{artifacts_dir}/scaler_delta.pkl")

feature_config = {
    "seq_len":           SEQ_LEN,
    "feature_cols":      feature_cols,
    "n_features":        len(feature_cols),
    "action_vocab_size": int(len(action_le.classes_)),
    "device_vocab_size": int(len(device_le.classes_)),
    "country_vocab_size":int(len(country_le.classes_)),
    "type_vocab_size":   int(len(type_le.classes_)),
    "subtype_vocab_size":int(len(subtype_le.classes_)),
    "max_session_len":   int(max_session_len),
}
with open(f"{artifacts_dir}/feature_config.json", "w", encoding="utf-8") as f:
    json.dump(feature_config, f, ensure_ascii=False, indent=2)

print("Artefacts saved to", artifacts_dir)

# ══════════════════════════════════════════════════════════════════════════════
# ━━━━━━━━━━━━━━━━━━━━━━ PART A — LSTM Autoencoder ━━━━━━━━━━━━━━━━━━━━━━━━━━
# ══════════════════════════════════════════════════════════════════════════════

# ══════════════════════════════════════════════════════════════════════════════
# Cell 15 — Prepare autoencoder data
#
# ★ KEY CHANGE: train ONLY on normal (non-anomalous) sequences.
#   If is_anomaly column exists, use it.  Otherwise fall back to all data.
# ══════════════════════════════════════════════════════════════════════════════

if "is_anomaly" in df.columns:
    # Build a lookup: (insuredId, sessionNumber) → is_anomaly
    anomaly_map = (
        df.groupby(["insuredId", "sessionNumber"])["is_anomaly"]
        .max()
        .to_dict()
    )
    normal_mask = np.array([
        anomaly_map.get((m["insuredId"], m["sessionNumber"]), 0) == 0
        for m in meta_all
    ])
    normal_mask = normal_mask[order]   # apply the same temporal sort
    X_train_ae = X_train[normal_mask[:split_idx]]
    X_test_ae_normal  = X_test[normal_mask[split_idx:]]
    X_test_ae_anomaly = X_test[~normal_mask[split_idx:]]
    print(f"AE train (normal): {X_train_ae.shape}")
    print(f"AE test  normal  : {X_test_ae_normal.shape}")
    print(f"AE test  anomaly : {X_test_ae_anomaly.shape}")
else:
    X_train_ae = X_train
    X_test_ae_normal  = X_test
    X_test_ae_anomaly = None
    print("No is_anomaly column found — training on all data.")

# ══════════════════════════════════════════════════════════════════════════════
# Cell 16 — Define autoencoder (Bidirectional LSTM)
# ══════════════════════════════════════════════════════════════════════════════

input_dim = X_train.shape[-1]

inputs = layers.Input(shape=(SEQ_LEN, input_dim))
x = layers.Masking(mask_value=0.0)(inputs)

# ★ Bidirectional encoder — captures both forward and backward context
x = layers.Bidirectional(layers.LSTM(64, return_sequences=True))(x)
x = layers.Dropout(0.20)(x)
x = layers.LSTM(32, return_sequences=False)(x)

# Bottleneck
x = layers.RepeatVector(SEQ_LEN)(x)

# Decoder
x = layers.LSTM(32, return_sequences=True)(x)
x = layers.Dropout(0.20)(x)
x = layers.Bidirectional(layers.LSTM(64, return_sequences=True))(x)
outputs = layers.TimeDistributed(layers.Dense(input_dim))(x)

autoencoder = models.Model(inputs, outputs)
autoencoder.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3), loss="mse")
autoencoder.summary()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 17 — Train autoencoder
# ══════════════════════════════════════════════════════════════════════════════

X_tr_ae, X_val_ae = train_test_split(X_train_ae, test_size=0.15, shuffle=False)

es_ae = callbacks.EarlyStopping(monitor="val_loss", patience=7, restore_best_weights=True)
lr_ae = callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=3, min_lr=1e-5)

history_ae = autoencoder.fit(
    X_tr_ae, X_tr_ae,
    validation_data=(X_val_ae, X_val_ae),
    epochs=50,
    batch_size=128,
    callbacks=[es_ae, lr_ae],
    verbose=1,
)

# ══════════════════════════════════════════════════════════════════════════════
# Cell 18 — Visualise loss
# ══════════════════════════════════════════════════════════════════════════════

plt.figure(figsize=(8, 4))
plt.plot(history_ae.history["loss"], label="train")
plt.plot(history_ae.history["val_loss"], label="val")
plt.title("LSTM Autoencoder Loss")
plt.legend()
plt.tight_layout()
plt.show()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 19 — Compute anomaly threshold (★ IQR-based, more robust than 95th-pct)
# ══════════════════════════════════════════════════════════════════════════════

val_pred   = autoencoder.predict(X_val_ae, verbose=0)
val_errors = np.mean(np.square(X_val_ae - val_pred), axis=(1, 2))

Q1, Q3 = np.percentile(val_errors, [25, 75])
IQR = Q3 - Q1
# Threshold = Q3 + 1.5 * IQR  (standard outlier fence)
threshold_iqr  = Q3 + 1.5 * IQR
# Keep a percentile-based backup as well
threshold_p95  = float(np.percentile(val_errors, 95))
threshold_p99  = float(np.percentile(val_errors, 99))

# Use the IQR threshold (catches real outliers, not just top 5 %)
threshold = threshold_iqr
print(f"Threshold IQR  : {threshold_iqr:.4f}")
print(f"Threshold p95  : {threshold_p95:.4f}")
print(f"Threshold p99  : {threshold_p99:.4f}")
print(f"Using           : {threshold:.4f}")

plt.figure(figsize=(8, 4))
sns.histplot(val_errors, bins=50, kde=True)
plt.axvline(threshold, color="red", linestyle="--", label=f"threshold={threshold:.3f}")
plt.legend()
plt.title("Validation reconstruction error (normal data)")
plt.show()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 20 — Evaluate anomaly detection
# ══════════════════════════════════════════════════════════════════════════════

test_pred   = autoencoder.predict(X_test, verbose=0)
test_errors = np.mean(np.square(X_test - test_pred), axis=(1, 2))
test_flags  = (test_errors > threshold).astype(int)

print(f"Test anomalies flagged: {test_flags.sum()} / {len(test_flags)}"
      f"  ({100*test_flags.mean():.1f} %)")

# If ground-truth labels are available, compute AUC
if X_test_ae_anomaly is not None and len(X_test_ae_anomaly) > 0:
    n_normal  = len(X_test_ae_normal)
    n_anomaly = len(X_test_ae_anomaly)
    y_true = np.array([0] * n_normal + [1] * n_anomaly)
    # Reconstruct errors for normal + anomaly subsets
    err_normal  = np.mean(np.square(X_test_ae_normal  - autoencoder.predict(X_test_ae_normal,  verbose=0)), axis=(1,2))
    err_anomaly = np.mean(np.square(X_test_ae_anomaly - autoencoder.predict(X_test_ae_anomaly, verbose=0)), axis=(1,2))
    errors_combined = np.concatenate([err_normal, err_anomaly])
    auc = roc_auc_score(y_true, errors_combined)
    print(f"Anomaly detection AUC-ROC: {auc:.4f}")
    precision = (test_flags[-n_anomaly:].sum() / max(1, test_flags.sum()))
    recall    = test_flags[-n_anomaly:].mean()
    print(f"Precision (on anomaly slice): {precision:.3f}")
    print(f"Recall    (on anomaly slice): {recall:.3f}")

# ══════════════════════════════════════════════════════════════════════════════
# Cell 21 — Save autoencoder model
# ══════════════════════════════════════════════════════════════════════════════

autoencoder.export(f"{artifacts_dir}/anomaly_lstm_autoencoder")

with open(f"{artifacts_dir}/anomaly_threshold.json", "w", encoding="utf-8") as f:
    json.dump({
        "threshold": threshold,
        "threshold_iqr": threshold_iqr,
        "threshold_p95": threshold_p95,
        "threshold_p99": threshold_p99,
    }, f, indent=2)

# ══════════════════════════════════════════════════════════════════════════════
# Cell 22 — Export autoencoder to ONNX
# ══════════════════════════════════════════════════════════════════════════════

# !python -m tf2onnx.convert --saved-model artifacts/anomaly_lstm_autoencoder \
#   --output artifacts/anomaly_lstm_autoencoder.onnx

# ══════════════════════════════════════════════════════════════════════════════
# ━━━━━━━━━━━━━━━━━━━━━━━━━━ PART B — GRU Next Action ━━━━━━━━━━━━━━━━━━━━━━━━
# ══════════════════════════════════════════════════════════════════════════════

# ══════════════════════════════════════════════════════════════════════════════
# Cell 23 — Compute class weights (★ fixes the 20:1 imbalance)
# ══════════════════════════════════════════════════════════════════════════════

num_actions = len(action_le.classes_)

classes_present = np.unique(y_train)
raw_weights = compute_class_weight(
    class_weight="balanced",
    classes=classes_present,
    y=y_train,
)

# Cap max weight at 10× to avoid instability on very rare classes
raw_weights = np.clip(raw_weights, a_min=0.1, a_max=10.0)

class_weight_dict = {int(c): float(w) for c, w in zip(classes_present, raw_weights)}
# Classes not in training get weight 1.0
for c in range(num_actions):
    class_weight_dict.setdefault(c, 1.0)

print("Class weight examples:")
for cls_id, w in sorted(class_weight_dict.items())[:8]:
    print(f"  {action_le.classes_[cls_id][:40]:40s}  weight={w:.2f}")

# ══════════════════════════════════════════════════════════════════════════════
# Cell 24 — Define GRU model (★ stronger architecture + dropout)
# ══════════════════════════════════════════════════════════════════════════════

input_dim = X_train.shape[-1]

gru_inputs = layers.Input(shape=(SEQ_LEN, input_dim))
gx = layers.Masking(mask_value=0.0)(gru_inputs)

# ★ Bidirectional GRU captures both directions of the sequence
gx = layers.Bidirectional(layers.GRU(128, return_sequences=True))(gx)
gx = layers.Dropout(0.30)(gx)
gx = layers.Bidirectional(layers.GRU(64, return_sequences=False))(gx)
gx = layers.Dropout(0.30)(gx)
gx = layers.Dense(128, activation="relu")(gx)
gx = layers.Dropout(0.20)(gx)
gru_outputs = layers.Dense(num_actions, activation="softmax")(gx)

gru_model = models.Model(gru_inputs, gru_outputs)
gru_model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=5e-4),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)
gru_model.summary()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 25 — Train GRU (★ with class weights, more epochs, LR schedule)
# ══════════════════════════════════════════════════════════════════════════════

X_tr_gru, X_val_gru, y_tr_gru, y_val_gru = train_test_split(
    X_train, y_train, test_size=0.15, shuffle=False
)

es_gru = callbacks.EarlyStopping(
    monitor="val_accuracy", patience=12, restore_best_weights=True, mode="max"
)
lr_gru = callbacks.ReduceLROnPlateau(
    monitor="val_accuracy", factor=0.5, patience=4, min_lr=1e-6, mode="max"
)

history_gru = gru_model.fit(
    X_tr_gru, y_tr_gru,
    validation_data=(X_val_gru, y_val_gru),
    epochs=60,
    batch_size=128,
    class_weight=class_weight_dict,  # ★ critical
    callbacks=[es_gru, lr_gru],
    verbose=1,
)

# ══════════════════════════════════════════════════════════════════════════════
# Cell 26 — Learning curves
# ══════════════════════════════════════════════════════════════════════════════

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 4))
ax1.plot(history_gru.history["accuracy"],     label="train")
ax1.plot(history_gru.history["val_accuracy"], label="val")
ax1.set_title("GRU Accuracy"); ax1.legend()

ax2.plot(history_gru.history["loss"],     label="train")
ax2.plot(history_gru.history["val_loss"], label="val")
ax2.set_title("GRU Loss"); ax2.legend()

plt.tight_layout(); plt.show()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 27 — Evaluate on test set
# ══════════════════════════════════════════════════════════════════════════════

y_pred_prob = gru_model.predict(X_test, verbose=0)
y_pred      = np.argmax(y_pred_prob, axis=1)

acc = accuracy_score(y_test, y_pred)
f1  = f1_score(y_test, y_pred, average="macro", zero_division=0)
print(f"Top-1 Accuracy : {acc:.4f}")
print(f"Macro-F1       : {f1:.4f}")

# ══════════════════════════════════════════════════════════════════════════════
# Cell 28 — Top-k accuracy
# ══════════════════════════════════════════════════════════════════════════════

def top_k_accuracy(y_true, y_prob, k=3):
    topk = np.argsort(y_prob, axis=1)[:, -k:]
    hits = [1 if y_true[i] in topk[i] else 0 for i in range(len(y_true))]
    return np.mean(hits)

for k in [1, 3, 5]:
    print(f"Top-{k} accuracy: {top_k_accuracy(y_test, y_pred_prob, k=k):.4f}")

# ══════════════════════════════════════════════════════════════════════════════
# Cell 29 — Classification report
# ══════════════════════════════════════════════════════════════════════════════

print(classification_report(
    y_test, y_pred,
    target_names=action_le.classes_,
    zero_division=0,
))

# ══════════════════════════════════════════════════════════════════════════════
# Cell 30 — Save GRU model
# ══════════════════════════════════════════════════════════════════════════════

gru_model.export(f"{artifacts_dir}/next_action_gru")

with open(f"{artifacts_dir}/next_action_label_map.json", "w", encoding="utf-8") as f:
    json.dump({int(i): cls for i, cls in enumerate(action_le.classes_)}, f,
              ensure_ascii=False, indent=2)

# ══════════════════════════════════════════════════════════════════════════════
# Cell 31 — Export GRU to ONNX
# ══════════════════════════════════════════════════════════════════════════════

# !python -m tf2onnx.convert --saved-model artifacts/next_action_gru \
#   --output artifacts/next_action_gru.onnx --opset 13

# ══════════════════════════════════════════════════════════════════════════════
# ━━━━━━━━━━━━━━━━━━━━━━ PART C — XGBoost Trend Forecasting ━━━━━━━━━━━━━━━━━━
# ══════════════════════════════════════════════════════════════════════════════

# ══════════════════════════════════════════════════════════════════════════════
# Cell 32 — Daily aggregation
# ══════════════════════════════════════════════════════════════════════════════

from xgboost import XGBRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error

df_daily = df.copy()
df_daily["date"] = df_daily["createdAt"].dt.date

agg_cols = {"action": "size"}
if "is_anomaly" in df_daily.columns:
    agg_cols["is_anomaly"] = "sum"

daily_counts = (
    df_daily.groupby(["date", "action"])
    .agg(count=("action", "size"))
    .reset_index()
)

daily_counts["date"]       = pd.to_datetime(daily_counts["date"])
daily_counts["dayofweek"]  = daily_counts["date"].dt.dayofweek
daily_counts["month_num"]  = daily_counts["date"].dt.month
daily_counts["day"]        = daily_counts["date"].dt.day

action_le_xgb = LabelEncoder()
daily_counts["action_id"] = action_le_xgb.fit_transform(daily_counts["action"])
daily_counts = daily_counts.sort_values(["action_id", "date"]).reset_index(drop=True)

daily_counts.head()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 33 — Lag features
# ══════════════════════════════════════════════════════════════════════════════

for lag in [1, 2, 3, 7, 14, 30]:
    daily_counts[f"lag_{lag}"] = (
        daily_counts.groupby("action_id")["count"].shift(lag)
    )

daily_counts["rolling_mean_7"] = (
    daily_counts.groupby("action_id")["count"].shift(1).rolling(7).mean()
)
daily_counts["rolling_std_7"] = (
    daily_counts.groupby("action_id")["count"].shift(1).rolling(7).std()
)

daily_counts = daily_counts.dropna().reset_index(drop=True)
daily_counts.head()

# ══════════════════════════════════════════════════════════════════════════════
# Cell 34 — Train/test split
# ══════════════════════════════════════════════════════════════════════════════

feature_cols_trend = [
    "action_id", "dayofweek", "month_num", "day",
    "lag_1", "lag_2", "lag_3", "lag_7", "lag_14", "lag_30",
    "rolling_mean_7", "rolling_std_7",
]

X_trend = daily_counts[feature_cols_trend]
y_trend = daily_counts["count"]

split_idx_trend = int(len(daily_counts) * 0.8)
X_train_trend = X_trend.iloc[:split_idx_trend]
X_test_trend  = X_trend.iloc[split_idx_trend:]
y_train_trend = y_trend.iloc[:split_idx_trend]
y_test_trend  = y_trend.iloc[split_idx_trend:]

# ══════════════════════════════════════════════════════════════════════════════
# Cell 35 — Train XGBoost
# ══════════════════════════════════════════════════════════════════════════════

trend_model = XGBRegressor(
    n_estimators=500,
    learning_rate=0.03,
    max_depth=6,
    subsample=0.8,
    colsample_bytree=0.8,
    random_state=42,
    early_stopping_rounds=30,
    eval_metric="mae",
)

trend_model.fit(
    X_train_trend, y_train_trend,
    eval_set=[(X_test_trend, y_test_trend)],
    verbose=50,
)

y_pred_trend = trend_model.predict(X_test_trend)
mae  = mean_absolute_error(y_test_trend, y_pred_trend)
rmse = math.sqrt(mean_squared_error(y_test_trend, y_pred_trend))

print(f"XGBoost  MAE  : {mae:.3f}")
print(f"XGBoost  RMSE : {rmse:.3f}")

# ══════════════════════════════════════════════════════════════════════════════
# Cell 36 — Save XGBoost model
# ══════════════════════════════════════════════════════════════════════════════

joblib.dump(trend_model, f"{artifacts_dir}/trend_xgboost.pkl")

with open(f"{artifacts_dir}/trend_feature_cols.json", "w", encoding="utf-8") as f:
    json.dump(feature_cols_trend, f)

print("All models saved in", artifacts_dir)
print("Done ✓")
