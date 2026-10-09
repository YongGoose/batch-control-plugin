"""One pytest test per step of a CI e2e shard (see conftest.py). The test runs the step's driver through
ci/shard.py's Runner and fails with the runner's verdict reasons and the driver's failure lines."""
import pytest


def test_step(bc_runner, bc_step, record_property):
    idx, name, spec = bc_step
    res = bc_runner.run_step(idx, name, spec)
    record_property("seconds", res["seconds"])
    record_property("log", "logs/" + res["log"])
    record_property("driver", " ".join(spec["argv"]))
    if res.get("traces"):
        record_property("traces", " ".join(res["traces"]))
    if res["verdict"] == "BLOCKED":
        pytest.skip("BLOCKED: " + "; ".join(res["reasons"]))
    if res["verdict"] != "PASS":
        lines = "\n".join(str(x)[:400] for x in res.get("failure_lines", [])[:30])
        pytest.fail(f"{name}: {'; '.join(res['reasons'])} (log: logs/{res['log']})\n{lines}", pytrace=False)
