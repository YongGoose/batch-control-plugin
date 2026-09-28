package io.jenkins.plugins.batchcontrol.poc.auth;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Job;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import hudson.util.AtomicFileWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;

/**
 * PoC-5 option B: no wrapper. An approved window is written as native permission entries
 * (a matrix-auth job property entry, or a role-strategy item role assigned to the user) and
 * removed again by {@link #sweep()}. A ledger file ({@code poc5-native-grants.txt}) records
 * every entry the plugin added, so a sweep after a restart knows what to take back.
 *
 * <p>Ordering is the whole point: {@code recordFirst=true} writes the ledger line before the
 * native entry (write-ahead), {@code false} does it the other way round. {@code crashAfterFirstStep}
 * stops after the first durable step to simulate a JVM crash between the two writes.
 */
public final class PocNativeGrants {

    public static volatile Clock clock = Clock.systemUTC();

    private PocNativeGrants() {
    }

    /** One ledger line: id, kind (MATRIX|ROLE), target (job full name | role pattern), sid, permission id, expiry, preExisting. */
    public record Rec(String id, String kind, String target, String sid, String perm, long expires, boolean preExisting) {
        String line() {
            return String.join("\t", id, kind, target, sid, perm, Long.toString(expires), Boolean.toString(preExisting));
        }

        static Rec parse(String l) {
            String[] f = l.split("\t");
            return new Rec(f[0], f[1], f[2], f[3], f[4], Long.parseLong(f[5]), Boolean.parseBoolean(f[6]));
        }
    }

    // ------------------------------------------------------------------ matrix-auth

    public static synchronized void grantMatrix(String id, String user, Job<?, ?> job, Permission p, Instant expires,
                                                boolean recordFirst, boolean crashAfterFirstStep) throws IOException {
        AuthorizationMatrixProperty prop = job.getProperty(AuthorizationMatrixProperty.class);
        boolean pre = prop != null
                && prop.hasExplicitPermission(org.jenkinsci.plugins.matrixauth.PermissionEntry.user(user), p);
        Rec r = new Rec(id, "MATRIX", job.getFullName(), user, p.getId(), expires.toEpochMilli(), pre);
        if (recordFirst) {
            append(r);
            if (crashAfterFirstStep) {
                return;
            }
            addMatrixEntry(job, user, p);
        } else {
            addMatrixEntry(job, user, p);
            if (crashAfterFirstStep) {
                return;
            }
            append(r);
        }
    }

    private static void addMatrixEntry(Job<?, ?> job, String user, Permission p) throws IOException {
        AuthorizationMatrixProperty prop = job.getProperty(AuthorizationMatrixProperty.class);
        if (prop == null) {
            prop = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
            prop.add(p, org.jenkinsci.plugins.matrixauth.PermissionEntry.user(user));
            job.addProperty(prop);
        } else {
            prop.add(p, org.jenkinsci.plugins.matrixauth.PermissionEntry.user(user));
            job.save();
        }
    }

    /** Rebuilds the property without the entry (matrix-auth has no public remove API). */
    private static void removeMatrixEntry(Job<?, ?> job, String user, Permission p) throws IOException {
        AuthorizationMatrixProperty prop = job.getProperty(AuthorizationMatrixProperty.class);
        if (prop == null) {
            return;
        }
        Map<Permission, Set<org.jenkinsci.plugins.matrixauth.PermissionEntry>> copy = new HashMap<>();
        prop.getGrantedPermissionEntries().forEach((k, v) -> copy.put(k, new HashSet<>(v)));
        Set<org.jenkinsci.plugins.matrixauth.PermissionEntry> s = copy.get(p);
        if (s == null || !s.remove(org.jenkinsci.plugins.matrixauth.PermissionEntry.user(user))) {
            return;
        }
        AuthorizationMatrixProperty replacement = new AuthorizationMatrixProperty(copy, prop.getInheritanceStrategy());
        job.removeProperty(AuthorizationMatrixProperty.class);
        job.addProperty(replacement);
    }

    // ------------------------------------------------------------------ role-strategy

