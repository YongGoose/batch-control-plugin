"""CI arrangement (script console, admin; arrangement only), run by ci/shard.py.

    arrange_ci.py            after the e2e-14 arrangement: pendingTimeoutHours 1 -> 24. A shard runs up to an hour
                             after the seed, and JCasC's 1 hour would expire the seeded pending requests mid-run
                             (e2e-14 raised it by hand for the same reason, docs/reports/e2e-14.md Environment).
                             misc.py C saves the value and restores it. approvedRunTimeoutMinutes stays 1 (seed.py).
    arrange_ci.py monitors   after the admin crawl: re-enables the Batch Control administrative monitors that the
                             crawl dismissed. The crawl clicks every control it may, including a monitor's Dismiss
                             (e2e-12 Known 5), and Dismiss disables a monitor for good; misc.py S then reverts the
                             strategy, finds no "Install the Batch Control variant" and leaves the shard without a Batch
                             Control strategy, so every later grant check in that shard fails for that reason alone."""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "r14"))
from lib import groovy  # noqa: E402

if sys.argv[1:] == ["monitors"]:
    print(groovy("""def out = []
jenkins.model.Jenkins.get().administrativeMonitors.findAll { it.class.name.startsWith('io.jenkins.plugins.batchcontrol.') }.each { m ->
  if (!m.isEnabled()) { m.disable(false); out << m.id + ' re-enabled' } else { out << m.id + ' enabled' } }
return out"""))
else:
    print(groovy("""def c = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()
c.setPendingTimeoutHours(24); c.save()
return "pendingTimeoutHours=${c.pendingTimeoutHours} approvedRunTimeoutMinutes=${c.approvedRunTimeoutMinutes}" """))
