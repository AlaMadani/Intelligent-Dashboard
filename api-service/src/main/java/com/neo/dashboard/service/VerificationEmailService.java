package com.neo.dashboard.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VerificationEmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.auth.verification.mail-enabled:false}")
    private boolean mailEnabled;

    @Value("${app.auth.verification.from:}")
    private String configuredFrom;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.auth.verification.logo-url:}")
    private String logoUrl;

    public VerificationEmailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

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
        } catch (MailException | MessagingException e) {
            log.error("Failed to send {} email to {}", logPurpose, email, e);
        }
    }

    private String buildHtml(String fullName, String code, String title, String intro, String note) {
        String safeName = escapeHtml(firstNonBlank(fullName, "there"));
        String brandBlock = buildBrandBlock();

        return """
                <!doctype html>
                <html lang="en">
                <body style="margin:0;background:#f3f4f6;font-family:Inter, Segoe UI, Arial, sans-serif;color:#111111;">
                  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#f3f4f6;padding:32px 12px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:600px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;box-shadow:0 10px 25px rgba(0,0,0,0.05);">
                          <tr>
                            <td style="background:#ffffff;padding:40px 32px 30px;border-bottom:4px solid #e94b58;">
                              <div style="margin-bottom:25px;">{{brandBlock}}</div>
                              <div style="color:#111111;font-size:35px;font-weight:200;line-height:1.2;letter-spacing:-0.5px;">{{title}}</div>
                              <div style="margin-top:10px;color:#666666;font-size:15px;line-height:1.55;">{{intro}}</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:34px 32px 30px;">
                              <p style="margin:0;color:#666666;font-size:15px;line-height:1.65;">Hi <strong style="color:#111111;">{{safeName}}</strong>,</p>
                              <p style="margin:12px 0 0;color:#666666;font-size:15px;line-height:1.65;">Enter this 6-digit code in the verification screen:</p>
                              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="margin:30px 0 28px;">
                                <tr>
                                  <td align="center" style="padding:24px 12px;border:2px dashed #e94b58;border-radius:10px;background:#fff9f9;">
                                    <div style="display:inline-block;color:#e94b58;font-size:42px;font-weight:900;letter-spacing:10px;line-height:1;font-family:'Courier New', Courier, monospace;">{{code}}</div>
                                  </td>
                                </tr>
                              </table>
                              <div style="padding:16px;border-left:4px solid #666666;background:#f9f9f9;color:#666666;font-size:13px;line-height:1.6;">{{note}}</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:24px 32px;background:#ffffff;border-top:1px solid #f0f0f0;color:#999999;font-size:12px;line-height:1.5;text-align:center;">
                              If you did not request this action, you can safely ignore this email.
                              <br><br>
                              <span style="color:#666666;">NoveoCare</span>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """
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
            return "<div style=\"display:inline-block;background:#ffffff;border-radius:8px;padding:10px 14px;\"><img src=\""
                    + escapeHtml(configuredLogo)
                    + "\" alt=\"NoveoCare\" style=\"display:block;max-width:180px;height:auto;border:0;\" /></div>";
        }
        return "<div style=\"display:inline-block;background:#ffffff;border-radius:8px;padding:10px 14px;font-size:25px;font-weight:900;color:#666666;letter-spacing:-0.2px;\">Noveo<span style=\"color:#e94b58;\">Care</span></div>";
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
