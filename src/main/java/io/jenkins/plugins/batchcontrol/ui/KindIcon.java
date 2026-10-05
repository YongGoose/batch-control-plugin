package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jenkins.ui.symbol.Symbol;
import org.jenkins.ui.symbol.SymbolRequest;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-71 (spec-review-S6 M-1): an item kind's icon as markup, for answers that are built in Java
 * rather than in Jelly (the request form's item check, {@code FormValidation.okWithMarkup}). The
 * lists and the detail page render the same icon with {@code l:icon} ({@code tags/scopeItem.jelly}).
 *
 * <p>Core's {@code l:icon} turns a {@code symbol-<name> [plugin-<id>]} icon class into an inline
 * SVG through {@link Symbol#get(SymbolRequest)}; this does the same through the same public API
 * (the {@code IconSet} and {@code Functions} shortcuts that {@code l:icon} uses are
 * {@code NoExternalUse}). The SVG comes from core's or the plugin's own symbol resources; Symbol
 * empties its {@code <title>} and marks it {@code aria-hidden}, so the icon adds no text and the
 * kind's display name next to it stays the accessible name.
 *
 * <p>The icon class is a descriptor's {@code getIconClassName()}, never user input. It is still
 * checked against a strict pattern before it reaches the resource lookup, and anything else (a
 * legacy {@code icon-*} class, a bitmap path) yields no icon: the icon is decoration, the display
 * name carries the meaning.
 */
@Restricted(NoExternalUse.class)
public final class KindIcon {

    private static final Logger LOGGER = Logger.getLogger(KindIcon.class.getName());

    /** {@code symbol-<name>}, optionally followed by other class tokens such as {@code plugin-<id>}. */
    private static final Pattern SYMBOL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private static final Pattern INTER_TAG_WHITESPACE = Pattern.compile(">\\s+<");

    private static final String SYMBOL_PREFIX = "symbol-";

    private static final String PLUGIN_PREFIX = "plugin-";

    private KindIcon() {
    }

    /**
     * The inline SVG of {@code iconClassName} with the CSS classes {@code classes}, or an empty
     * string when the class is not a symbol or the symbol cannot be rendered.
     *
     * @param iconClassName a descriptor's icon class, for example {@code symbol-freestyle-project}
     *                      or {@code symbol-folder-outline plugin-ionicons-api}
     * @param classes       CSS classes for the {@code svg} element (a constant of the caller, such
     *                      as {@code icon-sm})
     */
    public static String svg(@CheckForNull String iconClassName, String classes) {
        if (iconClassName == null || iconClassName.isBlank()) {
            return "";
        }
        String name = null;
        String plugin = null;
        for (String token : iconClassName.trim().split("\\s+")) {
            if (name == null && token.startsWith(SYMBOL_PREFIX)) {
                name = token.substring(SYMBOL_PREFIX.length());
            } else if (plugin == null && token.startsWith(PLUGIN_PREFIX)) {
                plugin = token.substring(PLUGIN_PREFIX.length());
            }
        }
        if (name == null || !SYMBOL_NAME.matcher(name).matches()
                || (plugin != null && !PLUGIN_NAME.matcher(plugin).matches())) {
            return "";
        }
        try {
            SymbolRequest.Builder request = new SymbolRequest.Builder().withName(name).withClasses(classes);
            if (plugin != null) {
                request = request.withPluginName(plugin);
            }
            String markup = Symbol.get(request.build());
            // The symbol files are indented; whitespace between SVG elements renders nothing, and
            // without it the icon adds no text at all to the element it is placed in.
            return markup == null ? "" : INTER_TAG_WHITESPACE.matcher(markup.trim()).replaceAll("><");
        } catch (RuntimeException e) {
            // Decoration only: the answer is still complete without the icon.
            LOGGER.log(Level.FINE, "No symbol for " + iconClassName, e);
            return "";
        }
    }
}
