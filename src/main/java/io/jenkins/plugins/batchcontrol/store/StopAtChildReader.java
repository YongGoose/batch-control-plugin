package io.jenkins.plugins.batchcontrol.store;

import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.ReaderWrapper;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72b (5), security-35 S-35-02: reads an XStream document up to, and not into, the first child
 * of the root element named {@code stopAt}. The root's converter sees no more children from that
 * element on, so the element's content is neither parsed nor deserialized and the rest of the file
 * is never read. Used for the listing read of a run request, whose typed values are its last
 * element.
 *
 * <p>Deciding whether the next child is {@code stopAt} needs its start tag: {@link #hasMoreChildren()}
 * at the root moves into the next child and remembers that it did, and the following
 * {@link #moveDown()} then only records the step.
 */
@Restricted(NoExternalUse.class)
final class StopAtChildReader extends ReaderWrapper {

    private final String stopAt;
    /** 0 while positioned on the root element, 1 inside one of its children, and so on. */
    private int depth;
    /** At the root: the next child's start tag was read by {@link #hasMoreChildren()}. */
    private boolean entered;
    private boolean stopped;

    StopAtChildReader(HierarchicalStreamReader reader, String stopAt) {
        super(reader);
        this.stopAt = stopAt;
    }

    /** Whether the document has a root child named {@code stopAt} (reading stopped there). */
    boolean stopped() {
        return stopped;
    }

    @Override
    public boolean hasMoreChildren() {
        if (depth > 0) {
            return super.hasMoreChildren();
        }
        if (stopped) {
            return false;
        }
        if (entered) {
            return true;
        }
        if (!super.hasMoreChildren()) {
            return false;
        }
        super.moveDown();
        if (stopAt.equals(super.getNodeName())) {
            stopped = true;
            return false;
        }
        entered = true;
        return true;
    }

    @Override
    public void moveDown() {
        if (depth == 0) {
            if (stopped) {
                throw new IllegalStateException("No more children before <" + stopAt + ">");
            }
            if (entered) {
                entered = false;
                depth = 1;
                return;
            }
        }
        super.moveDown();
        depth++;
    }

    @Override
    public void moveUp() {
        super.moveUp();
        depth--;
    }

    @Override
    public String peekNextChild() {
        if (depth == 0) {
            if (stopped) {
                return null;
            }
            if (entered) {
                return super.getNodeName();
            }
        }
        return super.peekNextChild();
    }
}
