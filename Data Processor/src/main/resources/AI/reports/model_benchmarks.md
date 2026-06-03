# Exhaustive AI Benchmark Report — V3.6 High Precision

Device: `cuda` | Epochs: `20` | Batch: `256` | Train normal only: `1` | Checkpoint: `auc`

## Deep Sequence Models — Security Detection

|Rank|family|model_name|selected_by|val_loss|val_acc1|val_acc3|cat_acc|anomaly_auc|pr_auc|best_auc_seen|cat_auc|cont_auc|best_f1|best_precision|best_recall|best_accuracy|best_balanced_accuracy|best_mcc|latency_ms|train_time_s|params|combined_score|
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
|1|deep_sequence|Behavioral Transformer MH (2-head)|auc|6.706817|0.749521|0.877255|0.668767|0.818647|0.052456|0.818647|0.829205|0.625990|0.114247|0.063769|0.548180|0.911007|0.731513|0.164680|3.211600|978.220000|349295|0.678422|
|2|deep_sequence|Behavioral Transformer MH (4-head)|auc|6.741159|0.745732|0.876111|0.668350|0.818115|0.051038|0.818115|0.827563|0.634670|0.112295|0.065944|0.377944|0.937439|0.660652|0.137702|3.342700|977.270000|349295|0.675021|
|3|deep_sequence|Deeper Transformer MH (3-layer)|auc|6.700821|0.747918|0.876784|0.668764|0.817962|0.053593|0.817962|0.828916|0.631531|0.114204|0.067065|0.384368|0.937574|0.663898|0.140485|3.918000|1084.590000|481775|0.668652|
|4|deep_sequence|CNN + Transformer Hybrid|auc|6.778802|0.741618|0.874497|0.667406|0.812817|0.050735|0.812817|0.822364|0.617890|0.109562|0.061153|0.525696|0.910536|0.720152|0.156599|3.800700|899.170000|266095|0.665275|
|5|deep_sequence|Temporal CNN / TCN|auc|6.686235|0.748792|0.877972|0.668993|0.812815|0.048749|0.812815|0.823707|0.623020|0.108619|0.065680|0.313704|0.946093|0.633244|0.124449|1.909100|879.870000|215919|0.691805|
|6|deep_sequence|Stacked GRU MH (2x128)|auc|8.254003|0.601128|0.790974|0.582860|0.782531|0.042989|0.782531|0.798095|0.624615|0.108248|0.065456|0.312634|0.946070|0.632703|0.123943|2.134800|400.230000|246639|0.618732|
|7|deep_sequence|Stacked LSTM MH (2x128)|auc|8.243300|0.600679|0.791008|0.583450|0.781227|0.044189|0.781227|0.796489|0.625674|0.109361|0.066129|0.315846|0.946138|0.634326|0.125459|2.264200|360.710000|311279|0.616486|
|8|deep_sequence|Bidirectional GRU MH|auc|8.253263|0.600791|0.790593|0.582859|0.780501|0.044232|0.780501|0.795471|0.624923|0.108619|0.065680|0.313704|0.946093|0.633244|0.124449|2.031900|591.610000|122991|0.619138|
|9|deep_sequence|MeanPool MLP Baseline|auc|8.471071|0.450997|0.737751|0.618718|0.764532|0.038711|0.764532|0.761821|0.634156|0.108248|0.065456|0.312634|0.946070|0.632703|0.123943|1.213300|559.110000|84335|0.571212|

## Deep Sequence Models — Prediction

|Rank|family|model_name|selected_by|val_loss|val_acc1|val_acc3|cat_acc|anomaly_auc|pr_auc|best_auc_seen|cat_auc|cont_auc|best_f1|best_precision|best_recall|best_accuracy|best_balanced_accuracy|best_mcc|latency_ms|train_time_s|params|combined_score|
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
|1|deep_sequence|Behavioral Transformer MH (2-head)|auc|6.706817|0.749521|0.877255|0.668767|0.818647|0.052456|0.818647|0.829205|0.625990|0.114247|0.063769|0.548180|0.911007|0.731513|0.164680|3.211600|978.220000|349295|0.678422|
|2|deep_sequence|Temporal CNN / TCN|auc|6.686235|0.748792|0.877972|0.668993|0.812815|0.048749|0.812815|0.823707|0.623020|0.108619|0.065680|0.313704|0.946093|0.633244|0.124449|1.909100|879.870000|215919|0.691805|
|3|deep_sequence|Deeper Transformer MH (3-layer)|auc|6.700821|0.747918|0.876784|0.668764|0.817962|0.053593|0.817962|0.828916|0.631531|0.114204|0.067065|0.384368|0.937574|0.663898|0.140485|3.918000|1084.590000|481775|0.668652|
|4|deep_sequence|Behavioral Transformer MH (4-head)|auc|6.741159|0.745732|0.876111|0.668350|0.818115|0.051038|0.818115|0.827563|0.634670|0.112295|0.065944|0.377944|0.937439|0.660652|0.137702|3.342700|977.270000|349295|0.675021|
|5|deep_sequence|CNN + Transformer Hybrid|auc|6.778802|0.741618|0.874497|0.667406|0.812817|0.050735|0.812817|0.822364|0.617890|0.109562|0.061153|0.525696|0.910536|0.720152|0.156599|3.800700|899.170000|266095|0.665275|
|6|deep_sequence|Stacked GRU MH (2x128)|auc|8.254003|0.601128|0.790974|0.582860|0.782531|0.042989|0.782531|0.798095|0.624615|0.108248|0.065456|0.312634|0.946070|0.632703|0.123943|2.134800|400.230000|246639|0.618732|
|7|deep_sequence|Bidirectional GRU MH|auc|8.253263|0.600791|0.790593|0.582859|0.780501|0.044232|0.780501|0.795471|0.624923|0.108619|0.065680|0.313704|0.946093|0.633244|0.124449|2.031900|591.610000|122991|0.619138|
|8|deep_sequence|Stacked LSTM MH (2x128)|auc|8.243300|0.600679|0.791008|0.583450|0.781227|0.044189|0.781227|0.796489|0.625674|0.109361|0.066129|0.315846|0.946138|0.634326|0.125459|2.264200|360.710000|311279|0.616486|
|9|deep_sequence|MeanPool MLP Baseline|auc|8.471071|0.450997|0.737751|0.618718|0.764532|0.038711|0.764532|0.761821|0.634156|0.108248|0.065456|0.312634|0.946070|0.632703|0.123943|1.213300|559.110000|84335|0.571212|

