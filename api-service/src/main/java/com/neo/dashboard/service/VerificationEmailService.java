package com.neo.dashboard.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;

/**
 * Async email service that sends verification and password-reset codes via
 * SMTP.  Uses Thymeleaf templates for the HTML body and falls back to logging
 * the code when mail is disabled.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationEmailService {

    /** Thymeleaf template name for auth-code email messages. */
    private static final String EMAIL_TEMPLATE = "email/auth-code-email";

    /** Lazy provider for {@link JavaMailSender}; may return {@code null} when not configured. */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    /** Thymeleaf template engine for rendering the HTML email body. */
    private final SpringTemplateEngine templateEngine;

    /** Whether SMTP email sending is enabled (default: {@code false}). */
    @Value("${app.auth.verification.mail-enabled:false}")
    private boolean mailEnabled;

    /** Explicit "from" address override for outgoing emails. */
    @Value("${app.auth.verification.from:}")
    private String configuredFrom;

    /** SMTP username used as fallback "from" address when not explicitly configured. */
    @Value("${spring.mail.username:}")
    private String mailUsername;

    /** URL of the brand logo to embed in the email HTML (optional). */
    @Value("${app.auth.verification.logo-url:}")
    private String logoUrl;

    /**
     * Sends an email verification code to the given address asynchronously.
     * When mail is disabled the code is simply logged.
     *
     * @param email    the recipient email address
     * @param fullName the recipient's display name
     * @param code     the verification code
     */
    @Async
    public void sendVerificationCode(String email, String fullName, String code) {
        sendCodeEmail(
                email,
                fullName,
                code,
                "Your NoveoCare verification code",
                "Verify your email address",
                "Use the security code below to finish activating your NoveoCare Insights account.",
                "This code expires in 5 minutes. If you request a new code, only the latest one remains valid.",
                "Email verification"
        );
    }

    /**
     * Sends a password-reset code to the given address asynchronously.
     * When mail is disabled the code is simply logged.
     *
     * @param email    the recipient email address
     * @param fullName the recipient's display name
     * @param code     the reset code
     */
    @Async
    public void sendPasswordResetCode(String email, String fullName, String code) {
        sendCodeEmail(
                email,
                fullName,
                code,
                "Reset your NoveoCare password",
                "Reset your password",
                "Use the security code below to verify your email before setting a new password.",
                "This password reset code expires in 5 minutes. If you request a new code, only the latest one remains valid.",
                "Password reset"
        );
    }

    /**
     * Core email-sending method.  Checks whether mail is enabled, resolves the
     * JavaMailSender, builds the HTML body via Thymeleaf, sets the from address,
     * and sends the message.  Logs the code when mail is disabled.
     *
     * @param email       the recipient email address
     * @param fullName    the recipient's display name
     * @param code        the security code
     * @param subject     the email subject line
     * @param title       the HTML title heading
     * @param intro       the introductory paragraph
     * @param note        the security/expiry note paragraph
     * @param logPurpose  a short label for log messages (e.g. "Email verification")
     */
    private void sendCodeEmail(
            String email,
            String fullName,
            String code,
            String subject,
            String title,
            String intro,
            String note,
            String logPurpose
    ) {
        /* If mail is disabled, just log the code for development/testing. */
        if (!mailEnabled) {
            log.info("{} code for {} is {}. Enable SMTP to send real email.", logPurpose, email, code);
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("SMTP email verification is enabled but JavaMailSender is not configured");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(buildHtml(fullName, code, title, intro, note), true);

            /* Use the explicit from address, falling back to the SMTP username. */
            String from = firstNonBlank(configuredFrom, mailUsername);
            if (from != null) {
                helper.setFrom(from);
            }

            mailSender.send(message);
            log.info("{} email sent to {}", logPurpose, email);
        } catch (MailException | MessagingException | IllegalStateException e) {
            log.error("Failed to send {} email to {}", logPurpose, email, e);
        }
    }

    /**
     * Builds the HTML email body using the Thymeleaf template engine.  All
     * user-supplied strings are HTML-escaped before being passed to the
     * template.
     *
     * @param fullName the recipient's name (or "there" as fallback)
     * @param code     the security code
     * @param title    the email title heading
     * @param intro    the introductory paragraph
     * @param note     the security/expiry note
     * @return the rendered HTML string
     */
    private String buildHtml(String fullName, String code, String title, String intro, String note) {
        Context ctx = new Context();
        ctx.setVariables(Map.of(
                "safeName", escapeHtml(firstNonBlank(fullName, "there")),
                "code", code,
                "title", escapeHtml(title),
                "intro", escapeHtml(intro),
                "note", escapeHtml(note),
                "brandBlock", buildBrandBlock()
        ));
        return templateEngine.process(EMAIL_TEMPLATE, ctx);
    }

    /**
     * Builds the brand HTML block.  If a logo URL is configured it renders an
     * {@code <img>} tag; otherwise it returns a plain text brand name.
     *
     * @return the brand HTML snippet
     */
    private String buildBrandBlock() {
        String configuredLogo = firstNonBlank(logoUrl);
        if (configuredLogo != null) {
            return "<div class=\"brand brand-logo\"><img src=\""
                    + escapeHtml(configuredLogo)
                    + "\" alt=\"NoveoCare\" /></div>";
        }
        return "<div class=\"brand brand-text\">Noveo<span>Care</span></div>";
    }

    /**
     * Returns the first non-blank value from the given array, or {@code null}
     * if all values are blank.
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * HTML-escapes a string by replacing the five XML special characters with
     * their corresponding entities.
     *
     * @param value the raw string
     * @return the escaped string, safe for HTML insertion
     */
    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
