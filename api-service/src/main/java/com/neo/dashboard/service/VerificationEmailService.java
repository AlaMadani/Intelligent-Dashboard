package com.neo.dashboard.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationEmailService {

    private static final String EMAIL_TEMPLATE = "templates/email/auth-code-email.html";
    private static final String EMAIL_STYLES = "templates/email/auth-code-email.css";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.auth.verification.mail-enabled:false}")
    private boolean mailEnabled;

    @Value("${app.auth.verification.from:}")
    private String configuredFrom;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.auth.verification.logo-url:}")
    private String logoUrl;

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

    private String buildHtml(String fullName, String code, String title, String intro, String note) {
        String safeName = escapeHtml(firstNonBlank(fullName, "there"));
        String brandBlock = buildBrandBlock();

        return loadResource(EMAIL_TEMPLATE)
                .replace("{{styles}}", loadResource(EMAIL_STYLES))
                .replace("{{brandBlock}}", brandBlock)
                .replace("{{title}}", escapeHtml(title))
                .replace("{{intro}}", escapeHtml(intro))
                .replace("{{safeName}}", safeName)
                .replace("{{code}}", code)
                .replace("{{note}}", escapeHtml(note));
    }

    private String buildBrandBlock() {
        String configuredLogo = firstNonBlank(logoUrl);
        if (configuredLogo != null) {
            return "<div class=\"brand brand-logo\"><img src=\""
                    + escapeHtml(configuredLogo)
                    + "\" alt=\"NoveoCare\" /></div>";
        }
        return "<div class=\"brand brand-text\">Noveo<span>Care</span></div>";
    }

    private String loadResource(String path) {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Email resource not found: " + path, e);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