## Deep Sequence Models — Combined

|Rank|family|model_name|selected_by|val_loss|val_acc1|val_acc3|cat_acc|anomaly_auc|pr_auc|best_auc_seen|cat_auc|cont_auc|best_f1|best_precision|best_recall|best_accuracy|best_balanced_accuracy|best_mcc|latency_ms|train_time_s|params|combined_score|
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
|1|deep_sequence|Temporal CNN / TCN|auc|6.686235|0.748792|0.877972|0.668993|0.812815|0.048749|0.812815|0.823707|0.623020|0.108619|0.065680|0.313704|0.946093|0.633244|0.124449|1.909100|879.870000|215919|0.691805|
|2|deep_sequence|Behavioral Transformer MH (2-head)|auc|6.706817|0.749521|0.877255|0.668767|0.818647|0.052456|0.818647|0.829205|0.625990|0.114247|0.063769|0.548180|0.911007|0.731513|0.164680|3.211600|978.220000|349295|0.678422|
|3|deep_sequence|Behavioral Transformer MH (4-head)|auc|6.741159|0.745732|0.876111|0.668350|0.818115|0.051038|0.818115|0.827563|0.634670|0.112295|0.065944|0.377944|0.937439|0.660652|0.137702|3.342700|977.270000|349295|0.675021|
|4|deep_sequence|Deeper Transformer MH (3-layer)|auc|6.700821|0.747918|0.876784|0.668764|0.817962|0.053593|0.817962|0.828916|0.631531|0.114204|0.067065|0.384368|0.937574|0.663898|0.140485|3.918000|1084.590000|481775|0.668652|
|5|deep_sequence|CNN + Transformer Hybrid|auc|6.778802|0.741618|0.874497|0.667406|0.812817|0.050735|0.812817|0.822364|0.617890|0.109562|0.061153|0.525696|0.910536|0.720152|0.156599|3.800700|899.170000|266095|0.665275|
|6|deep_sequence|Bidirectional GRU MH|auc|8.253263|0.600791|0.790593|0.582859|0.780501|0.044232|0.780501|0.795471|0.624923|0.108619|0.065680|0.313704|0.946093|0.633244|0.124449|2.031900|591.610000|122991|0.619138|
|7|deep_sequence|Stacked GRU MH (2x128)|auc|8.254003|0.601128|0.790974|0.582860|0.782531|0.042989|0.782531|0.798095|0.624615|0.108248|0.065456|0.312634|0.946070|0.632703|0.123943|2.134800|400.230000|246639|0.618732|
|8|deep_sequence|Stacked LSTM MH (2x128)|auc|8.243300|0.600679|0.791008|0.583450|0.781227|0.044189|0.781227|0.796489|0.625674|0.109361|0.066129|0.315846|0.946138|0.634326|0.125459|2.264200|360.710000|311279|0.616486|
|9|deep_sequence|MeanPool MLP Baseline|auc|8.471071|0.450997|0.737751|0.618718|0.764532|0.038711|0.764532|0.761821|0.634156|0.108248|0.065456|0.312634|0.946070|0.632703|0.123943|1.213300|559.110000|84335|0.571212|

## Classical / Tabular Anomaly Detection Baselines

