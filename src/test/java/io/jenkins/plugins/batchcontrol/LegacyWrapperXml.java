package io.jenkins.plugins.batchcontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewrites the saved {@code <authorizationStrategy>} element of config.xml into the withdrawn
 * wrapper's shape around it (the same fixture StrategyUpgradeTest uses). Kept in its own class
 * with core and JDK types only, so the S-03 runs without matrix-auth or role-strategy can load it.
 */
final class LegacyWrapperXml {

    static final String LEGACY = "io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy";
    private static final Pattern STRATEGY_ELEMENT = Pattern.compile(
            "<authorizationStrategy class=\"([^\"]+)\"([^>]*?)(?:/>|>(.*?)</authorizationStrategy>)", Pattern.DOTALL);

    private LegacyWrapperXml() {
    }

    static void write(Jenkins jenkins) throws Exception {
        Path cfg = jenkins.getRootDir().toPath().resolve("config.xml");
        String xml = Files.readString(cfg, StandardCharsets.UTF_8);
        Matcher m = STRATEGY_ELEMENT.matcher(xml);
        assertTrue(m.find(), "fixture: config.xml must carry an <authorizationStrategy> element");
        if (!LEGACY.equals(m.group(1))) {
            String body = m.group(3) == null ? "" : m.group(3);
            xml = xml.substring(0, m.start()) + "<authorizationStrategy class=\"" + LEGACY + "\"><delegate class=\""
                    + m.group(1) + "\"" + m.group(2) + ">" + body + "</delegate></authorizationStrategy>" + xml.substring(m.end());
            Files.writeString(cfg, xml, StandardCharsets.UTF_8);
        }
        assertTrue(Files.readString(cfg, StandardCharsets.UTF_8).contains(LEGACY), "fixture: config.xml must hold the legacy wrapper");
    }
}
