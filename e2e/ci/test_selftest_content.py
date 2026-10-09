"""The crawl's content checks on the synthetic pages of ci/selftest_content.py, as pytest-playwright tests (no Jenkins).

    python -m pytest e2e/ci/test_selftest_content.py --browser chromium        (or --browser-channel chrome)

pytest-playwright gives each test a fresh `page` (its --tracing retain-on-failure / --screenshot options apply)."""
import importlib.util
from pathlib import Path

import pytest

_spec = importlib.util.spec_from_file_location("bc_selftest", Path(__file__).resolve().parent / "selftest_content.py")
selftest = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(selftest)


@pytest.mark.parametrize("name", list(selftest.PAGES))
def test_content_checks(page, name):
    good, lines = selftest.check_page(page, name)
    print("\n".join(lines))
    assert good, "\n".join(lines)
