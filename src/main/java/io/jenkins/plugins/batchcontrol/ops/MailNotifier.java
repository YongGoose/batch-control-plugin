package io.jenkins.plugins.batchcontrol.ops;

import hudson.Extension;
import hudson.model.User;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.plugins.mailer.tasks.MimeMessageBuilder;
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
                MimeMessage message = new MimeMessageBuilder()
                        .setMimeType("text/plain")
                        .setSubject(subject)
                        .setBody(body)
                        .addRecipients(address)
                        .buildMimeMessage();
                Transport.send(message);
            } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not send the " + event + " e-mail for request "
                        + notification.getRequestId() + " to user '" + userId + "'", e);
            }
        }
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
        String what = Notification.KIND_GRANT.equals(n.getKind()) ? "change request " : "run request ";
        return oneLine("[Batch Control] " + event.getTitle() + ": " + what + n.getRequestId()
                + " (" + n.getSubject() + ")");
    }

    static String body(NotificationEvent event, Notification n) {
        boolean grant = Notification.KIND_GRANT.equals(n.getKind());
        StringBuilder text = new StringBuilder();
        text.append(event.getTitle()).append("\n\n");
        text.append("Request: ").append(n.getRequestId())
                .append(grant ? " (change request)" : " (run request)").append('\n');
        text.append(grant ? "Scope: " : "Job: ").append(nullToEmpty(n.getSubject())).append('\n');
        text.append("Requester: ").append(nullToEmpty(n.getRequester())).append('\n');
        text.append("Reason: ").append(nullToEmpty(n.getReason())).append('\n');
        text.append("Link: ").append(nullToEmpty(n.getUrl())).append('\n');
        return text.toString();
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ");
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
