package io.jenkins.plugins.batchcontrol.security;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Job;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.Messages;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * matrix-auth's project-based matrix with Batch Control grants layered on top (D-35a,
 * ARCHITECTURE section 4). Being a {@link ProjectMatrixAuthorizationStrategy}, it passes every
 * {@code instanceof} check matrix-auth makes, so job, folder and agent authorization properties
 * stay configurable and effective, and matrix-auth's own logic runs underneath the grant layer.
 *
 * <p>Every {@code getACL} overload the parent overrides is wrapped in a {@link GrantAwareACL}. The
 * root and agent ACLs carry no grant scope (S-13: grants never apply there), so for them the
 * wrapper passes every decision through. With no active grant, or while change control is off,
 * the strategy behaves exactly like its parent.
 *
 * <p>Persistence: matrix-auth's XStream converter matches its exact class and is restricted, so
 * {@link ConverterImpl} writes the same {@code <permission>TYPE:id:sid</permission>} lines through
 * public API. JCasC support is {@link BatchControlMatrixCascConfigurator}.
 *
 * <p>matrix-auth is an optional dependency; the descriptor is an optional extension, so without
 * matrix-auth this class is simply never loaded.
 */
public class BatchControlMatrixAuthorizationStrategy extends ProjectMatrixAuthorizationStrategy
        implements GrantLayeredStrategy {

    private static final Logger LOGGER =
            Logger.getLogger(BatchControlMatrixAuthorizationStrategy.class.getName());

    public BatchControlMatrixAuthorizationStrategy() {
        super();
    }

    @NonNull
    @Override
    public ACL getRootACL() {
        // S-13: the root carries no grant scope; root-level Item/Create is the matrix's alone.
        return new GrantAwareACL(super.getRootACL(), (String) null);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Job<?, ?> project) {
        return new GrantAwareACL(super.getACL(project), project);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull AbstractItem item) {
        // Folders are AbstractItems: Item/Create inside a folder is checked on this ACL.
        return new GrantAwareACL(super.getACL(item), item);
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull Node node) {
        return new GrantAwareACL(super.getACL(node), (String) null);
    }

    @NonNull
    @Override
    public AuthorizationStrategy toPlainStrategy() {
        ProjectMatrixAuthorizationStrategy plain = new ProjectMatrixAuthorizationStrategy();
        copyEntries(this, plain);
        return plain;
    }

    /** Adds every entry of {@code from} to {@code to}. */
    static void copyEntries(GlobalMatrixAuthorizationStrategy from, GlobalMatrixAuthorizationStrategy to) {
        from.getGrantedPermissionEntries().forEach((p, entries) -> entries.forEach(e -> to.add(p, e)));
    }

    /**
     * The Batch Control strategy with every global entry of a matrix-auth strategy (migration and
     * the withdrawn wrapper's load conversion). Per-item properties live on the items and need
     * nothing. Declared with a core parameter type so {@link MatrixStrategies} can call it without
     * loading matrix-auth classes first.
     */
    @NonNull
    static BatchControlMatrixAuthorizationStrategy copyOf(@NonNull AuthorizationStrategy existing) {
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        copyEntries((GlobalMatrixAuthorizationStrategy) existing, strategy);
        return strategy;
    }

    /**
     * Listed on the security page. {@link #create()} is the hook matrix-auth's
     * {@code newInstance} uses, so a save of the security form keeps this class.
     */
    @Extension(optional = true)
    @Symbol("batchControlProjectMatrix")
    @Restricted(NoExternalUse.class)
    public static final class DescriptorImpl extends GlobalMatrixAuthorizationStrategy.DescriptorImpl {

        @Override
        protected GlobalMatrixAuthorizationStrategy create() {
            return new BatchControlMatrixAuthorizationStrategy();
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.BatchControlMatrixAuthorizationStrategy_DisplayName();
        }
    }

    /**
     * Found by XStream through the {@code $ConverterImpl} naming convention. Same on-disk format as
     * matrix-auth: {@code <permission>USER:hudson.model.Item.Read:alice</permission>}, sorted by
     * permission id. Lines without a type prefix (matrix-auth before 3.0) load as {@code EITHER},
     * as matrix-auth itself loads them.
     */
    @Restricted(NoExternalUse.class)
    public static final class ConverterImpl implements Converter {

        @Override
        public boolean canConvert(Class type) {
            return type == BatchControlMatrixAuthorizationStrategy.class;
        }

        @Override
        public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            Map<Permission, Set<PermissionEntry>> sorted = new TreeMap<>(Permission.ID_COMPARATOR);
            sorted.putAll(((BatchControlMatrixAuthorizationStrategy) source).getGrantedPermissionEntries());
            for (Map.Entry<Permission, Set<PermissionEntry>> e : sorted.entrySet()) {
                TreeMap<String, PermissionEntry> entries = new TreeMap<>();
                for (PermissionEntry entry : e.getValue()) {
                    entries.put(entry.getType().toPrefix() + entry.getSid(), entry);
                }
                for (PermissionEntry entry : entries.values()) {
                    writer.startNode("permission");
                    writer.setValue(entry.getType().toPrefix() + e.getKey().getId() + ':' + entry.getSid());
                    writer.endNode();
                }
            }
        }

        @Override
        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
            while (reader.hasMoreChildren()) {
                reader.moveDown();
                if ("permission".equals(reader.getNodeName())) {
                    addLine(strategy, reader.getValue());
                }
                reader.moveUp();
            }
            return strategy;
        }

        private static void addLine(BatchControlMatrixAuthorizationStrategy strategy, String line) {
            if (line == null) {
                return;
            }
            AuthorizationType type = AuthorizationType.EITHER;
            String rest = line;
            int first = line.indexOf(':');
            if (first > 0) {
                String prefix = line.substring(0, first);
                for (AuthorizationType candidate : AuthorizationType.values()) {
                    if (candidate.name().equals(prefix)) {
                        type = candidate;
                        rest = line.substring(first + 1);
                        break;
                    }
                }
            }
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                LOGGER.warning(() -> "Skipping a malformed permission line: " + line);
                return;
            }
            Permission permission = Permission.fromId(rest.substring(0, colon));
            if (permission == null) {
                LOGGER.warning(() -> "Skipping a non-existent permission in: " + line);
                return;
            }
            strategy.add(permission, new PermissionEntry(type, rest.substring(colon + 1)));
        }
    }
}