|Rank|family|model_name|training_mode|anomaly_auc|pr_auc|best_f1|best_precision|best_recall|best_accuracy|best_balanced_accuracy|best_mcc|latency_ms|train_time_s|
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
|1|tabular_anomaly|XGBoost|supervised|0.908746|0.515807|0.567755|0.574000|0.561644|0.991260|0.778670|0.563375|0.006059|6.574509|
|2|tabular_anomaly|LightGBM|supervised|0.907364|0.480197|0.569733|0.576000|0.563601|0.991300|0.779659|0.565373|0.030181|10.798549|
|3|tabular_anomaly|HistGradientBoosting|supervised|0.896721|0.461906|0.538081|0.544000|0.532290|0.990660|0.763841|0.533396|0.005488|2.418544|
|4|tabular_anomaly|RandomForest|supervised|0.879444|0.452172|0.544016|0.550000|0.538160|0.990780|0.766807|0.539392|0.015768|68.814269|
|5|tabular_anomaly|MLPClassifier|supervised|0.875020|0.426946|0.488625|0.494000|0.483366|0.989660|0.739127|0.483432|0.001060|26.222734|
|6|tabular_anomaly|GradientBoosting|supervised|0.872258|0.426300|0.551383|0.556886|0.545988|0.990920|0.770751|0.546825|0.001251|97.344794|
|7|tabular_anomaly|CatBoost|supervised|0.902821|0.423987|0.563798|0.570000|0.557730|0.991180|0.776693|0.559378|0.002196|18.406549|
|8|tabular_anomaly|AdaBoost|supervised|0.891441|0.421447|0.542038|0.548000|0.536204|0.990740|0.765818|0.537393|0.013508|91.203620|
|9|tabular_anomaly|ExtraTrees|supervised|0.828019|0.397820|0.478318|0.728000|0.356164|0.992060|0.677395|0.505906|0.035084|54.316400|
|10|tabular_anomaly|LogisticRegression balanced|supervised|0.860150|0.294584|0.393670|0.398000|0.389432|0.987740|0.691675|0.387501|0.000242|4.833866|
|11|tabular_anomaly|KNN|supervised|0.745099|0.269724|0.386334|0.588000|0.287671|0.990660|0.642795|0.407231|0.982765|0.009859|
|12|tabular_anomaly|SGDClassifier log_loss|supervised|0.720951|0.185374|0.272997|0.276000|0.270059|0.985300|0.631372|0.265589|0.000074|2.165410|
|13|tabular_anomaly|OneClassSVM RBF|normal_only|0.713517|0.166691|0.273325|0.416000|0.203523|0.988940|0.600286|0.286002|0.048268|0.920721|
|14|tabular_anomaly|LocalOutlierFactor novelty|lof_novelty|0.717953|0.163322|0.252300|0.384000|0.187867|0.988620|0.592378|0.263448|0.181943|3.181486|
|15|tabular_anomaly|PCA reconstruction|normal_only|0.667421|0.132931|0.223541|0.226000|0.221135|0.984300|0.606658|0.215625|0.000000|0.000000|
|16|tabular_anomaly|SVC RBF sampled|supervised|0.816548|0.115879|0.183976|0.186000|0.181996|0.983500|0.586886|0.175654|0.470996|40.161809|
|17|tabular_anomaly|GaussianNB|supervised|0.756804|0.091045|0.228228|0.233607|0.223092|0.984580|0.607767|0.220504|0.000755|0.063907|
|18|tabular_anomaly|IsolationForest|normal_only|0.640921|0.027899|0.075173|0.076000|0.074364|0.981300|0.532514|0.065733|0.025113|5.182540|
|19|tabular_anomaly|EllipticEnvelope|normal_only|0.607756|0.014356|0.035658|0.019231|0.244618|0.864780|0.557901|0.034632|0.002375|7.546317|

## Per-Anomaly-Type AUC / AP

