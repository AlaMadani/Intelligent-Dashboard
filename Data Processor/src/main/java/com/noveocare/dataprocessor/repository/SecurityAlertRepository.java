package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.SecurityAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecurityAlertRepository extends JpaRepository<SecurityAlert, Long> {

    // Bonus 1 : Pour afficher l'historique d'un utilisateur suspect précis
    List<SecurityAlert> findByUserKeyOrderByDetectedAtDesc(Integer userKey);

    // Bonus 2 : Pour afficher les toutes dernières alertes sur le dashboard général
    List<SecurityAlert> findTop50ByOrderByDetectedAtDesc();
}