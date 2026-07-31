package com.neo.dashboard.config;

import com.neo.dashboard.security.JwtAuthenticationFilter;
import com.neo.dashboard.security.JwtTokenProvider;
import com.neo.dashboard.service.CustomUserDetailsService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Loads user-specific data during authentication. */
    private final CustomUserDetailsService customUserDetailsService;
    /** Creates and validates JWT tokens. */
    private final JwtTokenProvider jwtTokenProvider;
    /** Encoder used to verify password hashes. */
    private final PasswordEncoder passwordEncoder;

    /**
     * Configures a {@link DaoAuthenticationProvider} that delegates user
     * retrieval to {@link CustomUserDetailsService} and password verification
     * to the BCrypt encoder.
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(customUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    /**
     * Creates the per-request JWT filter that intercepts every HTTP request
     * to extract and validate the bearer token.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtTokenProvider, customUserDetailsService);
    }

    /** Builds the Spring Security filter chain with stateless JWT auth. */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                /* Enable CORS using the globally configured CorsFilter */
                .cors(Customizer.withDefaults())
                /* Disable CSRF because we use stateless JWT tokens */
                .csrf(csrf -> csrf.disable())
                /* Do not create or use HTTP sessions */
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                /* Return 401 / 403 instead of redirecting to a login page */
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN))
                )
                /* Define public vs. authenticated endpoints */
                .authorizeHttpRequests(authz -> authz
                        /* Allow async dispatches, error pages, and forwards */
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        /* Preflight CORS requests */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        /* Public auth endpoints (health, signup, signin, etc.) */
                        .requestMatchers(HttpMethod.GET, "/api/auth/health").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/signup",
                                "/api/auth/signin",
                                "/api/auth/verify-email",
                                "/api/auth/resend-verification",
                                "/api/auth/forgot-password",
                                "/api/auth/forgot-password/verify",
                                "/api/auth/forgot-password/reset",
                                "/api/auth/refresh"
                        ).permitAll()
                        /* Public health-check endpoint */
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        /* Actuator observability endpoints */
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        /* Everything else requires a valid JWT */
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                /* Insert JWT filter before the default username/password filter */
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

