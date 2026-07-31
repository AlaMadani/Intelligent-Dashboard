package com.neo.dashboard.repository;

import com.neo.dashboard.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link User} entities.
 * Handles authentication-related lookups needed for login and registration flows.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** Looks up a user by their email address — used during login. */
    Optional<User> findByEmail(String email);

    /** Checks whether an email address is already registered — used during sign-up to avoid duplicates. */
    boolean existsByEmail(String email);
}
