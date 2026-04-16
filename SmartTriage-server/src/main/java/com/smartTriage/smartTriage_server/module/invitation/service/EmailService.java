package com.smartTriage.smartTriage_server.module.invitation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Email service for sending invitation and notification emails.
 *
 * SMTP configuration is in application.properties — set your sender
 * email and app password there.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${smarttriage.mail.from-address}")
    private String fromAddress;

    @Value("${smarttriage.mail.from-name:SmartTriage}")
    private String fromName;

    @Value("${smarttriage.app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    /**
     * Send an invitation email with a link to activate the account.
     */
    public void sendInvitationEmail(String toEmail, String token, String roleName, String hospitalName) {
        String activationLink = frontendUrl + "/activate?token=" + token;
        String subject = "You've been invited to SmartTriage";

        String html = """
                <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 600px; margin: 0 auto; background: #f8fafc; border-radius: 16px; overflow: hidden;">
                  <div style="background: linear-gradient(135deg, #1e293b, #334155); padding: 32px 24px; text-align: center;">
                    <h1 style="color: #ffffff; font-size: 24px; margin: 0 0 8px 0; font-weight: 700;">SmartTriage</h1>
                    <p style="color: rgba(255,255,255,0.6); font-size: 14px; margin: 0;">AI-Assisted Emergency Department System</p>
                  </div>
                  <div style="padding: 32px 24px;">
                    <h2 style="color: #1e293b; font-size: 20px; margin: 0 0 16px 0;">You've been invited!</h2>
                    <p style="color: #475569; font-size: 14px; line-height: 1.6; margin: 0 0 8px 0;">
                      You have been invited to join <strong>%s</strong> on the SmartTriage platform as a <strong>%s</strong>.
                    </p>
                    <p style="color: #475569; font-size: 14px; line-height: 1.6; margin: 0 0 24px 0;">
                      Click the button below to complete your account setup. You'll be asked to set your name and password.
                    </p>
                    <div style="text-align: center; margin: 24px 0;">
                      <a href="%s" style="display: inline-block; background: linear-gradient(135deg, #0891b2, #06b6d4); color: #ffffff; padding: 14px 32px; border-radius: 12px; text-decoration: none; font-size: 14px; font-weight: 700; letter-spacing: 0.5px;">
                        Activate Your Account
                      </a>
                    </div>
                    <p style="color: #94a3b8; font-size: 12px; line-height: 1.5; margin: 24px 0 0 0;">
                      This invitation link expires in 48 hours. If you did not expect this email, you can safely ignore it.
                    </p>
                    <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 24px 0;" />
                    <p style="color: #94a3b8; font-size: 11px; margin: 0;">
                      If the button doesn't work, copy and paste this URL into your browser:<br/>
                      <span style="color: #0891b2; word-break: break-all;">%s</span>
                    </p>
                  </div>
                </div>
                """.formatted(hospitalName, roleName, activationLink, activationLink);

        sendHtmlEmail(toEmail, subject, html);
    }

    private void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent to {}: {}", to, subject);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send invitation email. Please check SMTP configuration.", e);
        }
    }
}
