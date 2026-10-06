package io.jenkins.plugins.batchcontrol;

import hudson.EnvVars;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.Secret;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Parameter types written for the D-72b rows (matrix note 265). They stand for "any other
 * parameter type" in SPEC item 5, which must work like core's own:
 *
 * <ul>
 *   <li>{@link TokenParameterValue}: a value that is not a {@code PasswordParameterValue} but carries
 *       a {@link Secret} field (spec-review-S7 m-1). It is not flagged {@code isSensitive()}; its
 *       {@code getValue()} is the {@code Secret}. SPEC item 5 and section 6: "any other
 *       {@code Secret} field" is stored only encrypted, every textual form shows {@code ********},
 *       and the approved build receives the original. (The credentials plugin's parameter value holds
 *       a credentials id, not a {@code Secret}, so it would not exercise this clause.)</li>
 *   <li>{@link ProbeParameterValue}: a string value that counts how often Jenkins' XStream
 *       materialises it ({@code readResolve}), so a row can observe whether a screen or the periodic
 *       work loaded a request's typed values (D-72b (5): "listings never load them") without
 *       looking inside the plugin.</li>
 * </ul>
 *
 * <p>Each test class registers the descriptors it needs as {@code @TestExtension} subclasses of
 * {@link TokenDescriptorBase} / {@link ProbeDescriptorBase} (the harness only loads a test extension
 * nested in the running test class).
 *
 * <p>Written from docs/SPEC.md item 5 and section 6, docs/DECISIONS.md D-72 and D-72b only (no
 * src/main knowledge).
 */
final class CustomParameterFixtures {

    private CustomParameterFixtures() {
        // utility class
    }

    // ------------------------------------------------------------------ a Secret-carrying value

    /** Defines a parameter whose values are {@link TokenParameterValue}s. */
    public static final class TokenParameterDefinition extends SimpleParameterDefinition {
        private static final long serialVersionUID = 1L;

        public TokenParameterDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(String value) {
            return new TokenParameterValue(getName(), Secret.fromString(value));
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return createValue(jo.optString("value", ""));
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return new TokenParameterValue(getName(), Secret.fromString(""));
        }
    }

    /** A non-Password value whose only payload is a {@link Secret}; it puts the plaintext into the build environment. */
    public static final class TokenParameterValue extends ParameterValue {
        private static final long serialVersionUID = 1L;

        private final Secret token;

        public TokenParameterValue(String name, Secret token) {
            super(name);
            this.token = token;
        }

        @Override
        public Object getValue() {
            return token;
        }

        @Override
        public void buildEnvironment(Run<?, ?> build, EnvVars env) {
            env.put(name, token.getPlainText());
        }

        @Override
        public String toString() {
            return "(TokenParameterValue) " + name; // never the secret
        }
    }

    /** The descriptor of {@link TokenParameterDefinition}; subclass it as a {@code @TestExtension}. */
    public abstract static class TokenDescriptorBase extends ParameterDefinition.ParameterDescriptor {
        protected TokenDescriptorBase() {
            super(TokenParameterDefinition.class);
        }

        @Override
        public String getDisplayName() {
            return "D-72b token parameter";
        }
    }

    // ------------------------------------------------------------------ a value that reports being loaded

    /** Defines a string parameter whose values are {@link ProbeParameterValue}s. */
    public static final class ProbeParameterDefinition extends StringParameterDefinition {
        private static final long serialVersionUID = 1L;

        public ProbeParameterDefinition(String name) {
            super(name, "probe-default");
        }

        @Override
        public ParameterValue createValue(String value) {
            return new ProbeParameterValue(getName(), value);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return createValue(jo.optString("value", getDefaultValue()));
        }

        @Override
        public StringParameterValue getDefaultParameterValue() {
            return new ProbeParameterValue(getName(), getDefaultValue());
        }
    }

    /** A string value that counts every time XStream reads it back ({@code readResolve}). */
    public static final class ProbeParameterValue extends StringParameterValue {
        private static final long serialVersionUID = 1L;

        static final AtomicInteger LOADS = new AtomicInteger();

        public ProbeParameterValue(String name, String value) {
            super(name, value);
        }

        private Object readResolve() {
            LOADS.incrementAndGet();
            return this;
        }
    }

    /** The descriptor of {@link ProbeParameterDefinition}; subclass it as a {@code @TestExtension}. */
    public abstract static class ProbeDescriptorBase extends ParameterDefinition.ParameterDescriptor {
        protected ProbeDescriptorBase() {
            super(ProbeParameterDefinition.class);
        }

        @Override
        public String getDisplayName() {
            return "D-72b probe parameter";
        }
    }
}
