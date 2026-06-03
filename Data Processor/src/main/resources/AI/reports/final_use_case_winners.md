# Final Use-Case Winners — V3.6 High Precision

Selection uses task-appropriate metrics: PR-AUC/F1 for rare anomalies, ARI/NMI for persona clustering when labels exist, F1/AUC/PR-AUC for churn, and RMSE for forecasting.

|use_case|recommended_approach|family|selection_metric|metric_value|
|---|---|---|---|---|
|security_anomaly_detection_ranking|XGBoost|tabular_anomaly|pr_auc|0.515807|
|security_anomaly_detection_alerting|LightGBM|tabular_anomaly|best_f1|0.569733|
|security_anomaly_detection_auc_reference|XGBoost|tabular_anomaly|anomaly_auc|0.908746|
|sequence_foundation_best_detection|Behavioral Transformer MH (2-head)|deep_sequence|anomaly_auc|0.818647|
|sequence_foundation_next_action_prediction|Behavioral Transformer MH (2-head)|deep_sequence|val_acc1|0.749521|
|sequence_runtime_fast_combined|Temporal CNN / TCN|deep_sequence|combined_score|0.691805|
|anomaly_type_api_scraping|XGBoost|tabular_anomaly|auc_vs_rest|0.930093|
|anomaly_type_credential_stuffing|CNN + Transformer Hybrid|deep_sequence|auc_vs_rest|0.967168|
|anomaly_type_data_exfiltration|CatBoost|tabular_anomaly|auc_vs_rest|0.893543|
|anomaly_type_off_hours_compromise|OneClassSVM RBF|tabular_anomaly|auc_vs_rest|0.889530|
|anomaly_type_session_hijacking|Deeper Transformer MH (3-layer)|deep_sequence|auc_vs_rest|0.971167|
|persona_clustering|Behavioral_Transformer_MH_2-head_embedding_plus_profile + KMeans k=7|clustering|ARI|0.746751|
|churn_ranking_auc|profile_only + ExtraTrees|churn_classifier|AUC|0.953357|
|churn_ranking_pr_auc|profile_only + ExtraTrees|churn_classifier|PR_AUC|0.887514|
|churn_operational_f1|profile_only + ExtraTrees|churn_classifier|F1|0.800000|
|forecast_anomaly_rate|Ridge|forecasting_regressor|RMSE|0.051724|
|forecast_total_events|XGBoost|forecasting_regressor|RMSE|1468.515539|