|family|model_name|anomaly_type|count|auc_vs_rest|ap_vs_rest|
|---|---|---|---|---|---|
|deep_sequence|MeanPool MLP Baseline|api_scraping|356|0.724663|0.007522|
|deep_sequence|MeanPool MLP Baseline|credential_stuffing|97|0.962520|0.014134|
|deep_sequence|MeanPool MLP Baseline|data_exfiltration|200|0.704945|0.004753|
|deep_sequence|MeanPool MLP Baseline|off_hours_compromise|121|0.536510|0.001377|
|deep_sequence|MeanPool MLP Baseline|session_hijacking|160|0.967717|0.056415|
|deep_sequence|Temporal CNN / TCN|api_scraping|356|0.813585|0.012164|
|deep_sequence|Temporal CNN / TCN|credential_stuffing|97|0.962846|0.014127|
|deep_sequence|Temporal CNN / TCN|data_exfiltration|200|0.778133|0.006873|
|deep_sequence|Temporal CNN / TCN|off_hours_compromise|121|0.519616|0.001365|
|deep_sequence|Temporal CNN / TCN|session_hijacking|160|0.970843|0.060933|
|deep_sequence|Stacked LSTM MH (2x128)|api_scraping|356|0.751576|0.009180|
|deep_sequence|Stacked LSTM MH (2x128)|credential_stuffing|97|0.964142|0.014631|
|deep_sequence|Stacked LSTM MH (2x128)|data_exfiltration|200|0.735254|0.005108|
|deep_sequence|Stacked LSTM MH (2x128)|off_hours_compromise|121|0.533142|0.001408|
|deep_sequence|Stacked LSTM MH (2x128)|session_hijacking|160|0.968273|0.068268|
|deep_sequence|Stacked GRU MH (2x128)|api_scraping|356|0.751166|0.008967|
|deep_sequence|Stacked GRU MH (2x128)|credential_stuffing|97|0.964006|0.014584|
|deep_sequence|Stacked GRU MH (2x128)|data_exfiltration|200|0.741752|0.005113|
|deep_sequence|Stacked GRU MH (2x128)|off_hours_compromise|121|0.534708|0.001410|
|deep_sequence|Stacked GRU MH (2x128)|session_hijacking|160|0.967511|0.064393|
|deep_sequence|Behavioral Transformer MH (2-head)|api_scraping|356|0.813324|0.013776|
|deep_sequence|Behavioral Transformer MH (2-head)|credential_stuffing|97|0.965897|0.015574|
|deep_sequence|Behavioral Transformer MH (2-head)|data_exfiltration|200|0.791589|0.007654|
|deep_sequence|Behavioral Transformer MH (2-head)|off_hours_compromise|121|0.543456|0.001459|
|deep_sequence|Behavioral Transformer MH (2-head)|session_hijacking|160|0.968475|0.062233|
|deep_sequence|Behavioral Transformer MH (4-head)|api_scraping|356|0.815524|0.013541|
|deep_sequence|Behavioral Transformer MH (4-head)|credential_stuffing|97|0.965516|0.015430|
|deep_sequence|Behavioral Transformer MH (4-head)|data_exfiltration|200|0.787623|0.007305|
|deep_sequence|Behavioral Transformer MH (4-head)|off_hours_compromise|121|0.538387|0.001439|
|deep_sequence|Behavioral Transformer MH (4-head)|session_hijacking|160|0.969531|0.058190|
|deep_sequence|Bidirectional GRU MH|api_scraping|356|0.748906|0.008946|
|deep_sequence|Bidirectional GRU MH|credential_stuffing|97|0.965239|0.015296|
|deep_sequence|Bidirectional GRU MH|data_exfiltration|200|0.734588|0.005015|
|deep_sequence|Bidirectional GRU MH|off_hours_compromise|121|0.535916|0.001413|
|deep_sequence|Bidirectional GRU MH|session_hijacking|160|0.968067|0.069454|
|deep_sequence|CNN + Transformer Hybrid|api_scraping|356|0.809971|0.013022|
|deep_sequence|CNN + Transformer Hybrid|credential_stuffing|97|0.967168|0.016130|
|deep_sequence|CNN + Transformer Hybrid|data_exfiltration|200|0.776365|0.006782|
|deep_sequence|CNN + Transformer Hybrid|off_hours_compromise|121|0.530044|0.001400|
|deep_sequence|CNN + Transformer Hybrid|session_hijacking|160|0.970576|0.062318|
|deep_sequence|Deeper Transformer MH (3-layer)|api_scraping|356|0.819651|0.013944|
|deep_sequence|Deeper Transformer MH (3-layer)|credential_stuffing|97|0.965335|0.015294|
|deep_sequence|Deeper Transformer MH (3-layer)|data_exfiltration|200|0.785860|0.007342|
|deep_sequence|Deeper Transformer MH (3-layer)|off_hours_compromise|121|0.526004|0.001392|
|deep_sequence|Deeper Transformer MH (3-layer)|session_hijacking|160|0.971167|0.069109|
|tabular_anomaly|LogisticRegression balanced|api_scraping|188|0.871082|0.039104|
|tabular_anomaly|LogisticRegression balanced|credential_stuffing|52|0.938041|0.063450|
|tabular_anomaly|LogisticRegression balanced|data_exfiltration|114|0.812936|0.020423|
|tabular_anomaly|LogisticRegression balanced|off_hours_compromise|71|0.821379|0.046062|
|tabular_anomaly|LogisticRegression balanced|session_hijacking|86|0.867155|0.475222|
|tabular_anomaly|SGDClassifier log_loss|api_scraping|188|0.632030|0.009766|
|tabular_anomaly|SGDClassifier log_loss|credential_stuffing|52|0.932932|0.024822|
|tabular_anomaly|SGDClassifier log_loss|data_exfiltration|114|0.602115|0.004454|
|tabular_anomaly|SGDClassifier log_loss|off_hours_compromise|71|0.765913|0.048147|
|tabular_anomaly|SGDClassifier log_loss|session_hijacking|86|0.896810|0.542575|
|tabular_anomaly|RandomForest|api_scraping|188|0.881096|0.150875|
|tabular_anomaly|RandomForest|credential_stuffing|52|0.951881|0.692593|
|tabular_anomaly|RandomForest|data_exfiltration|114|0.851376|0.043859|
|tabular_anomaly|RandomForest|off_hours_compromise|71|0.826899|0.021895|
|tabular_anomaly|RandomForest|session_hijacking|86|0.895121|0.086970|
|tabular_anomaly|ExtraTrees|api_scraping|188|0.823728|0.103958|
|tabular_anomaly|ExtraTrees|credential_stuffing|52|0.950516|0.860197|
|tabular_anomaly|ExtraTrees|data_exfiltration|114|0.815045|0.027709|
|tabular_anomaly|ExtraTrees|off_hours_compromise|71|0.696559|0.005411|
|tabular_anomaly|ExtraTrees|session_hijacking|86|0.873909|0.117092|
|tabular_anomaly|GradientBoosting|api_scraping|188|0.870076|0.100536|
|tabular_anomaly|GradientBoosting|credential_stuffing|52|0.961744|0.423717|
|tabular_anomaly|GradientBoosting|data_exfiltration|114|0.845207|0.041960|
|tabular_anomaly|GradientBoosting|off_hours_compromise|71|0.816342|0.026883|
|tabular_anomaly|GradientBoosting|session_hijacking|86|0.887751|0.215475|
|tabular_anomaly|HistGradientBoosting|api_scraping|188|0.897274|0.114908|
|tabular_anomaly|HistGradientBoosting|credential_stuffing|52|0.964834|0.871129|
|tabular_anomaly|HistGradientBoosting|data_exfiltration|114|0.869409|0.038280|
|tabular_anomaly|HistGradientBoosting|off_hours_compromise|71|0.851764|0.027663|
|tabular_anomaly|HistGradientBoosting|session_hijacking|86|0.909347|0.119109|
|tabular_anomaly|AdaBoost|api_scraping|188|0.912429|0.117064|
|tabular_anomaly|AdaBoost|credential_stuffing|52|0.949790|0.076979|
|tabular_anomaly|AdaBoost|data_exfiltration|114|0.862452|0.052417|
|tabular_anomaly|AdaBoost|off_hours_compromise|71|0.830788|0.022359|
|tabular_anomaly|AdaBoost|session_hijacking|86|0.880819|0.448259|
|tabular_anomaly|GaussianNB|api_scraping|188|0.633115|0.005737|
|tabular_anomaly|GaussianNB|credential_stuffing|52|0.941917|0.017972|
|tabular_anomaly|GaussianNB|data_exfiltration|114|0.704278|0.005151|
|tabular_anomaly|GaussianNB|off_hours_compromise|71|0.875260|0.065555|
|tabular_anomaly|GaussianNB|session_hijacking|86|0.874574|0.106110|
|tabular_anomaly|KNN|api_scraping|188|0.649332|0.016864|
|tabular_anomaly|KNN|credential_stuffing|52|0.954837|0.145260|
|tabular_anomaly|KNN|data_exfiltration|114|0.636685|0.006780|
|tabular_anomaly|KNN|off_hours_compromise|71|0.836449|0.051811|
|tabular_anomaly|KNN|session_hijacking|86|0.884022|0.453239|
|tabular_anomaly|MLPClassifier|api_scraping|188|0.877877|0.086190|
|tabular_anomaly|MLPClassifier|credential_stuffing|52|0.948349|0.469754|
|tabular_anomaly|MLPClassifier|data_exfiltration|114|0.856516|0.031699|
|tabular_anomaly|MLPClassifier|off_hours_compromise|71|0.803316|0.026296|
|tabular_anomaly|MLPClassifier|session_hijacking|86|0.890882|0.228880|
|tabular_anomaly|CatBoost|api_scraping|188|0.916581|0.142555|
|tabular_anomaly|CatBoost|credential_stuffing|52|0.951861|0.263991|
|tabular_anomaly|CatBoost|data_exfiltration|114|0.893543|0.058905|
|tabular_anomaly|CatBoost|off_hours_compromise|71|0.848250|0.027669|
|tabular_anomaly|CatBoost|session_hijacking|86|0.881943|0.103064|
|tabular_anomaly|XGBoost|api_scraping|188|0.930093|0.139019|
|tabular_anomaly|XGBoost|credential_stuffing|52|0.958742|0.750890|
|tabular_anomaly|XGBoost|data_exfiltration|114|0.888403|0.047091|
|tabular_anomaly|XGBoost|off_hours_compromise|71|0.858722|0.026972|
|tabular_anomaly|XGBoost|session_hijacking|86|0.881370|0.180644|
|tabular_anomaly|LightGBM|api_scraping|188|0.925495|0.141511|
|tabular_anomaly|LightGBM|credential_stuffing|52|0.953901|0.168334|
|tabular_anomaly|LightGBM|data_exfiltration|114|0.892185|0.051817|
|tabular_anomaly|LightGBM|off_hours_compromise|71|0.845140|0.020183|
|tabular_anomaly|LightGBM|session_hijacking|86|0.892393|0.368286|
|tabular_anomaly|SVC RBF sampled|api_scraping|188|0.795668|0.034578|
|tabular_anomaly|SVC RBF sampled|credential_stuffing|52|0.921482|0.098288|
|tabular_anomaly|SVC RBF sampled|data_exfiltration|114|0.806829|0.045901|
|tabular_anomaly|SVC RBF sampled|off_hours_compromise|71|0.758750|0.004267|
|tabular_anomaly|SVC RBF sampled|session_hijacking|86|0.844642|0.019774|
|tabular_anomaly|IsolationForest|api_scraping|188|0.555636|0.004291|
|tabular_anomaly|IsolationForest|credential_stuffing|52|0.895725|0.005263|
|tabular_anomaly|IsolationForest|data_exfiltration|114|0.564704|0.003153|
|tabular_anomaly|IsolationForest|off_hours_compromise|71|0.866953|0.071707|
|tabular_anomaly|IsolationForest|session_hijacking|86|0.580627|0.002086|
|tabular_anomaly|LocalOutlierFactor novelty|api_scraping|188|0.638989|0.008138|
|tabular_anomaly|LocalOutlierFactor novelty|credential_stuffing|52|0.936780|0.017867|
|tabular_anomaly|LocalOutlierFactor novelty|data_exfiltration|114|0.637175|0.006676|
|tabular_anomaly|LocalOutlierFactor novelty|off_hours_compromise|71|0.654573|0.009361|
|tabular_anomaly|LocalOutlierFactor novelty|session_hijacking|86|0.907136|0.558280|
|tabular_anomaly|OneClassSVM RBF|api_scraping|188|0.580943|0.004621|
|tabular_anomaly|OneClassSVM RBF|credential_stuffing|52|0.834875|0.004612|
|tabular_anomaly|OneClassSVM RBF|data_exfiltration|114|0.637778|0.004988|
|tabular_anomaly|OneClassSVM RBF|off_hours_compromise|71|0.889530|0.092537|
|tabular_anomaly|OneClassSVM RBF|session_hijacking|86|0.874464|0.488300|
|tabular_anomaly|EllipticEnvelope|api_scraping|188|0.524625|0.004007|
|tabular_anomaly|EllipticEnvelope|credential_stuffing|52|0.809857|0.002619|
|tabular_anomaly|EllipticEnvelope|data_exfiltration|114|0.517804|0.002282|
|tabular_anomaly|EllipticEnvelope|off_hours_compromise|71|0.770367|0.006616|
|tabular_anomaly|EllipticEnvelope|session_hijacking|86|0.646750|0.002503|
|tabular_anomaly|PCA reconstruction|api_scraping|188|0.496160|0.004142|
|tabular_anomaly|PCA reconstruction|credential_stuffing|52|0.936596|0.012381|
|tabular_anomaly|PCA reconstruction|data_exfiltration|114|0.584161|0.003227|
|tabular_anomaly|PCA reconstruction|off_hours_compromise|71|0.794710|0.085557|
|tabular_anomaly|PCA reconstruction|session_hijacking|86|0.875651|0.341882|

