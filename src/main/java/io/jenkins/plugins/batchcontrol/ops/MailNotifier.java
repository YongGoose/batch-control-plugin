package io.jenkins.plugins.batchcontrol.ops;

import hudson.Extension;
import hudson.model.User;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.JenkinsLocationConfiguration;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The shipped e-mail {@link BatchControlNotifier} (SPEC item 13, D-36). Mailer is an optional
 * dependency: this extension is {@code optional}, so without Mailer it is simply not loaded.
 * Nothing is sent unless the global option {@code emailNotifications} is on. The address is the
 * recipient's Mailer user property; a recipient without one is skipped. Messages are plain text.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public class MailNotifier extends BatchControlNotifier {

    private static final Logger LOGGER = Logger.getLogger(MailNotifier.class.getName());

    private static final String UTF_8 = "UTF-8";

    @Override
    public void notify(NotificationEvent event, Notification notification) {
        if (!BatchControlGlobalConfiguration.get().isEmailNotifications()) {
            return;
        }
        String subject = subject(event, notification);
        String body = body(event, notification);
        for (String userId : notification.getRecipients()) {
            String address = addressOf(userId);
            if (address == null) {
                LOGGER.fine(() -> "No e-mail address for user '" + userId + "'; skipping " + event);
                continue;
            }
            try {
                MimeMessage message = buildMessage(address, subject, body);
                Transport.send(message);
            } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not send the " + event + " e-mail for request "
                        + notification.getRequestId() + " to user '" + userId + "'", e);
            }
        }
    }

    /**
     * A single-part {@code text/plain; charset=UTF-8} message (SPEC item 13: plain text), built on
     * Mailer's session with the sender and reply-to Mailer's own builder would use: the Jenkins
     * administrator address and Mailer's configured reply-to address.
     */
    static MimeMessage buildMessage(String address, String subject, String body)
            throws MessagingException, UnsupportedEncodingException {
        Mailer.DescriptorImpl mailer = Mailer.descriptor();
        MimeMessage message = new MimeMessage(mailer.createSession());
        String from = JenkinsLocationConfiguration.get().getAdminAddress();
        if (from != null && !from.trim().isEmpty()) {
            message.setFrom(Mailer.stringToAddress(from.trim(), UTF_8));
        }
        String replyTo = mailer.getReplyToAddress();
        if (replyTo != null && !replyTo.trim().isEmpty()) {
            message.setReplyTo(new Address[] {Mailer.stringToAddress(replyTo.trim(), UTF_8)});
        }
        message.setRecipient(Message.RecipientType.TO, Mailer.stringToAddress(address, UTF_8));
        message.setSubject(subject, UTF_8);
        message.setText(body, UTF_8);
        message.setSentDate(new Date());
        return message;
    }

    private static String addressOf(String userId) {
        User user = User.getById(userId, false);
        if (user == null) {
            return null;
        }
        Mailer.UserProperty property = user.getProperty(Mailer.UserProperty.class);
        String address = property == null ? null : property.getAddress();
        return address == null || address.trim().isEmpty() ? null : address.trim();
    }

    static String subject(NotificationEvent event, Notification n) {
        String what = kindLabel(n) + " ";
        return oneLine("[Batch Control] " + event.getTitle() + ": " + what + n.getRequestId()
                + " (" + n.getSubject() + ")");
    }

    static String body(NotificationEvent event, Notification n) {
        boolean grant = Notification.KIND_GRANT.equals(n.getKind());
        StringBuilder text = new StringBuilder();
        text.append(event.getTitle()).append("\n\n");
        text.append("Request: ").append(n.getRequestId())
                .append(" (").append(kindLabel(n)).append(')').append('\n');
        text.append(grant ? "Scope: " : "Job: ").append(nullToEmpty(n.getSubject())).append('\n');
        text.append("Requester: ").append(nullToEmpty(n.getRequester())).append('\n');
        // security-08 S-08: the link comes before the free-text reason, and every reason line is
        // quoted, so a multi-line reason cannot pose as another field (such as a forged link).
        if (n.getUrl() != null) {
            text.append("Link: ").append(n.getUrl()).append('\n');
        }
        text.append("Reason:\n");
        for (String line : nullToEmpty(n.getReason()).split("\\r\\n|\\r|\\n", -1)) {
            text.append("> ").append(line).append('\n');
        }
        return text.toString();
    }

    private static String kindLabel(Notification n) {
        if (Notification.KIND_GRANT.equals(n.getKind())) {
            return "change request";
        }
        if (Notification.KIND_ACTIVATION.equals(n.getKind())) {
            return "activation request";
        }
        return "run request";
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ");
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
