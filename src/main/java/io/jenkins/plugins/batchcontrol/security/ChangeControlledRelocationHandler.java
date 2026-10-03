package io.jenkins.plugins.batchcontrol.security;

import com.cloudbees.hudson.plugins.folder.relocate.RelocationHandler;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;

/**
 * D-59: puts {@link MoveGuard} in front of every move the folders plugin performs. The plugin's
 * {@code move/move} hands the move to the chain of {@link RelocationHandler}s that do not answer
 * {@link HandlingMode#SKIP}, highest ordinal first, and the standard handler at the end of that
 * chain is what calls {@code Items.move}. This handler is first in the chain while change control
 * is on and passes the move on only when the guard allows it; it never offers a destination or
 * makes the Move action available ({@link HandlingMode#DELEGATE}). It works whatever authorization
 * strategy is installed. With change control off it is skipped and moves behave as in Jenkins.
 */
@Extension(ordinal = 10_000)
@Restricted(NoExternalUse.class)
public final class ChangeControlledRelocationHandler extends RelocationHandler {

    @NonNull
    @Override
    public HandlingMode applicability(@NonNull Item item) {
        return BatchControlGlobalConfiguration.get().isChangeControlEnabled()
                ? HandlingMode.DELEGATE : HandlingMode.SKIP;
    }

    @CheckForNull
    @Override
    public HttpResponse handle(@NonNull Item item, @NonNull ItemGroup<?> destination, @NonNull AtomicReference<Item> newItem,
                               @NonNull List<? extends RelocationHandler> chain)
            throws IOException, InterruptedException {
        // The folders plugin checked Item/Move on the item and offered the destination before
        // calling the chain; the guard adds D-59's Delete-here, Create-there rule as the same user.
        MoveRefusal refusal = MoveGuard.check(item, destination);
        if (refusal != null) {
            return refusal;
        }
        return chain.isEmpty() ? null : chain.get(0).handle(item, destination, newItem, chain.subList(1, chain.size()));
    }

    @NonNull
    @Override
    public List<? extends ItemGroup<?>> validDestinations(@NonNull Item item) {
        return Collections.emptyList();
    }
}
