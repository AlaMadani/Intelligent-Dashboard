package com.neo.dashboard.service;

import com.neo.dashboard.dto.AuditTrailEvent;
import com.neo.dashboard.dto.SequenceDetailsDto;
import com.neo.dashboard.entity.SecurityAlert;
import com.neo.dashboard.repository.SecurityAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
public class XaiService {

    private final StringRedisTemplate redisTemplate;
    private final InvestigationService investigationService;
    private final SecurityAlertRepository alertRepository;
    private final ChatClient chatClient;

    public XaiService(StringRedisTemplate redisTemplate,
                      InvestigationService investigationService,
                      SecurityAlertRepository alertRepository,
                      ChatClient.Builder chatClientBuilder) {
        this.redisTemplate = redisTemplate;
        this.investigationService = investigationService;
        this.alertRepository = alertRepository;
        this.chatClient = chatClientBuilder.build();
    }

    // Le @Transactional est important ici car nous allons modifier la base SQL
    @Transactional
    public String explainAlert(Long alertId) {
        String cacheKey = "explanation:alert:" + alertId;

        // NIVEAU 1 : Verification dans le cache REDIS (Ultra rapide)
        String cachedExplanation = redisTemplate.opsForValue().get(cacheKey);
        if (cachedExplanation != null) {
            log.info("Explication trouvee dans REDIS pour l'alerte {}", alertId);
            return cachedExplanation;
        }

        // Recuperation de l'alerte en base SQL
        SecurityAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alerte introuvable"));

        // NIVEAU 2 : Verification dans SQL SERVER (Persistance a froid)
        // On s'assure que le champ n'est pas null et qu'il ne contient pas le fameux placeholder "En attente..."
        if (alert.getAiExplanation() != null && !alert.getAiExplanation().contains("En attente")) {
            log.info("Explication trouvee dans SQL SERVER pour l'alerte {}. Remise en cache Redis.", alertId);
            // On le remet dans Redis pour la prochaine fois (ex: pour 7 jours)
            redisTemplate.opsForValue().set(cacheKey, alert.getAiExplanation(), Duration.ofDays(7));
            return alert.getAiExplanation();
        }

        // NIVEAU 3 : Aucun historique, appel a GOOGLE GEMINI
        log.info("Explication introuvable. Generation par GEMINI en cours pour l'alerte {}...", alertId);

        // On recupere les logs Redis du hacker
        List<AuditTrailEvent> history = investigationService.getUserHistory(alert.getUserKey());
        SequenceDetailsDto sequenceDetails = investigationService.getSequenceDetailsByAlertId(alertId);

        // Construction du prompt
        String prompt = buildPrompt(alert, history, sequenceDetails);

        // Appel API
        String explanation = chatClient.prompt()
                .user(prompt)
                .system("Tu es un analyste SOC expert en cybersecurity. " +
                        "Analyse ces logs d'actions et explique de facon claire, concise et professionnelle " +
                        "pourquoi ce comportement est une anomalie. Donne une recommandation d'action.")
                .call()
                .content();

        // SAUVEGARDE 1 : Mise a jour dans SQL Server (pour la persistance a long terme)
        alert.setAiExplanation(explanation);
        alertRepository.save(alert); // Fera un UPDATE grace a Hibernate

        // SAUVEGARDE 2 : Mise en cache Redis APRES commit (pour eviter le cache si l'UPDATE SQL echoue)
        Runnable cacheWrite = () -> redisTemplate.opsForValue().set(cacheKey, explanation, Duration.ofDays(7));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cacheWrite.run();
                }
            });
        } else {
            cacheWrite.run();
        }

        log.info("Analyse GEMINI terminee et sauvegardee pour l'alerte {}", alertId);
        return explanation;
    }

    private String buildPrompt(SecurityAlert alert, List<AuditTrailEvent> history, SequenceDetailsDto sequenceDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("Alerte de securite de type : ").append(alert.getAlertType()).append("\n");
        sb.append("Score de severite (MSE) : ").append(alert.getAnomalyScore()).append("\n");
        sb.append("Seuil utilise : ").append(alert.getThresholdUsed()).append("\n");
        sb.append("Utilisateur : ").append(alert.getUserKey()).append("\n");
        sb.append("IP : ").append(alert.getIpAddress()).append("\n");
        sb.append("Detecte a : ").append(alert.getDetectedAt()).append("\n\n");
        sb.append("Historique des actions recentes de l'utilisateur (ID: ").append(alert.getUserKey()).append(") :\n");

        if (history.isEmpty()) {
            sb.append("Aucun historique recent trouve dans la fenetre glissante.\n");
        } else {
            for (AuditTrailEvent event : history) {
                sb.append("- Action: ").append(event.getAction())
                        .append(" | Objet: ").append(event.getObject())
                        .append(" | Succes: ").append(event.getSuccess())
                        .append(" | Details: ").append(event.getDetails()).append("\n");
            }
        }

        if (sequenceDetails != null) {
            if (sequenceDetails.getSequenceJson() != null && !sequenceDetails.getSequenceJson().isBlank()) {
                sb.append("\nSequence a l'origine de l'alerte (JSON) : ")
                        .append(sequenceDetails.getSequenceJson()).append("\n");
            }
            if (sequenceDetails.getPredictionSummary() != null && !sequenceDetails.getPredictionSummary().isBlank()) {
                sb.append("\nResume prediction model : ").append(sequenceDetails.getPredictionSummary()).append("\n");
            }
            if (sequenceDetails.getPredictionJson() != null && !sequenceDetails.getPredictionJson().isBlank()) {
                sb.append("Prediction details (JSON) : ").append(sequenceDetails.getPredictionJson()).append("\n");
            }
        }

        return sb.toString();
    }
}
