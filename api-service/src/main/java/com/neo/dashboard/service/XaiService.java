package com.neo.dashboard.service;

import com.neo.dashboard.dto.AuditTrailEvent;
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

        // NIVEAU 1 : VÃ©rification dans le cache REDIS (Ultra rapide)
        String cachedExplanation = redisTemplate.opsForValue().get(cacheKey);
        if (cachedExplanation != null) {
            log.info("Explication trouvÃ©e dans REDIS pour l'alerte {}", alertId);
            return cachedExplanation;
        }

        // RÃ©cupÃ©ration de l'alerte en base SQL
        SecurityAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alerte introuvable"));

        // NIVEAU 2 : VÃ©rification dans SQL SERVER (Persistance Ã  froid)
        // On s'assure que le champ n'est pas null et qu'il ne contient pas le fameux placeholder "En attente..."
        if (alert.getAiExplanation() != null && !alert.getAiExplanation().contains("En attente")) {
            log.info("Explication trouvÃ©e dans SQL SERVER pour l'alerte {}. Remise en cache Redis.", alertId);
            // On le remet dans Redis pour la prochaine fois (ex: pour 7 jours)
            redisTemplate.opsForValue().set(cacheKey, alert.getAiExplanation(), Duration.ofDays(7));
            return alert.getAiExplanation();
        }

        // NIVEAU 3 : Aucun historique, appel Ã  GOOGLE GEMINI
        log.info("Explication introuvable. GÃ©nÃ©ration par GEMINI en cours pour l'alerte {}...", alertId);

        // On rÃ©cupÃ¨re les logs Redis du hacker
        List<AuditTrailEvent> history = investigationService.getUserHistory(alert.getUserKey());

        // Construction du prompt
        String prompt = buildPrompt(alert, history);

        // Appel API
        String explanation = chatClient.prompt()
                .user(prompt)
                .system("Tu es un analyste SOC expert en cybersÃ©curitÃ©. " +
                        "Analyse ces logs d'actions et explique de faÃ§on claire, concise et professionnelle " +
                        "pourquoi ce comportement est une anomalie. Donne une recommandation d'action.")
                .call()
                .content();

        // SAUVEGARDE 1 : Mise Ã  jour dans SQL Server (pour la persistance Ã  long terme)
        alert.setAiExplanation(explanation);
        alertRepository.save(alert); // Fera un UPDATE grÃ¢ce Ã  Hibernate

        // SAUVEGARDE 2 : Mise en cache Redis APRÃˆS commit (pour Ã©viter le cache si l'UPDATE SQL Ã©choue)
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

        log.info("Analyse GEMINI terminÃ©e et sauvegardÃ©e pour l'alerte {}", alertId);
        return explanation;
    }

    private String buildPrompt(SecurityAlert alert, List<AuditTrailEvent> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Alerte de sÃ©curitÃ© de type : ").append(alert.getAlertType()).append("\n");
        sb.append("Score de sÃ©vÃ©ritÃ© (MSE) : ").append(alert.getAnomalyScore()).append("\n\n");
        sb.append("Historique des actions rÃ©centes de l'utilisateur (ID: ").append(alert.getUserKey()).append(") :\n");

        if (history.isEmpty()) {
            sb.append("Aucun historique rÃ©cent trouvÃ© dans la fenÃªtre glissante.\n");
        } else {
            for (AuditTrailEvent event : history) {
                sb.append("- Action: ").append(event.getAction())
                        .append(" | Objet: ").append(event.getObject())
                        .append(" | SuccÃ¨s: ").append(event.getSuccess())
                        .append(" | DÃ©tails: ").append(event.getDetails()).append("\n");
            }
        }

        return sb.toString();
    }
}
