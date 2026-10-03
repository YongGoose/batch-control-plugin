package io.jenkins.plugins.batchcontrol.security;

import com.michelin.cio.hudson.plugins.rolestrategy.AuthorizationType;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Functions;
import hudson.Util;
import hudson.model.User;
import hudson.security.GroupDetails;
import hudson.security.SecurityRealm;
import hudson.security.UserMayOrMayNotExistException2;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkins.ui.symbol.Symbol;
import org.jenkins.ui.symbol.SymbolRequest;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The sid checks role-strategy's Assign Roles page calls on the installed strategy's descriptor
 * ({@code /descriptor/<class>/checkName} up to role-strategy 918, {@code checkSidName} from the
 * redesigned page of role-strategy PR #766 on), re-implemented here so that
 * {@link BatchControlRoleBasedAuthorizationStrategy.DescriptorImpl} does not call descriptor
 * methods role-strategy removes (D-35f: the Batch Control variant must keep working across
 * role-strategy's UI rework).
 *
 * <p>Adapted from role-strategy's {@code RoleBasedAuthorizationStrategy.DescriptorImpl#doCheckName}
 * and {@code ValidationUtil} (version 918.v91e5468d8db_2) and their redesigned versions in
 * jenkinsci/role-strategy-plugin PR #766, which are package-private there.
 * role-strategy is Copyright (c) 2010-2011 Thomas Maurel, Romain Seguy and contributors, MIT
 * License. The responses (HTML snippets, messages, kinds) are kept the same so that role-strategy's
 * pages render them as under the plain strategy. Every name taken from the request or the realm
 * is escaped before it is put into a snippet; plain messages go through
 * {@link FormValidation#error(String)} and {@link FormValidation#ok(String)}, which escape them.
 *
 * <p>The callers check permissions first (inline in each web method); these methods only read.
 */
@Restricted(NoExternalUse.class)
final class RoleSidChecks {

    private static final int MAX_DISPLAY_NAME = 50;

    private RoleSidChecks() {
    }

    // ------------------------------------------------------------------ checkName (role-strategy 918)

    /**
     * role-strategy 918's {@code doCheckName}: {@code value} is {@code [TYPE:sid]}; the answer is
     * the snippet the Assign Roles table shows instead of the plain sid.
     */
    static FormValidation checkName(@CheckForNull String value) {
        if (value == null || value.length() < 2) {
            return FormValidation.error("No type prefix: " + Util.fixNull(value)); // error() escapes
        }
        final String unbracketedValue = value.substring(1, value.length() - 1);
        final int splitIndex = unbracketedValue.indexOf(':');
        if (splitIndex < 0) {
            return FormValidation.error("No type prefix: " + unbracketedValue);
        }
        final String typeString = unbracketedValue.substring(0, splitIndex);
        final AuthorizationType type;
        try {
            type = AuthorizationType.valueOf(typeString);
        } catch (RuntimeException ex) {
            return FormValidation.error("Invalid type prefix: " + unbracketedValue);
        }
        String sid = unbracketedValue.substring(splitIndex + 1);
        String escapedSid = Functions.escape(sid);

        if (!Jenkins.get().hasPermission(Jenkins.SYSTEM_READ)) {
            return FormValidation.ok(escapedSid); // can't check
        }
        SecurityRealm sr = Jenkins.get().getSecurityRealm();

        if (sid.equals("authenticated") && type == AuthorizationType.EITHER) {
            return FormValidation.respond(FormValidation.Kind.OK, format(type, escapedSid,
                    "Internal group found; but permissions would also be granted to a user of this name", true));
        }
        if (sid.equals("anonymous") && type == AuthorizationType.EITHER) {
            return FormValidation.respond(FormValidation.Kind.OK, format(type, escapedSid,
                    "Internal user found; but permissions would also be granted to a group of this name", true));
        }
        try {
            FormValidation groupValidation;
            FormValidation userValidation;
            switch (type) {
                case GROUP:
                    groupValidation = validateGroup(sid, sr, false);
                    if (groupValidation != null) {
                        return groupValidation;
                    }
                    return FormValidation.respond(FormValidation.Kind.OK,
                            formatNotFound(type, escapedSid, "Group not found", false));
                case USER:
                    userValidation = validateUser(sid, sr, false);
                    if (userValidation != null) {
                        return userValidation;
                    }
                    return FormValidation.respond(FormValidation.Kind.OK,
                            formatNotFound(type, escapedSid, "User not found", false));
                case EITHER:
                    userValidation = validateUser(sid, sr, true);
                    if (userValidation != null) {
                        return userValidation;
                    }
                    groupValidation = validateGroup(sid, sr, true);
                    if (groupValidation != null) {
                        return groupValidation;
                    }
                    return FormValidation.respond(FormValidation.Kind.OK,
                            formatNotFound(type, escapedSid, "User or group not found", true));
                default:
                    return FormValidation.error("Unexpected type: " + type);
            }
        } catch (RuntimeException e) {
            // If the check fails, the user still sees the (escaped) name.
            return FormValidation.error(e, escapedSid);
        }
    }

    @CheckForNull
    private static FormValidation validateGroup(String groupName, SecurityRealm sr, boolean ambiguous) {
        String escapedSid = Functions.escape(groupName);
        try {
            GroupDetails details = sr.loadGroupByGroupname2(groupName, false);
            escapedSid = Util.escape(StringUtils.abbreviate(details.getDisplayName(), MAX_DISPLAY_NAME));
            if (ambiguous) {
                return FormValidation.respond(FormValidation.Kind.WARNING, format(AuthorizationType.GROUP, escapedSid,
                        "Group found; but permissions would also be granted to a user of this name", true));
            }
            return FormValidation.respond(FormValidation.Kind.OK,
                    format(AuthorizationType.GROUP, escapedSid, "Group", false));
        } catch (UserMayOrMayNotExistException2 e) {
            if (ambiguous) {
                return FormValidation.respond(FormValidation.Kind.WARNING, format(AuthorizationType.GROUP, escapedSid,
                        "Permissions would also be granted to a user or group of this name", true));
            }
            return FormValidation.ok(escapedSid);
        } catch (UsernameNotFoundException e) {
            return null; // not found: the caller answers
        } catch (AuthenticationException e) {
            return FormValidation.error(e, "Failed to test the validity of the group name " + groupName);
        }
    }

    @CheckForNull
    private static FormValidation validateUser(String userName, SecurityRealm sr, boolean ambiguous) {
        String escapedSid = Functions.escape(userName);
        try {
            sr.loadUserByUsername2(userName);
            // Unlike role-strategy 918, no User record is created as a side effect of a check
            // (as in PR #766): without one the sid is the display name.
            User u = User.getById(userName, false);
            String fullName = u == null ? userName : u.getFullName();
            if (userName.equals(fullName)) {
                if (ambiguous) {
                    return FormValidation.respond(FormValidation.Kind.WARNING, format(AuthorizationType.EITHER,
                            escapedSid, "User found; but permissions would also be granted to a group of this name",
                            true));
                }
                return FormValidation.respond(FormValidation.Kind.OK,
                        format(AuthorizationType.USER, escapedSid, "User", false));
            }
            String escapedFullName = Util.escape(StringUtils.abbreviate(fullName, MAX_DISPLAY_NAME));
            if (ambiguous) {
                return FormValidation.respond(FormValidation.Kind.WARNING, format(AuthorizationType.EITHER,
                        escapedFullName, "User " + escapedSid
                                + " found, but permissions would also be granted to a group of this name", true));
            }
            return FormValidation.respond(FormValidation.Kind.OK,
                    format(AuthorizationType.USER, escapedFullName, "User " + escapedSid, false));
        } catch (UserMayOrMayNotExistException2 e) {
            if (ambiguous) {
                return FormValidation.respond(FormValidation.Kind.WARNING, format(AuthorizationType.EITHER, escapedSid,
                        "Permissions would also be granted to a user or group of this name", true));
            }
            return FormValidation.ok(escapedSid);
        } catch (UsernameNotFoundException e) {
            return null; // not found: the caller answers
        } catch (AuthenticationException e) {
            return FormValidation.error(e, "Failed to test the validity of the user name " + userName);
        }
    }

    // ------------------------------------------------------------------ checkSidName (role-strategy PR #766)

    /**
     * role-strategy PR #766's {@code doCheckSidName}: looks {@code value} up as a user or group
     * ({@code type} {@code USER} or {@code GROUP}) and answers the snippet the Assign Roles dialog
     * shows below the name field. A sid the realm does not know still validates OK (struck
     * through): an assignment for it is legal.
     */
    static FormValidation checkSidName(@CheckForNull String value, @CheckForNull String type) {
        String sid = Util.fixEmptyAndTrim(value);
        if (sid == null) {
            return FormValidation.ok();
        }
        String escapedSid = Functions.escape(sid);
        if (!Jenkins.get().hasPermission(Jenkins.SYSTEM_READ)) {
            return FormValidation.ok(escapedSid); // can't check
        }
        final AuthorizationType authType;
        try {
            authType = AuthorizationType.valueOf(type);
        } catch (RuntimeException e) {
            return FormValidation.error("Invalid type: " + type); // error() escapes
        }
        if (authType == AuthorizationType.EITHER) {
            // ambiguous entries cannot be created from the dialog
            return FormValidation.error("Invalid type: " + type);
        }
        boolean isGroup = authType == AuthorizationType.GROUP;
        if ("authenticated".equals(sid) && isGroup) {
            return FormValidation.respond(FormValidation.Kind.OK, format(authType, escapedSid, "Internal group", false));
        }
        if ("anonymous".equals(sid) && !isGroup) {
            return FormValidation.respond(FormValidation.Kind.OK, format(authType, escapedSid, "Internal user", false));
        }
        SecurityRealm realm = Jenkins.get().getSecurityRealm();
        Resolution resolution;
        try {
            resolution = isGroup ? resolveGroup(sid, realm) : resolveUser(sid, realm);
        } catch (RuntimeException e) {
            // A realm may throw for lookups it does not support: inconclusive, not a failure.
            return FormValidation.ok(escapedSid);
        }
        String kindWord = isGroup ? "Group" : "User";
        switch (resolution.kind) {
            case FOUND:
                if (resolution.displayName == null) {
                    return FormValidation.respond(FormValidation.Kind.OK, format(authType, escapedSid, kindWord, false));
                }
                return FormValidation.respond(FormValidation.Kind.OK, format(authType,
                        Util.escape(resolution.displayName), kindWord + " " + escapedSid, false));
            case NOT_FOUND:
                return FormValidation.respond(FormValidation.Kind.OK,
                        formatNotFound(authType, escapedSid, kindWord + " not found", false));
            default:
                // the realm cannot decide: the plain sid without a verdict
                return FormValidation.ok(escapedSid);
        }
    }

    private enum Kind { FOUND, NOT_FOUND, UNKNOWN }

    private static final class Resolution {
        final Kind kind;
        @CheckForNull
        final String displayName;

        Resolution(Kind kind, @CheckForNull String displayName) {
            this.kind = kind;
            this.displayName = displayName;
        }
    }

    private static Resolution resolveUser(String userName, SecurityRealm sr) {
        try {
            sr.loadUserByUsername2(userName);
            User user = User.getById(userName, false); // no User record as a side effect
            String fullName = user != null ? user.getFullName() : userName;
            String displayName = userName.equals(fullName) ? null : StringUtils.abbreviate(fullName, MAX_DISPLAY_NAME);
            return new Resolution(Kind.FOUND, displayName);
        } catch (UserMayOrMayNotExistException2 e) {
            return new Resolution(Kind.UNKNOWN, null);
        } catch (UsernameNotFoundException e) {
            return new Resolution(Kind.NOT_FOUND, null);
        } catch (AuthenticationException e) {
            return new Resolution(Kind.UNKNOWN, null); // a realm failure says nothing about the sid
        }
    }

    private static Resolution resolveGroup(String groupName, SecurityRealm sr) {
        try {
            GroupDetails details = sr.loadGroupByGroupname2(groupName, false);
            String display = details.getDisplayName();
            String displayName = display == null || groupName.equals(display)
                    ? null : StringUtils.abbreviate(display, MAX_DISPLAY_NAME);
            return new Resolution(Kind.FOUND, displayName);
        } catch (UserMayOrMayNotExistException2 e) {
            return new Resolution(Kind.UNKNOWN, null);
        } catch (UsernameNotFoundException e) {
            return new Resolution(Kind.NOT_FOUND, null);
        } catch (AuthenticationException e) {
            return new Resolution(Kind.UNKNOWN, null);
        }
    }

    // ------------------------------------------------------------------ snippets

    /**
     * The icon and the already escaped {@code name}, as role-strategy renders it. {@code tooltip}
     * is either constant text or contains only an escaped sid.
     */
    private static String format(AuthorizationType type, String name, String tooltip, boolean alert) {
        String symbol = symbol(type == AuthorizationType.GROUP ? "people" : "person", "icon-sm");
        if (alert) {
            return String.format("<div tooltip='%s' class='rsp-table__cell'>%s%s%s</div>", tooltip,
                    symbol("warning", "icon-md rsp-table__icon-alert"), symbol, name);
        }
        return String.format("<div tooltip='%s' class='rsp-table__cell'>%s%s</div>", tooltip, symbol, name);
    }

    private static String formatNotFound(AuthorizationType type, String escapedName, String tooltip, boolean alert) {
        return format(type, "<span class='rsp-entry-not-found'>" + escapedName + "</span>", tooltip, alert);
    }

    private static String symbol(String name, String classes) {
        return Symbol.get(new SymbolRequest.Builder()
                .withRaw("symbol-" + name + "-outline plugin-ionicons-api").withClasses(classes).build());
    }
}