## Threshold / Top-K Metrics

|family|model_name|threshold|precision|recall|f1|accuracy|balanced_accuracy|mcc|alerts|captured_anomalies|
|---|---|---|---|---|---|---|---|---|---|---|
|deep_sequence|MeanPool MLP Baseline|top_1pct|0.013453|0.012848|0.013143|0.979800|0.501440|0.002945|892|12|
|deep_sequence|MeanPool MLP Baseline|top_5pct|0.065471|0.312634|0.108268|0.946082|0.632709|0.123962|4460|292|
|deep_sequence|MeanPool MLP Baseline|p95.0|0.065456|0.312634|0.108248|0.946070|0.632703|0.123943|4461|292|
|deep_sequence|Temporal CNN / TCN|top_1pct|0.016816|0.016060|0.016429|0.979868|0.503063|0.006266|892|15|
|deep_sequence|Temporal CNN / TCN|top_5pct|0.065695|0.313704|0.108639|0.946104|0.633250|0.124467|4460|293|
|deep_sequence|Temporal CNN / TCN|p95.0|0.065680|0.313704|0.108619|0.946093|0.633244|0.124449|4461|293|
|deep_sequence|Stacked LSTM MH (2x128)|top_1pct|0.019058|0.018201|0.018620|0.979912|0.504145|0.008480|892|17|
|deep_sequence|Stacked LSTM MH (2x128)|top_5pct|0.066143|0.315846|0.109381|0.946149|0.634332|0.125478|4460|295|
|deep_sequence|Stacked LSTM MH (2x128)|p95.0|0.066129|0.315846|0.109361|0.946138|0.634326|0.125459|4461|295|
|deep_sequence|Stacked GRU MH (2x128)|top_1pct|0.020179|0.019272|0.019715|0.979935|0.504686|0.009587|892|18|
|deep_sequence|Stacked GRU MH (2x128)|top_5pct|0.065471|0.312634|0.108268|0.946082|0.632709|0.123962|4460|292|
|deep_sequence|Stacked GRU MH (2x128)|p95.0|0.065456|0.312634|0.108248|0.946070|0.632703|0.123943|4461|292|
|deep_sequence|Behavioral Transformer MH (2-head)|top_1pct|0.014574|0.013919|0.014239|0.979823|0.501981|0.004052|892|13|
|deep_sequence|Behavioral Transformer MH (2-head)|top_5pct|0.066368|0.316916|0.109752|0.946171|0.634873|0.125983|4460|296|
|deep_sequence|Behavioral Transformer MH (2-head)|p91.0|0.063769|0.548180|0.114247|0.911007|0.731513|0.164680|8029|512|
|deep_sequence|Behavioral Transformer MH (4-head)|top_1pct|0.017937|0.017131|0.017525|0.979890|0.503604|0.007373|892|16|
|deep_sequence|Behavioral Transformer MH (4-head)|top_5pct|0.065695|0.313704|0.108639|0.946104|0.633250|0.124467|4460|293|
|deep_sequence|Behavioral Transformer MH (4-head)|p94.0|0.065944|0.377944|0.112295|0.937439|0.660652|0.137702|5353|353|
|deep_sequence|Bidirectional GRU MH|top_1pct|0.021300|0.020343|0.020811|0.979957|0.505227|0.010694|892|19|
|deep_sequence|Bidirectional GRU MH|top_5pct|0.065695|0.313704|0.108639|0.946104|0.633250|0.124467|4460|293|
|deep_sequence|Bidirectional GRU MH|p95.0|0.065680|0.313704|0.108619|0.946093|0.633244|0.124449|4461|293|
|deep_sequence|CNN + Transformer Hybrid|top_1pct|0.016816|0.016060|0.016429|0.979868|0.503063|0.006266|892|15|
|deep_sequence|CNN + Transformer Hybrid|top_5pct|0.066143|0.315846|0.109381|0.946149|0.634332|0.125478|4460|295|
|deep_sequence|CNN + Transformer Hybrid|p91.0|0.061153|0.525696|0.109562|0.910536|0.720152|0.156599|8029|491|
|deep_sequence|Deeper Transformer MH (3-layer)|top_1pct|0.019058|0.018201|0.018620|0.979912|0.504145|0.008480|892|17|
|deep_sequence|Deeper Transformer MH (3-layer)|top_5pct|0.066143|0.315846|0.109381|0.946149|0.634332|0.125478|4460|295|
|deep_sequence|Deeper Transformer MH (3-layer)|p94.0|0.067065|0.384368|0.114204|0.937574|0.663898|0.140485|5353|359|
|tabular_anomaly|LogisticRegression balanced|top_1pct|0.398000|0.389432|0.393670|0.987740|0.691675|0.387501|500|199|
|tabular_anomaly|LogisticRegression balanced|top_5pct|0.121200|0.592955|0.201262|0.951900|0.774281|0.253147|2500|303|
|tabular_anomaly|LogisticRegression balanced|p99.0|0.398000|0.389432|0.393670|0.987740|0.691675|0.387501|500|199|
|tabular_anomaly|SGDClassifier log_loss|top_1pct|0.276000|0.270059|0.272997|0.985300|0.631372|0.265589|500|138|
|tabular_anomaly|SGDClassifier log_loss|top_5pct|0.084800|0.414873|0.140817|0.948260|0.684320|0.170118|2500|212|
|tabular_anomaly|SGDClassifier log_loss|p99.0|0.276000|0.270059|0.272997|0.985300|0.631372|0.265589|500|138|
|tabular_anomaly|RandomForest|top_1pct|0.550000|0.538160|0.544016|0.990780|0.766807|0.539392|500|275|
|tabular_anomaly|RandomForest|top_5pct|0.138800|0.679061|0.230488|0.953660|0.817778|0.293293|2500|347|
|tabular_anomaly|RandomForest|p99.0|0.550000|0.538160|0.544016|0.990780|0.766807|0.539392|500|275|
|tabular_anomaly|ExtraTrees|top_1pct|0.446000|0.436399|0.441147|0.988700|0.715401|0.435467|500|223|
|tabular_anomaly|ExtraTrees|top_5pct|0.111200|0.544031|0.184656|0.950900|0.749566|0.230337|2500|278|
|tabular_anomaly|ExtraTrees|p99.5|0.728000|0.356164|0.478318|0.992060|0.677395|0.505906|250|182|
|tabular_anomaly|GradientBoosting|top_1pct|0.558000|0.545988|0.551929|0.990940|0.770761|0.547386|500|279|
|tabular_anomaly|GradientBoosting|top_5pct|0.134000|0.655577|0.222517|0.953180|0.805915|0.282344|2500|335|
|tabular_anomaly|GradientBoosting|p99.0|0.556886|0.545988|0.551383|0.990920|0.770751|0.546825|501|279|
|tabular_anomaly|HistGradientBoosting|top_1pct|0.544000|0.532290|0.538081|0.990660|0.763841|0.533396|500|272|
|tabular_anomaly|HistGradientBoosting|top_5pct|0.138400|0.677104|0.229824|0.953620|0.816789|0.292381|2500|346|
|tabular_anomaly|HistGradientBoosting|p99.0|0.544000|0.532290|0.538081|0.990660|0.763841|0.533396|500|272|
|tabular_anomaly|AdaBoost|top_1pct|0.548000|0.536204|0.542038|0.990740|0.765818|0.537393|500|274|
|tabular_anomaly|AdaBoost|top_5pct|0.143200|0.700587|0.237795|0.954100|0.828652|0.303330|2500|358|
|tabular_anomaly|AdaBoost|p99.0|0.548000|0.536204|0.542038|0.990740|0.765818|0.537393|500|274|
|tabular_anomaly|GaussianNB|top_1pct|0.228000|0.223092|0.225519|0.984340|0.607646|0.217623|500|114|
|tabular_anomaly|GaussianNB|top_5pct|0.081200|0.397260|0.134839|0.947900|0.675423|0.161907|2500|203|
|tabular_anomaly|GaussianNB|p99.5|0.233607|0.223092|0.228228|0.984580|0.607767|0.220504|488|114|
|tabular_anomaly|KNN|top_1pct|0.386000|0.377691|0.381800|0.987500|0.685744|0.375510|500|193|
|tabular_anomaly|KNN|top_5pct|0.100000|0.489237|0.166058|0.949780|0.721886|0.204790|2500|250|
|tabular_anomaly|KNN|p99.5|0.588000|0.287671|0.386334|0.990660|0.642795|0.407231|250|147|
|tabular_anomaly|MLPClassifier|top_1pct|0.494000|0.483366|0.488625|0.989660|0.739127|0.483432|500|247|
|tabular_anomaly|MLPClassifier|top_5pct|0.134000|0.655577|0.222517|0.953180|0.805915|0.282344|2500|335|
|tabular_anomaly|MLPClassifier|p99.0|0.494000|0.483366|0.488625|0.989660|0.739127|0.483432|500|247|
|tabular_anomaly|CatBoost|top_1pct|0.570000|0.557730|0.563798|0.991180|0.776693|0.559378|500|285|
|tabular_anomaly|CatBoost|top_5pct|0.143200|0.700587|0.237795|0.954100|0.828652|0.303330|2500|358|
|tabular_anomaly|CatBoost|p99.0|0.570000|0.557730|0.563798|0.991180|0.776693|0.559378|500|285|
|tabular_anomaly|XGBoost|top_1pct|0.574000|0.561644|0.567755|0.991260|0.778670|0.563375|500|287|
|tabular_anomaly|XGBoost|top_5pct|0.150800|0.737769|0.250415|0.954860|0.847435|0.320665|2500|377|
|tabular_anomaly|XGBoost|p99.0|0.574000|0.561644|0.567755|0.991260|0.778670|0.563375|500|287|
|tabular_anomaly|LightGBM|top_1pct|0.576000|0.563601|0.569733|0.991300|0.779659|0.565373|500|288|
|tabular_anomaly|LightGBM|top_5pct|0.146400|0.716243|0.243109|0.954420|0.836561|0.310629|2500|366|
|tabular_anomaly|LightGBM|p99.0|0.576000|0.563601|0.569733|0.991300|0.779659|0.565373|500|288|
|tabular_anomaly|SVC RBF sampled|top_1pct|0.186000|0.181996|0.183976|0.983500|0.586886|0.175654|500|93|
|tabular_anomaly|SVC RBF sampled|top_5pct|0.086800|0.424658|0.144138|0.948460|0.689263|0.174680|2500|217|
|tabular_anomaly|SVC RBF sampled|p99.0|0.186000|0.181996|0.183976|0.983500|0.586886|0.175654|500|93|
|tabular_anomaly|IsolationForest|top_1pct|0.076000|0.074364|0.075173|0.981300|0.532514|0.065733|500|38|
|tabular_anomaly|IsolationForest|top_5pct|0.029600|0.144814|0.049153|0.942740|0.547897|0.044206|2500|74|
|tabular_anomaly|IsolationForest|p99.0|0.076000|0.074364|0.075173|0.981300|0.532514|0.065733|500|38|
|tabular_anomaly|LocalOutlierFactor novelty|top_1pct|0.220000|0.215264|0.217606|0.984180|0.603692|0.209629|500|110|
|tabular_anomaly|LocalOutlierFactor novelty|top_5pct|0.068800|0.336595|0.114248|0.946660|0.644777|0.133622|2500|172|
|tabular_anomaly|LocalOutlierFactor novelty|p99.5|0.384000|0.187867|0.252300|0.988620|0.592378|0.263448|250|96|
|tabular_anomaly|OneClassSVM RBF|top_1pct|0.228000|0.223092|0.225519|0.984340|0.607646|0.217623|500|114|
|tabular_anomaly|OneClassSVM RBF|top_5pct|0.066800|0.326810|0.110927|0.946460|0.639834|0.129060|2500|167|
|tabular_anomaly|OneClassSVM RBF|p99.5|0.416000|0.203523|0.273325|0.988940|0.600286|0.286002|250|104|
|tabular_anomaly|EllipticEnvelope|top_1pct|0.002000|0.001957|0.001978|0.979820|0.495937|-0.008214|500|1|
|tabular_anomaly|EllipticEnvelope|top_5pct|0.017600|0.086106|0.029226|0.941540|0.518239|0.016834|2500|44|
|tabular_anomaly|EllipticEnvelope|p87.0|0.019231|0.244618|0.035658|0.864780|0.557901|0.034632|6500|125|
|tabular_anomaly|PCA reconstruction|top_1pct|0.226000|0.221135|0.223541|0.984300|0.606658|0.215625|500|113|
|tabular_anomaly|PCA reconstruction|top_5pct|0.068800|0.336595|0.114248|0.946660|0.644777|0.133622|2500|172|
|tabular_anomaly|PCA reconstruction|p99.0|0.226000|0.221135|0.223541|0.984300|0.606658|0.215625|500|113|

