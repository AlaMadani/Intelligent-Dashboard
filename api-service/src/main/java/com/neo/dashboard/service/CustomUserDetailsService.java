package com.neo.dashboard.service;

import com.neo.dashboard.entity.User;
import com.neo.dashboard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges the application's User entity with Spring Security's
 * authentication subsystem by resolving email addresses to
 * {@link UserDetails} instances.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    /** Repository for loading persisted user accounts. */
    private final UserRepository userRepository;

    /**
     * Fetches the user by email and returns a Spring Security
     * {@link UserDetails} with the stored password hash, the user's
     * role as a granted authority, and an active/inactive flag
     * derived from the enabled + email-verified state.
     *
     * @param email the email identifying the user
     * @return a fully populated UserDetails
     * @throws UsernameNotFoundException if no user exists for the email
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Look up the user or throw immediately if not found
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        // Build a Security User with hashed credentials, granted role, and status
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().toString()))
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(!user.isEnabled() || !user.isEmailVerified())
                .build();
    }
}
