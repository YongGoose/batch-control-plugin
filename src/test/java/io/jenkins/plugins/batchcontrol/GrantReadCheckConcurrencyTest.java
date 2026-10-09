package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bug hunt B, R3-04 (matrix row T-08-196, note 299): an Item/Read or Item/Discover permission check
 * never blocks on the GrantService monitor, and its answers are unchanged.
 *
 * <p>Basis: SPEC 6 non-functional (the Jenkins pages stay usable at scale; the job list runs one
 * Item/Read check per item), SPEC 2 / D-35c (a CREATE window's holder configures, and reads, the item
 * created through it) and the frozen bug-hunt contract: with another thread holding
 * {@code synchronized (GrantService.get())} for 3 s,
 * {@code job.getACL().hasPermission2(viewerAuth, Item.READ)} returns within 1 s with the correct
 * answer, and a D-35c creating grant still confers Read on its item. Reproduced on a real Jenkins:
 * with 20,000 ended grants, 20 parallel job-list requests took a median 527 ms against 84 ms with
 * change control off, the request threads BLOCKED on GrantService. GrantService is a static
 * singleton ({@link GrantService#get()}) whose synchronized methods lock that instance (coordinator
 * correction: it is not an extension).
 *
 * <p>Deterministic: the holder thread takes the monitor and signals a latch; the checks run on a
 * second thread only after that signal and must answer within 1 s, while the holder keeps the monitor
 * until it is released (at most 3 s). No timing is inferred from sleeps.
 *
 * <p>Batch Control role strategy (D-35a), change control on, approver a1. {@code maker} holds
 * Overall/Read and RequestGrant globally and Item/Read only through the item role {@code team}, whose
 * pattern matches the folder {@code team} and nothing inside it (requesting a window needs to read its
 * item), and a CREATE window on that folder, through which it creates {@code team/made};
 * {@code viewer} holds Item/Read globally; {@code nobody} holds Overall/Read only.
 *
 * <p>Written from docs/SPEC.md items 2, 6 and 8, DECISIONS D-35a and D-35c and the bug-hunt B
 * contract only (no src/main knowledge).
 */
@WithJenkins
public class GrantReadCheckConcurrencyTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be installed");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-08-196 (R3-04): maker's CREATE window on {@code team}; maker creates {@code team/made} through
     * it. With another thread holding the GrantService monitor (for up to 3 s), the checks of
     * {@code team/made}'s ACL (maker Item/Read and Item/Discover, nobody Item/Read, viewer Item/Read)
     * answer within 1 s, and the answers are the ones given without contention: true, true, false,
     * true. Guards first, without contention: maker holds no Item/Read natively inside {@code team}
     * (an administrator's {@code team/other}), but holds it on {@code team/made} through the D-35c
     * creating grant; nobody does not; viewer does.
     */
    @Test
    public void t_08_196_readCheckDoesNotBlockOnGrantServiceMonitor() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        FreeStyleProject other = team.createProject(FreeStyleProject.class, "other"); // created by the administrator (SYSTEM)
        assertFalse(team.getACL().hasPermission2(token("maker"), Item.CREATE), "premise: maker cannot create before the window");
        assertFalse(other.getACL().hasPermission2(token("maker"), Item.READ),
                "premise: maker holds no Item/Read natively inside team (the item role matches the folder only)");
        StrategyFixtures.grant("maker", "team", List.of(GrantAction.CREATE));
        assertTrue(team.getACL().hasPermission2(token("maker"), Item.CREATE), "premise: the CREATE window confers Item/Create on team");
        FreeStyleProject made = StrategyFixtures.as("maker", () -> team.createProject(FreeStyleProject.class, "made"));

        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("maker Item/Read", true);
        expected.put("maker Item/Discover", true);
        expected.put("nobody Item/Read", false);
        expected.put("viewer Item/Read", true);
        assertEquals(expected, answers(made), "guard: without contention the D-35c creating grant confers Read on team/made"
                + " to maker, nobody reads nothing, viewer reads natively");

        GrantService service = GrantService.get();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (service) {
                held.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS); // keeps the monitor: a latch wait does not release it
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "GrantService-monitor-holder");
        holder.setDaemon(true);
        ExecutorService checker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Item-Read-checker");
            t.setDaemon(true);
            return t;
        });
        try {
            holder.start();
            assertTrue(held.await(10, TimeUnit.SECONDS), "fixture: the holder thread must take the GrantService monitor");
            Future<Map<String, Boolean>> pending = checker.submit(() -> answers(made));
            Map<String, Boolean> contended;
            try {
                contended = pending.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException blocked) {
                fail("an Item/Read (or Discover) check of team/made did not answer within 1 s while another thread held the"
                        + " GrantService monitor: a permission check must not block on GrantService");
                return;
            }
            assertTrue(holder.isAlive(), "premise: the monitor was still held when the checks answered");
            assertEquals(expected, contended, "the checks under contention must give the same answers as without it");
        } finally {
            release.countDown();
            holder.join(10_000);
            checker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The four checks on {@code item}'s ACL, in a fixed order. */
    private static Map<String, Boolean> answers(Item item) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("maker Item/Read", has(item, token("maker"), Item.READ));
        out.put("maker Item/Discover", has(item, token("maker"), Item.DISCOVER));
        out.put("nobody Item/Read", has(item, token("nobody"), Item.READ));
        out.put("viewer Item/Read", has(item, token("viewer"), Item.READ));
        return out;
    }

    private static boolean has(Item item, Authentication auth, Permission permission) {
        return item.getACL().hasPermission2(auth, permission);
    }

    private static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("base", Pattern.compile(".*"), Set.of(Jenkins.READ), ""), set(PermissionEntry.user("maker"),
                PermissionEntry.user("a1"), PermissionEntry.user("viewer"), PermissionEntry.user("nobody")));
        global.put(new Role("requester", Pattern.compile(".*"), Set.of(BatchControlPermissions.REQUEST_GRANT), ""),
                set(PermissionEntry.user("maker")));
        global.put(new Role("approver", Pattern.compile(".*"), Set.of(Item.READ, BatchControlPermissions.APPROVE), ""),
                set(PermissionEntry.user("a1")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Item.READ), ""), set(PermissionEntry.user("viewer")));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("team", Pattern.compile("team"), Set.of(Item.READ), ""), set(PermissionEntry.user("maker")));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        return m;
    }

    @SafeVarargs
    private static <E> Set<E> set(E... e) {
        return new HashSet<>(Arrays.asList(e));
    }
}