    /**
     * Adds an item role {@code bc-<id>} (pattern = exact item name) holding {@code p} and assigns it
     * to {@code user}. role-strategy's mutators (getRoleMap, addRole, assignRole) are restricted or
     * private, so the only public route is to rebuild the whole strategy and install the copy.
     */
    public static synchronized void grantRole(String id, String user, String itemFullName, Permission p,
                                              Instant expires) throws IOException {
        append(new Rec(id, "ROLE", itemFullName, user, p.getId(), expires.toEpochMilli(), false));
        RoleBasedAuthorizationStrategy cur = (RoleBasedAuthorizationStrategy) Jenkins.get().getAuthorizationStrategy();
        Map<String, RoleMap> maps = copyMaps(cur);
        SortedMap<Role, Set<PermissionEntry>> items = new TreeMap<>(cur.getGrantedRolesEntries(RoleType.Project));
        items.put(new Role("bc-" + id, Pattern.compile(Pattern.quote(itemFullName)), Set.of(p), "Batch Control grant " + id),
                new HashSet<>(Set.of(PermissionEntry.user(user))));
        maps.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        install(cur, maps);
    }

    private static void removeRole(String id) throws IOException {
        RoleBasedAuthorizationStrategy cur = (RoleBasedAuthorizationStrategy) Jenkins.get().getAuthorizationStrategy();
        Map<String, RoleMap> maps = copyMaps(cur);
        SortedMap<Role, Set<PermissionEntry>> items = new TreeMap<>(cur.getGrantedRolesEntries(RoleType.Project));
        items.keySet().removeIf(r -> r.getName().equals("bc-" + id));
        maps.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        install(cur, maps);
    }

    private static Map<String, RoleMap> copyMaps(RoleBasedAuthorizationStrategy s) {
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Global))));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Slave))));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(new TreeMap<>(s.getGrantedRolesEntries(RoleType.Project))));
        return m;
    }

    private static void install(RoleBasedAuthorizationStrategy cur, Map<String, RoleMap> maps) throws IOException {
        Jenkins.get().setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(maps, cur.getPermissionTemplates()));
        Jenkins.get().save();
    }

    // ------------------------------------------------------------------ expiry

    /** Removes every expired entry the ledger knows about, then drops the ledger line. Idempotent. */
    public static synchronized int sweep() throws IOException {
        long now = clock.millis();
        List<Rec> keep = new ArrayList<>();
        int removed = 0;
        for (Rec r : read()) {
            if (r.expires() > now) {
                keep.add(r);
                continue;
            }
            if (r.kind().equals("MATRIX")) {
                Job<?, ?> job = Jenkins.get().getItemByFullName(r.target(), Job.class);
                if (job != null && !r.preExisting()) {
                    removeMatrixEntry(job, r.sid(), Permission.fromId(r.perm()));
                }
            } else {
                removeRole(r.id());
            }
            removed++;
        }
        write(keep);
        return removed;
    }

    /**
     * Boot sweep: runs after jobs are loaded and before the instance completes initialization,
     * i.e. before any build can be scheduled or HTTP request served.
     */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED, before = InitMilestone.COMPLETED)
    public static void sweepAtBoot() throws IOException {
        sweep();
    }

    // ------------------------------------------------------------------ ledger

    static File file() {
        return new File(Jenkins.get().getRootDir(), "poc5-native-grants.txt");
    }

    public static synchronized List<Rec> read() throws IOException {
        File f = file();
        List<Rec> out = new ArrayList<>();
        if (f.exists()) {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (!l.isBlank()) {
                    out.add(Rec.parse(l));
                }
            }
        }
        return out;
    }

    private static void append(Rec r) throws IOException {
        List<Rec> all = read();
        all.add(r);
        write(all);
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "OS_OPEN_STREAM", justification = "commit() closes; abort() in finally")
    private static void write(List<Rec> recs) throws IOException {
        AtomicFileWriter w = new AtomicFileWriter(file().toPath(), StandardCharsets.UTF_8);
        try {
            for (Rec r : recs) {
                w.write(r.line());
                w.write('\n');
            }
            w.commit();
        } finally {
            w.abort();
        }
    }
}
