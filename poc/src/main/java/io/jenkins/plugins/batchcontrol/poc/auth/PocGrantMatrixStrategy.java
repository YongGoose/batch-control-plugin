package io.jenkins.plugins.batchcontrol.poc.auth;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.casc.Attribute;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.Configurator;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.model.CNode;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.Symbol;

/**
 * PoC-5 option A (matrix-auth): a subclass of ProjectMatrixAuthorizationStrategy that layers
 * active grants on top of the native item ACL.
 *
 * <p>Uses only non-restricted API. matrix-auth's own XStream converter and JCasC configurator
 * are {@code @Restricted(NoExternalUse)} and match the exact class, so the subclass carries:
 * a converter that writes the same {@code <permission>TYPE:id:sid</permission>} lines through
 * public getters, and a JCasC configurator that looks matrix-auth's configurator up by type at
 * runtime and converts to/from a plain ProjectMatrixAuthorizationStrategy.
 */
public class PocGrantMatrixStrategy extends ProjectMatrixAuthorizationStrategy {

    @NonNull
    @Override
    public ACL getACL(@NonNull Job<?, ?> project) {
        return new GrantLayerACL(super.getACL(project), project.getFullName());
    }

    @NonNull
    @Override
    public ACL getACL(@NonNull AbstractItem item) {
        return new GrantLayerACL(super.getACL(item), item.getFullName());
    }

    static void copy(GlobalMatrixAuthorizationStrategy from, GlobalMatrixAuthorizationStrategy to) {
        from.getGrantedPermissionEntries().forEach((p, entries) -> entries.forEach(e -> to.add(p, e)));
    }

    /** Migration helper: the same matrix, with the grant layer. */
    public static PocGrantMatrixStrategy from(GlobalMatrixAuthorizationStrategy existing) {
        PocGrantMatrixStrategy s = new PocGrantMatrixStrategy();
        copy(existing, s);
        return s;
    }

    @Extension
    @Symbol("batchControlProjectMatrix")
    public static final class DescriptorImpl extends GlobalMatrixAuthorizationStrategy.DescriptorImpl {
        @Override
        protected GlobalMatrixAuthorizationStrategy create() {
            return new PocGrantMatrixStrategy();
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "PoC-5 project matrix with Batch Control grants";
        }
    }

    /** Found by XStream2 through the {@code $ConverterImpl} naming convention. Same on-disk format as matrix-auth. */
    public static final class ConverterImpl implements Converter {
        @Override
        public boolean canConvert(Class type) {
            return type == PocGrantMatrixStrategy.class;
        }

        @Override
        public void marshal(Object source, HierarchicalStreamWriter w, MarshallingContext ctx) {
            Map<Permission, Set<PermissionEntry>> sorted = new TreeMap<>(Permission.ID_COMPARATOR);
            sorted.putAll(((PocGrantMatrixStrategy) source).getGrantedPermissionEntries());
            sorted.forEach((p, entries) -> entries.forEach(e -> {
                w.startNode("permission");
                w.setValue(e.getType().toPrefix() + p.getId() + ':' + e.getSid());
                w.endNode();
            }));
        }

        @Override
        public Object unmarshal(HierarchicalStreamReader r, UnmarshallingContext ctx) {
            PocGrantMatrixStrategy s = new PocGrantMatrixStrategy();
            while (r.hasMoreChildren()) {
                r.moveDown();
                String v = r.getValue();
                int a = v.indexOf(':');
                int b = v.indexOf(':', a + 1);
                Permission p = Permission.fromId(v.substring(a + 1, b));
                if (p != null) {
                    s.add(p, new PermissionEntry(AuthorizationType.valueOf(v.substring(0, a)), v.substring(b + 1)));
                }
                r.moveUp();
            }
            return s;
        }
    }

    /** JCasC: reuse matrix-auth's schema under the {@code batchControlProjectMatrix} symbol. */
    @Extension(optional = true)
    public static final class CascConfigurator implements Configurator<PocGrantMatrixStrategy> {
        @NonNull @Override public String getName() { return "batchControlProjectMatrix"; }
        @Override public Class<PocGrantMatrixStrategy> getTarget() { return PocGrantMatrixStrategy.class; }
        @Override public Class getImplementedAPI() { return AuthorizationStrategy.class; }
        @NonNull @Override public Set<Attribute<PocGrantMatrixStrategy, ?>> describe() { return Collections.emptySet(); }

        private static Configurator<ProjectMatrixAuthorizationStrategy> base(ConfigurationContext c) throws ConfiguratorException {
            return c.lookupOrFail(ProjectMatrixAuthorizationStrategy.class);
        }

        @NonNull
        @Override
        public PocGrantMatrixStrategy configure(CNode node, ConfigurationContext c) throws ConfiguratorException {
            return from(base(c).configure(node, c));
        }

        @Override
        public PocGrantMatrixStrategy check(CNode node, ConfigurationContext c) throws ConfiguratorException {
            return from(base(c).check(node, c));
        }

        @Override
        public CNode describe(PocGrantMatrixStrategy instance, ConfigurationContext c) throws Exception {
            ProjectMatrixAuthorizationStrategy plain = new ProjectMatrixAuthorizationStrategy();
            copy(instance, plain);
            return base(c).describe(plain, c);
        }
    }
}
