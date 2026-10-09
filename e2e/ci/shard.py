#!/usr/bin/env python3
"""Shard plan and step runner for the CI e2e pass (called by ci/run.sh; usable on its own against a running stack).

    shard.py units                      list the units, their weights, groups and steps
    shard.py check                      check GROUPS (every unit in exactly one group, ...) and print the groups
    shard.py matrix                     the GitHub Actions matrix of the groups as JSON (after the same check)
    shard.py label <k>/<N>              the label of group k
    shard.py plan <k>/<N> [--json]      the units and steps shard k of N (= group k) runs
    shard.py plan-all [N]               every group with its estimated duration
    shard.py run <k>/<N> --out DIR      run shard k of N against $BC_BASE, write DIR/summary.{json,md}

A *unit* is a group of existing driver invocations that must run in order on one Jenkins (for example the
crawl of one role on both job UIs). Every shard starts from a fresh JENKINS_HOME and first runs the SETUP steps
(the e2e-14 arrangement and seed), then its units in the canonical e2e-14 order; a unit marked `last` replaces the
authorization strategy and therefore runs last in its shard. The units are split into the fixed, named GROUPS below,
one CI job each, named after the group's label; shard k of N is group k, and N must be the number of groups.
BC_UNITS=a,b runs just those units instead of group k's (debugging).

A step fails when the driver exits non-zero, or prints one of the drivers' own failure markers: a line starting
with "FAIL " (round3.py, def07.py, the crawl's content checks), `"ok": false` (r15/check.py) or `"EXCEPTION":`
(misc.py catches a section's exception and logs it). The drivers' assertions are not changed here. The observational
drivers (crawl, actions, jobui) log rows for human review; their notable rows are counted in the summary.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

E2E = Path(__file__).resolve().parent.parent
ROLES6 = ["admin", "requester", "reqonly", "approver-1", "manager", "nobc"]
DEF07_ROLES = ["approver-1", "admin", "manager"]
ROLE_CRAWL = ["requester", "approver-1", "admin"]


def py(*args):
    return {"argv": list(args)}


def flag(value, *users):
    return py("r14/set_flag.py", value, *users)


# The e2e-14 arrangement (docs/reports/e2e-14.md, Environment) plus the CI-only pieces in ci/.
SETUP = [
    ("arrange-r7", py("r7/arrange.py")),
    ("arrange-r8", py("r8/arrange.py")),
    ("arrange-side", py("r8/arrange_side.py")),
    ("arrange-r12", py("r12/arrange.py")),
    ("arrange-fast", py("r14/arrange_fast.py")),
    ("arrange-ci", py("ci/arrange_ci.py")),
    ("seed-fast", py("r14/seed_fast.py")),
    ("seed", py("r14/seed.py")),
    ("seed-paging", py("r14/seed_paging.py")),
    ("seed-markup", py("ci/seed_markup.py")),
    ("preconditions", py("ci/preconditions.py")),  # e2e-16: the seeded fixtures the drivers assume, asserted
]
SETUP_WEIGHT = 3.4
# What a CI job spends outside the steps (checkout, the Python packages and Chromium, the fonts, starting Jenkins, the
# coverage dump and upload): job minus driver minutes in run 37883661045 were 3.1-3.4, of which Jenkins' start 2.4-2.6.
JOB_OVERHEAD = 3.2


class Unit:
    def __init__(self, name, weight, steps, last=False, doc=""):
        self.name, self.weight, self.steps, self.last, self.doc = name, weight, steps, last, doc


def crawl_unit(role, weight):
    steps = [
        (f"flag-new-{role}", flag("true", role)),
        (f"crawl-{role}-new", py("r14/crawl.py", role, "new")),
        (f"flag-classic-{role}", flag("false", role)),
        (f"crawl-{role}-classic", py("r14/crawl.py", role, "classic")),
        (f"flag-reset-{role}", flag("true", role)),
    ]
    if role == "admin":  # the admin crawl dismisses the Batch Control monitors on /manage/ (see ci/arrange_ci.py)
        steps.append(("restore-monitors", py("ci/arrange_ci.py", "monitors")))
    return Unit(f"crawl-{role}", weight, steps,
                doc=f"e2e-12/14 crawl (run_crawl.sh) for {role}, new job page then classic, with the content checks")


# jobui.py <ui> [role ...] runs each role on its own; the roles are grouped so that the groups take similar time.
# Weights: the minutes of CI run 37883661045 (see UNITS). The local measurement of 2026-10-05 had reqonly and
# approver-1+manager+nobc as one figure (new UI 15.9, classic 13.6) and split it by a guess that reqonly had most of
# the entries; on CI reqonly logged 38 entries and took 3.0 / 2.7 min, approver-1+manager+nobc 90 entries, 9.3 / 7.3.
JOBUI_GROUPS = {"new": [("requester", 6.9), ("admin", 5.2), ("reqonly", 3.0), ("approver-1 manager nobc", 9.3)],
                "classic": [("requester", 6.2), ("admin", 4.0), ("reqonly", 2.7), ("approver-1 manager nobc", 7.3)]}


def jobui_unit(ui, group, weight):
    roles = group.split()
    name = f"jobui-{ui}-{roles[0] if len(roles) == 1 else 'others'}"
    return Unit(name, weight, [
        (f"flag-{name}", flag("true" if ui == "new" else "false", *roles)),
        (name, py("r14/jobui.py", ui, *roles)),
        (f"flag-reset-{name}", flag("true", *roles)),
    ], doc=f"e2e-12/14 G2c: entry points and dialog cycles, {ui} job UI, {', '.join(roles)}")


# Canonical order = the e2e-14 order. Weights: minutes per unit on the GitHub Actions runner. They were measured on
# local shard runs (2026-10-05..08: MacBook, Docker Desktop, under load from other builds) and calibrated on 2026-10-09
# against CI run 37883661045 (ubuntu-latest; each shard's summary.json, step seconds summed per unit): where the CI
# minutes differed from the weight by more than 0.3 min and more than 10%, the weight is now the CI minutes, rounded to
# 0.1. That changed def07, misc, crawl-admin/-requester/-reqonly/-manager, all eight jobui units, r16-follow/-params/
# -rerun/-d60/-names/-durable, r17-s39, r17-disk, r19-plugins, role and r18-final (the local runs had been off by a
# factor of 0.17 to 3.2, in both directions, not by a common factor). The others, and SETUP_WEIGHT, were within that
# margin and are unchanged. One CI run: take a weight as good to about 10%.
UNITS = [
    Unit("def07", 1.0, [
        ("flag-def07-new", flag("true", *DEF07_ROLES)),
        ("def07-new", py("r14/def07.py", "new")),
        ("flag-def07-classic", flag("false", *DEF07_ROLES)),
        ("def07-classic", py("r14/def07.py", "classic")),
        ("flag-def07-reset", flag("true", *DEF07_ROLES)),
    ], doc="e2e-14 G1: DEF-07 recheck on both job UIs"),
    crawl_unit("admin", 5.3),
    crawl_unit("requester", 4.8),
    crawl_unit("reqonly", 2.6),
    crawl_unit("approver-1", 1.2),
    crawl_unit("manager", 3.9),
    crawl_unit("nobc", 1.2),
    Unit("actions", 2.3, [("actions", py("r14/actions.py", "all"))],
         doc="e2e-12/14 G2b: every state-changing control per role"),
] + [jobui_unit(ui, group, w) for ui in ("new", "classic") for group, w in JOBUI_GROUPS[ui]] + [
    Unit("misc", 1.8, [("misc", py("r14/misc.py", "CHPBTRMKS"))], doc="e2e-12/14 G2d: targeted checks C H P B T R M K S"),
    Unit("targeted", 0.9, [
        ("errpages", py("r14/errpages.py")),
        ("listpager", py("r14/s_listpager.py")),
        ("incident", py("r14/s_incident.py")),
        ("helpcheck", py("r14/helpcheck.py")),
        ("helpcheck2", py("r14/helpcheck2.py")),
        ("monitor", py("r14/s_monitor.py")),
    ], doc="e2e-12/14 G2d: error pages, list pager, incident, help, strategy monitor"),
    Unit("round3", 0.6, [("round3", py("r14/round3.py", "ABCDEFGHI"))], doc="e2e-14 R3-A..I: the e2e-11 round-3 checks"),
    # e2e-16 (hosting review round 6: D-71..D-74). Each unit arranges its own items/accounts (r16/arrange.py is idempotent)
    # so it is self-contained on whatever shard it lands on. Drivers exit non-zero and print FAIL lines. Weights: minutes
    # measured on the 2026-10-06 runs (arrangement included), calibrated against CI (above).
    Unit("r16-items", 1.0, [("r16-arrange", py("r16/arrange.py")), ("r16-items", py("r16/items.py"))],
         doc="e2e-16: one-item windows on job/folder/multibranch (D-71): Configure/Create reach, legacy scope refused, kind+icon"),
    Unit("r16-rename", 0.8, [("r16-arrange", py("r16/arrange.py")), ("r16-rename", py("r16/rename.py"))],
         doc="e2e-16: no rename through a window via UI and every URL form; allowed for admin/own permission (D-71c)"),
    Unit("r16-follow", 2.4, [("r16-arrange", py("r16/arrange.py")), ("r16-follow", py("r16/follow.py"))],
         doc="e2e-16: windows follow admin rename/move, deletion ends them and DELETE records name the deleting user (D-74, "
             "1864bdc); refused moves once per minute (D-73); CREATE window under a naming strategy (T-08-168)"),
    Unit("r16-params", 1.4, [("r16-arrange", py("r16/arrange.py")), ("r16-params", py("r16/params.py"))],
         doc="e2e-16: typed parameters (core file, stashedFile, base64File, password) page+dialog; values file; 413 at both "
             "stages; repeated name, U+0000, disposal (D-72, D-72b, D-74)"),
    Unit("r16-rerun", 0.5, [("r16-arrange", py("r16/arrange.py")), ("r16-rerun", py("r16/rerun.py"))],
         doc="e2e-16: incident rerun reuses the secret/core file; stashedFile falls back to the validated prefilled form; "
             "fromRerun validation; incident actions need ViewHistory (D-72, D-72a, SPEC 11)"),
    Unit("r16-d60", 0.4, [("r16-arrange", py("r16/arrange.py")), ("r16-d60", py("r16/d60.py"))],
         doc="e2e-16: refused direct build -> prefilled form: run parameter carried (T-06-103), new job page dialog, files "
             "and secrets not carried (D-60, #115)"),
    Unit("r16-names", 0.2, [("r16-arrange", py("r16/arrange.py")), ("r16-names", py("r16/names.py"))],
         doc="e2e-16: CREATE name restriction in the #107 optionalBlock (ticks Create before filling); exact and /regex/"),
    Unit("multibranch", 2.8, [
        ("mb-arrange", py("r15/arrange.py")),
        ("mb-check-crawl", py("r15/check.py", "crawl")),
        ("mb-submit-folder", py("r15/check.py", "submit", "true", "/job/team-mb/batch-control-activation/", "requester")),
        ("mb-submit-branch-new", py("r15/check.py", "submit", "true", "/job/team-mb/job/main/batch-control-activation/", "requester")),
        ("mb-submit-branch-classic", py("r15/check.py", "submit", "false", "/job/team-mb/job/feature-1/batch-control-activation/", "requester")),
        ("mb-submit-pipeline", py("r15/check.py", "submit", "true", "/job/batch-pipeline/batch-control-activation/", "requester")),
        ("mb-submit-daily", py("r15/check.py", "submit", "false", "/job/batch-daily/batch-control-activation/", "requester")),
        ("mb-side-dialog", py("r15/side_dialog.py")),
        ("mb-folder-compare", py("r15/folder_compare.py")),
        ("mb-menu", py("r15/menu.py", "ci")),
    ], doc="e2e-15: multibranch activation page and the DEF-08 job pages"),
    # e2e-17 (security-39 fixes, D-75). r17/arrange.py is idempotent and self-contained (account w17, items r17*).
    Unit("r17-s39", 1.5, [("r17-arrange", py("r17/arrange.py")), ("r17-s39", py("r17/s39.py", "PRD")),
                          ("r17-visibility", py("r17/s39.py", "H"))],
         doc="e2e-17: 404 for malformed and aliased record ids (S-39-01); a window follows two renames and the page shows "
             "the current name; delete + re-create ends it (S-39-02); followed name hidden from non-readers (D-75 (1), own step)"),
    # e2e-19 (gap audit, docs/reports/e2e-19.md): SPEC acceptance lines no CI step checked before. r19/arrange.py is
    # idempotent and self-contained (items r19-*, accounts from JCasC). Each driver exits non-zero and prints FAIL lines,
    # and restores what it changes globally (executors, authorize-project's authenticator). Weights: minutes measured on
    # the 2026-10-08 local runs (arrangement included), calibrated against CI (above).
    Unit("r19-gate", 1.5, [("r19-arrange", py("r19/arrange.py")), ("r19-gate", py("r19/gate.py"))],
         doc="e2e-19: every manual path refused (REST build/buildWithParameters, CLI, build token, build-token-root, "
             "Replay, Rebuild), the job page notice, an approved run exactly once with exact values, Cause on the build "
             "page, marker re-use recorded (SPEC 6, 4, 10, D-30)"),
    Unit("r19-plugins", 0.6, [("r19-arrange", py("r19/arrange.py")), ("r19-plugins", py("r19/plugins.py"))],
         doc="e2e-19: naginator retry refused and recorded, customize-build-now keeps Request Run, lockable-resources and "
             "authorize-project with an approved run, jobConfigHistory one CONFIGURE record (SPEC 6 #34/#36, SPEC 9)"),
    Unit("r19-triggers", 6.0, [("r19-arrange", py("r19/arrange.py")), ("r19-triggers", py("r19/triggers.py"))],
         doc="e2e-19: a real cron schedule (blockTimer, TRIGGER_BLOCKED coalescing, activation, hold) and a real "
             "parameterized-trigger upstream (blockUpstream, allow list) (SPEC 6, 6a)"),
    Unit("r19-lifecycle", 2.5, [("r19-arrange", py("r19/arrange.py")), ("r19-lifecycle", py("r19/lifecycle.py"))],
         doc="e2e-19: request form validation, decision and cancel rules, approver change, self-approval, approved-run "
             "expiry, disabled job, missing Build notice, and the mails each step sends (SPEC 2, 3, 4, 7, 12)"),
    Unit("r19-kinds", 1.0, [("r19-arrange", py("r19/arrange.py")), ("r19-kinds", py("r19/kinds.py")),
                            ("r19-guard", py("r19/guard.py"))],
         doc="e2e-19: matrix project and organization folder windows, credentials and run parameters through an approved "
             "run, rerun of a deleted build; the self-grant guard's 403 page with matrix-auth's form (SPEC 8, 5, 11, 2)"),
    Unit("role", 6.0, [
        ("role-setup", py("r14/role/setup.py")),
        ("role-manage", py("r14/role/manage_roles.py")),
        ("role-assign", py("r14/role/assign_roles.py")),
        ("role-overlay", py("r14/role/grant_overlay.py")),
        ("role-endpoints", py("r14/role/endpoints.py")),
        ("role-naming", py("r19/naming.py")),  # e2e-19 G-27; re-applies profile-role.yaml at its end
        ("flag-role-crawl", flag("true", *ROLE_CRAWL)),
    ] + [(f"role-crawl-{r}", dict(py("r14/crawl.py", r, "new"), env={"BC_CRAWL_LOG": "crawl-role"})) for r in ROLE_CRAWL],
        last=True, doc="e2e-13/14 RS: role-strategy 927 profile, e2e-19 G-27 (RoleBasedProjectNamingStrategy with a CREATE "
            "window), then a crawl under it (replaces the strategy: last)"),
    Unit("r16-durable", 2.2, [("r16-arrange", py("r16/arrange.py")), ("r16-durable", py("r16/durable.py")),
                              ("r19-arrange", py("r19/arrange.py")), ("r19-restart", py("r19/restart.py"))],
         last=True, doc="e2e-16: a window's end survives a failed grant write and a restart (6325e85); e2e-19: pending and "
                        "queued approved requests with typed values survive a restart and run once with the exact values; "
                        "restarts Jenkins, after which JCasC has reset the arrangement's permissions (last)"),
    Unit("r17-disk", 1.3, [("r17-arrange", py("r17/arrange.py")), ("r17-disk", py("r17/disk.py", "GFBU")),
                           ("r17-case", py("r17/disk.py", "C"))],
         last=True, doc="e2e-17: items removed on disk + reload (a rename onto the stale name: 'it could not follow its item'), "
                        "startup end of a vanished item, fail-closed restart re-end with an unreadable change log (S-39-02/03); "
                        "letter case after a restart (own step); reloads and restarts Jenkins (last)"),
    # e2e-18 (bd449cd..d696d5d). r18/arrange.py is idempotent and self-contained (account w18, items r18*).
    Unit("r18-final", 2.2, [("r18-arrange", py("r18/arrange.py")), ("r18-final", py("r18/final.py", "KMDAS"))],
         last=True, doc="e2e-18: creation-time saves (D-76 (2)), expiry notices of moved/unreadable windows (D-75 (1)), "
                        "approval refused while change control is off, recording baselines (D-76 (1)), strategy "
                        "migrate/revert refusals; switches the switches and the authorization strategy (last)"),
    # e2e-21 (71d267b). r21/arrange.py is idempotent (item r21-reject).
    Unit("r21-reject-color", 0.3, [("r21-arrange", py("r21/arrange.py")), ("r21-reject-color", py("r21/reject_color.py", "RGA"))],
         doc="e2e-21: approver-1's Reject button on the run, permission window and activation request pages renders in "
             "var(--destructive-color) (computed colour vs a probe), the Approve button does not"),
]
BY_NAME = {u.name: u for u in UNITS}
ORDER = {u.name: i for i, u in enumerate(UNITS)}

# The CI jobs: fixed, named groups of units, one GitHub Actions job each, named "e2e (<label>)" (.github/workflows/e2e.yml
# reads `shard.py matrix`). Group k is shard k of N = len(GROUPS): `run.sh k/N`, `--bc-shard k/N`, the artefact
# e2e-shard-<k> and ci/out/<k>/ keep the index. A label is lower-case words of letters and digits separated by single
# spaces or '-', at most 25 characters (the rule of .github/test-shards.txt), and says what the group tests. Each role's
# job-UI units stay together, so a red job also tells whose screens broke. `shard.py check` (and `matrix`, which the
# workflow's plan job reads) fails when a unit is in no group or in two, when a group holds two `last` units or a name
# that is no unit, or when a label is not valid; it prints the weight per group and the heaviest group against an automatic split of the
# same weights, so the balance stays visible. A new unit: add it to UNITS and to the group whose label it fits.
# Seven groups (2026-10-09), drawn on the CI-calibrated weights. The five before them took 26.9, 26.8, 26.8, 19.8 and
# 35.0 min per job in run 37883661045 (E2E wall clock 36.7). The floor is approver-1/manager/nobc's job UI (16.6 min of
# units, kept together by the rule above); every other group stays near it. Weight sums: 15.6, 13.1, 15.2, 16.7, 13.9,
# 16.6, 13.7 (+ SETUP_WEIGHT + JOB_OVERHEAD = about 23.3 min for the longest job); the longest-processing-time split of
# the same weights into 7 has 15.1 at most, but splits each role's job UI and mixes unrelated units.
GROUPS = [
    ("crawl and ui checks", [  # requester's and manager's crawls, the DEF-07 recheck, every control per role, the
        "def07", "crawl-requester", "crawl-manager",  # e2e-12/14 targeted checks, round 3, the Reject colour
        "actions", "misc", "targeted", "round3", "r21-reject-color"]),
    ("crawls and multibranch", [  # the crawls of admin, reqonly, approver-1 and nobc, the multibranch job pages
        "crawl-admin", "crawl-reqonly", "crawl-approver-1", "crawl-nobc", "multibranch"]),
    ("admin and role strategy", [  # admin's job UI, then the role-strategy profile and its crawl (last)
        "jobui-new-admin", "jobui-classic-admin", "role"]),
    ("job ui params and restart", [  # requester's job UI, typed parameters, requests with typed values across a
        "jobui-new-requester", "jobui-classic-requester", "r16-params", "r16-durable"]),  # restart (last)
    ("job ui windows and disk", [  # reqonly's job UI, permission windows on items and renames, item kinds, items
        "jobui-new-reqonly", "jobui-classic-reqonly",  # removed on disk with reload and restart (last)
        "r16-items", "r16-rename", "r16-follow", "r16-names", "r17-s39", "r19-kinds", "r17-disk"]),
    ("job ui other roles", [  # the job UI of approver-1, manager and nobc
        "jobui-new-others", "jobui-classic-others"]),
    ("runs and switches", [  # the run gate, reruns and the prefilled form, triggers, other plugins, the request
        "r16-rerun", "r16-d60",  # lifecycle, then the global switches and the strategy (last)
        "r19-gate", "r19-plugins", "r19-triggers", "r19-lifecycle", "r18-final"]),
]
LABEL_SYNTAX = re.compile(r"^[a-z0-9]+(?:[ -][a-z0-9]+)*$")
LABEL_MAX = 25

# Steps (as "<unit>:<step>") that are retried once when they fail (pytest: @pytest.mark.flaky(reruns=1) through
# pytest-rerunfailures; `shard.py run`: the same single retry). A step belongs here only with evidence of an intermittent
# failure (CI history or local runs, named in the comment) AND when its driver is safe to run twice on the same Jenkins
# (read-only, or idempotent arrangement). Every retry is listed in summary.md ("retried") and the JUnit XML, the failed
# attempt's log and traces are kept, so a retry never hides a failure silently. BC_FLAKY=a:b,c:d adds steps for one run.
FLAKY = set(
    # (empty: no step has failed intermittently in the e2e-17..e2e-20 runs or the CI history of e2e.yml; the one CI
    # failure, round3 F "no h-scroll at 1280", was deterministic on the runner's fonts and is fixed in the workflow)
)
FLAKY |={x.strip() for x in os.environ.get("BC_FLAKY", "").split(",") if x.strip()}


def parse_shard(s):
    m = re.fullmatch(r"(\d+)/(\d+)", s or "")
    if not m or not (1 <= int(m.group(1)) <= int(m.group(2))):
        raise SystemExit(f"shard must be <k>/<N> with 1 <= k <= N, got {s!r}")
    return int(m.group(1)), int(m.group(2))


def group_errors(groups=None):
    """Everything wrong with GROUPS (an empty list when every unit is in exactly one group, ...)."""
    groups = GROUPS if groups is None else groups
    errors = [] if groups else ["GROUPS defines no group"]
    labels, where = {}, {}
    for i, (label, names) in enumerate(groups, 1):
        if not isinstance(label, str) or len(label) > LABEL_MAX or not LABEL_SYNTAX.match(label):
            errors.append(f"group {i}: label {label!r} must be lower-case words of letters and digits separated by "
                          f"single spaces or '-', at most {LABEL_MAX} characters")
        elif label in labels:
            errors.append(f"group {i}: label {label!r} is already the label of group {labels[label]}")
        else:
            labels[label] = i
        if not names:
            errors.append(f"group {i} ({label}) has no units")
        for name in names:
            if name not in BY_NAME:
                errors.append(f"group {i} ({label}): {name!r} is not a unit (shard.py units lists them)")
            where.setdefault(name, []).append(i)
        lasts = [name for name in names if name in BY_NAME and BY_NAME[name].last]
        if len(lasts) > 1:
            errors.append(f"group {i} ({label}) holds {len(lasts)} `last` units ({', '.join(lasts)}): at most one per "
                          f"group, because each replaces the strategy or restarts Jenkins and must run last")
    for u in UNITS:
        gs = where.get(u.name, [])
        if not gs:
            errors.append(f"unit {u.name} is in no group (add it to the GROUPS entry whose label it fits)")
        elif len(gs) > 1:
            twice = len(set(gs)) == 1
            errors.append(f"unit {u.name} is listed {len(gs)} times in group {gs[0]}" if twice else
                          f"unit {u.name} is in more than one group: {', '.join(map(str, gs))}")
    return errors


def checked_groups():
    errors = group_errors()
    if errors:
        raise SystemExit("ci/shard.py GROUPS is inconsistent (shard.py check):\n  " + "\n  ".join(errors))
    return GROUPS


def group_for(k, n):
    groups = checked_groups()
    if n != len(groups):
        raise SystemExit(f"shard {k}/{n}: N must be the number of groups, {len(groups)}, so <k>/{len(groups)} with "
                         f"1 <= k <= {len(groups)} (shard.py check lists them)")
    return groups[k - 1]


def group_units(names):
    return sorted((BY_NAME[x] for x in names), key=lambda u: (u.last, ORDER[u.name]))


def bc_units():
    names = [x.strip() for x in os.environ.get("BC_UNITS", "").split(",") if x.strip()]
    unknown = [x for x in names if x not in BY_NAME]
    if unknown:
        raise SystemExit(f"unknown units {unknown}; known: {list(BY_NAME)}")
    return names


def units_for(k, n):
    _, names = group_for(k, n)
    return group_units(bc_units() or names)


def label_for(k, n):
    label, _ = group_for(k, n)
    return f"BC_UNITS {','.join(bc_units())}" if bc_units() else label


def weight(names):
    return sum(BY_NAME[x].weight for x in names)


def job_minutes(names):
    """The estimated minutes of the group's CI job: its units, the setup and the runner's own steps."""
    return weight(names) + SETUP_WEIGHT + JOB_OVERHEAD


def lpt_loads(n):
    """The automatic split the groups replaced (deterministic longest-processing-time over the weights, at most one
    `last` unit per shard), kept only to compare the balance in `shard.py check`: its shard loads without the setup."""
    load = [0.0] * n
    lasts = [0] * n
    for u in sorted(UNITS, key=lambda u: (-u.weight, ORDER[u.name])):
        i = min((i for i in range(n) if not (u.last and lasts[i])), key=lambda i: (load[i], i))
        load[i] += u.weight
        lasts[i] += u.last
    return load


def matrix():
    groups = checked_groups()
    return {"include": [{"shard": i, "of": len(groups), "label": label} for i, (label, _) in enumerate(groups, 1)]}


def check():
    errors = group_errors()
    for e in errors:
        print(f"::error::{e}")
    if errors:
        return 1
    loads = [weight(names) for _, names in GROUPS]
    lpt = max(lpt_loads(len(GROUPS)))
    lines = ["| Shard | Job label | Minutes | Job minutes | `last` unit | Units |", "|---:|---|---:|---:|---|---|"]
    for i, (label, names) in enumerate(GROUPS, 1):
        last = next((x for x in names if BY_NAME[x].last), "")
        lines.append(f"| {i}/{len(GROUPS)} | {label} | {loads[i - 1]:.1f} | {job_minutes(names):.1f} | {last} | "
                     f"{', '.join(u.name for u in group_units(names))} |")
    lines.append(f"| | total | {sum(loads):.1f} | | | {len(UNITS)} units |")
    note = (f"heaviest group {max(loads):.1f} min of units (job estimate with {SETUP_WEIGHT} setup and {JOB_OVERHEAD} "
            f"runner overhead: {max(job_minutes(n) for _, n in GROUPS):.1f}); an automatic split of the same weights "
            f"into {len(GROUPS)} would have {lpt:.1f} at most")
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### e2e groups\n\n" + "\n".join(lines) + f"\n\n{note[0].upper() + note[1:]}.\n")
    print(f"OK: {len(UNITS)} units, each in exactly one of {len(GROUPS)} groups, at most one `last` unit per group; "
          f"{note}")
    return 0


def steps_for(units):
    steps = [("setup:" + name, spec) for name, spec in SETUP]
    if os.environ.get("BC_SKIP_SETUP") == "1":
        steps = []
    for u in units:
        steps += [(f"{u.name}:{name}", spec) for name, spec in u.steps]
    return steps


# ------------------------------------------------------------------ verdicts
FAIL_LINE = re.compile(r"^FAIL ", re.M)
OK_FALSE = re.compile(r'"ok": false')
EXCEPTION = re.compile(r'"EXCEPTION": ')
PASS_LINE = re.compile(r"^PASS ", re.M)


def r15_rows(before):
    """check.jsonl rows that a r15/check.py step appended (the file had `before` lines when the step started)."""
    f = E2E / "r15" / "out" / "check.jsonl"
    if not f.exists():
        return []
    return [json.loads(l) for l in f.read_text().splitlines()[before:] if l.strip()]


# r15/check.py writes "ok": false as a hint for the reviewer (e2e-15 judged each row). The runner judges a row from its
# data, leaving out exactly these known or provoked items (everything else still fails):
BUILD_HISTORY = re.compile(r"^(Success|Failed|Unstable|Aborted|Not built|In progress|Pending)$")


def r15_problems(row):
    left = []
    for p in row.get("problems", []):
        if p.get("kind", "").startswith("known_core"):
            continue  # r15/check.py's own classification (core GET 405/404, e2e-15 Known)
        if p.get("kind") == "duplicate" and p.get("region") == "side" and BUILD_HISTORY.match(p.get("text", "")):
            continue  # the classic side panel's build history widget: one status label per build
        left.append(p)
    provoked = row.get("step") == "submit"  # the empty submit first: the form re-renders with errors as HTTP 400
    bad = [b for b in row.get("bad_responses", []) if not (provoked and re.match(r"400 POST .*/batch-control-activation/submit", b))]
    # "Refused to execute script from": core's DialogEvent MIME line (D-70, e2e-11 UX 2), r15's own KNOWN_CONSOLE.
    # "reading 'replace'": core header.js breadcrumb overflow (xmlEscape <- menuItem), e2e-13 U-1, intermittent; e2e-14
    # confirmed it by stack (round3.py's known_core). r15 records no stack, but the plugin's own scripts
    # (ui/dialogOpener.js, ui/getForm.js) call no replace(), so the message cannot come from plugin code.
    cons = [c for c in row.get("console", []) if not (provoked and "status of 400" in c)
            and "Refused to execute script from" not in c and "Cannot read properties of undefined (reading 'replace')" not in c]
    if row.get("step") == "submit" and row.get("form") and not (row.get("uuid") and row.get("server_detail_status") == 200):
        left.append({"kind": "submit did not land on a stored activation request"})
    return left + [{"kind": "response", "detail": b} for b in bad] + [{"kind": "console", "detail": c} for c in cons]


def verdict(rc, text, timed_out, extra=None):
    reasons = []
    if timed_out:
        reasons.append("timeout")
    elif rc != 0:
        reasons.append(f"exit {rc}")
    lines = text.splitlines()
    fails = [l for l in lines if l.startswith("FAIL ")]
    if fails:
        reasons.append(f"{len(fails)} FAIL line(s)")
    okf = [] if extra is not None else [l for l in lines if OK_FALSE.search(l) and not l.startswith(("FAIL ", "PASS "))]
    if okf:
        reasons.append(f'{len(okf)} "ok": false row(s)')
    exc = [l for l in lines if EXCEPTION.search(l)]
    if exc:
        reasons.append(f"{len(exc)} EXCEPTION row(s)")
    if extra:
        reasons.append(f"{len(extra)} r15 problem(s)")
    return ("FAIL" if reasons else "PASS"), reasons, (fails + okf + exc + (extra or []))[:30], len(PASS_LINE.findall(text))


def observations():
    """Rows the observational drivers logged that a reviewer should look at (not a verdict)."""
    obs = {}
    out = E2E / "r14" / "out"

    def rows(name):
        f = out / name
        if not f.exists():
            return []
        res = []
        for line in f.read_text().splitlines():
            try:
                res.append(json.loads(line))
            except ValueError:
                pass
        return res

    for log in ("crawl.jsonl", "crawl-role.jsonl"):
        r = rows(log)
        if r:
            obs[log] = {
                "page_loads": sum(1 for x in r if x.get("kind") == "page"),
                "control_checks": sum(1 for x in r if x.get("kind") not in ("page", "content")),
                "dialog_cycles": sum(1 for x in r if x.get("kind") == "click" and x.get("result") == "dialog"),
                "nothing_or_click_failed": sum(1 for x in r if x.get("result") in ("NOTHING", "click-failed")),
                "broken_links": sum(1 for x in r if x.get("result") == "BROKEN"),
                "pages_with_console_errors": sum(1 for x in r if x.get("kind") == "page" and x.get("console")),
                "content_defects": sum(1 for x in r if x.get("kind") == "content" and x.get("result") == "DEFECT"),
                "empty_optional_columns": sorted({f'{x.get("pattern")} {x.get("key")}' for x in r if x.get("check") == "empty-optional"})[:80],
                # e2e-16: raw enum values shown as text (INFO, the deferred UX-4/UX-5 items), per value with its URL patterns
                "raw_enum_values": {k: sorted({x.get("pattern") for x in r if x.get("check") == "raw-enum" and x.get("key") == k})[:12]
                                    for k in sorted({x.get("key") for x in r if x.get("check") == "raw-enum"})},
            }
    a = rows("actions.jsonl")
    if a:
        obs["actions.jsonl"] = {"rows": len(a), "exception": sum(1 for x in a if x.get("exception")),
                                "status_ge_400": sum(1 for x in a if (x.get("status") or 0) >= 400)}
    j = rows("jobui.jsonl")
    if j:
        obs["jobui.jsonl"] = {"entries": sum(1 for x in j if x.get("kind") == "entry"),
                              "exception": sum(1 for x in j if x.get("exception")),
                              "nothing": sum(1 for x in j if x.get("result") == "NOTHING")}
    return obs


SESSIONS = re.compile(r"^SESSIONS logins=(\d+) reused=(\d+)", re.M)


class Runner:
    """Runs the steps of one shard one by one (ci/shard.py run, or one pytest test per step: ci/test_shard.py).

    Per step: the driver as a subprocess with its log in logs/, the verdict from its exit code and output, a Playwright
    trace per browser context in traces/<idx>-<step>/ kept only when the step did not pass (BC_TRACE=off: no traces),
    and the login state shared by the shard's steps in a private temporary directory (BC_AUTH_DIR, never in the
    artefacts; see r6/lib.py Session). finish() writes summary.json and summary.md."""

    def __init__(self, k, n, outdir):
        self.k, self.n = k, n
        self.outdir = Path(outdir)
        self.logs = self.outdir / "logs"
        self.logs.mkdir(parents=True, exist_ok=True)
        self.units = units_for(k, n)
        self.label = label_for(k, n)
        self.steps = steps_for(self.units)
        self.pyexe = os.environ.get("PY", sys.executable)
        self.timeout = int(os.environ.get("BC_STEP_TIMEOUT", "2700"))
        self.trace_mode = os.environ.get("BC_TRACE", "retain-on-failure")  # retain-on-failure | on (keep all) | off
        self.trace = self.trace_mode != "off"
        self.auth_dir = Path(tempfile.mkdtemp(prefix=f"bc-auth-{k}-"))
        self.results = {}
        self.attempts = {}
        self.setup_failed = False
        self.t_all = time.time()

    def plan_line(self):
        return f"shard {self.k}/{self.n} ({self.label}): units {[u.name for u in self.units]} ({len(self.steps)} steps)"

    def run_step(self, idx, name, spec):
        attempt = self.attempts.get(idx, 0) + 1
        self.attempts[idx] = attempt
        base = f"{idx:02d}-{name.replace(':', '-').replace('/', '_')}"
        log = self.logs / (base + (f".attempt{attempt}" if attempt > 1 else "") + ".log")
        if attempt > 1 and name.startswith("setup:"):
            self.setup_failed = False  # the retry of the setup step that failed decides again
        if self.setup_failed:
            res = {"step": name, "verdict": "BLOCKED", "reasons": ["setup failed"], "seconds": 0, "log": log.name}
            self.results[idx] = res
            return res
        tdir = self.outdir / "traces" / (base + (f".attempt{attempt}" if attempt > 1 else ""))
        env = dict(os.environ, PYTHONUNBUFFERED="1", BC_AUTH_DIR=str(self.auth_dir), **spec.get("env", {}))
        if self.trace:
            env["BC_TRACE_DIR"] = str(tdir)
        is_r15 = spec["argv"][0] == "r15/check.py"
        r15_before = len(r15_rows(0)) if is_r15 else 0
        argv = [self.pyexe] + spec["argv"]
        t0 = time.time()
        timed_out = False
        with open(log, "w") as fh:
            fh.write("$ " + " ".join(spec["argv"]) + "\n")
            fh.flush()
            try:
                p = subprocess.run(argv, cwd=E2E, env=env, stdout=fh, stderr=subprocess.STDOUT, timeout=self.timeout)
                rc = p.returncode
            except subprocess.TimeoutExpired:
                rc, timed_out = -1, True
        secs = round(time.time() - t0, 1)
        text = log.read_text(errors="replace")
        extra = None
        if is_r15:
            extra = [json.dumps({"user": r.get("user"), "flag": r.get("flag"), "page": r.get("page"), "problem": p})[:400]
                     for r in r15_rows(r15_before) for p in r15_problems(r)]
            extra += [json.dumps({"page": r.get("page"), "status": r.get("status"), "expected": 404})
                      for r in r15_rows(r15_before) if "expected" in r and r.get("status") != r["expected"]]
        v, reasons, lines, passes = verdict(rc, text, timed_out, extra)
        traces = sorted(tdir.glob("*.zip")) if tdir.exists() else []
        recorded = len(traces)
        if v == "PASS" and tdir.exists() and self.trace_mode != "on":
            shutil.rmtree(tdir, ignore_errors=True)  # retain-on-failure
            traces = []
        sess = [tuple(map(int, m)) for m in SESSIONS.findall(text)]
        prev = self.results.get(idx)
        res = {"step": name, "verdict": v, "reasons": reasons, "seconds": secs, "log": log.name,
               "pass_lines": passes, "failure_lines": lines, "attempt": attempt,
               "traces": [str(t.relative_to(self.outdir)) for t in traces], "traces_recorded": recorded,
               "logins": sum(a for a, _ in sess), "logins_reused": sum(b for _, b in sess)}
        if prev and attempt > 1:
            res["earlier_attempts"] = prev.get("earlier_attempts", []) + [
                {k: prev[k] for k in ("verdict", "reasons", "seconds", "log", "traces")}]
            if v == "PASS":
                res["reasons"] = [f"passed on attempt {attempt} (flaky step, retried)"]
        self.results[idx] = res
        print(f"[{time.strftime('%H:%M:%S')}] {v:4} {secs:7.1f}s {name} {'; '.join(res['reasons'])}"
              f"{f' (traces: {len(traces)})' if traces else ''}", flush=True)
        if v != "PASS" and name.startswith("setup:"):
            self.setup_failed = True
        return res

    def finish(self):
        shutil.rmtree(self.auth_dir, ignore_errors=True)
        results = [self.results[i] for i in sorted(self.results)]
        total = round(time.time() - self.t_all, 1)
        summary = {"shard": f"{self.k}/{self.n}", "label": self.label, "units": [u.name for u in self.units],
                   "seconds": total,
                   "steps": results, "observations": observations(),
                   "failed": [r["step"] for r in results if r["verdict"] != "PASS"],
                   "retried": [r["step"] for r in results if r.get("attempt", 1) > 1],
                   "steps_traced": sum(1 for r in results if r.get("traces_recorded")),
                   "traces_kept": sum(len(r.get("traces", [])) + sum(len(e.get("traces", [])) for e in r.get("earlier_attempts", []))
                                      for r in results),
                   "logins": sum(r.get("logins", 0) for r in results),
                   "logins_reused": sum(r.get("logins_reused", 0) for r in results)}
        (self.outdir / "summary.json").write_text(json.dumps(summary, indent=1))
        (self.outdir / "summary.md").write_text(markdown(summary))
        return 1 if summary["failed"] else 0


def run(k, n, outdir):
    r = Runner(k, n, outdir)
    print(r.plan_line(), flush=True)
    for idx, (name, spec) in enumerate(r.steps, 1):
        res = r.run_step(idx, name, spec)
        if res["verdict"] == "FAIL" and name in FLAKY:
            r.run_step(idx, name, spec)
    return r.finish()


def markdown(s):
    out = [f"### e2e ({s.get('label', '')}), shard {s['shard']}: {'FAIL' if s['failed'] else 'PASS'}", "",
           f"Units: {', '.join(s['units'])}. Steps: {len(s['steps'])}, failed: {len(s['failed'])}, "
           f"retried: {len(s.get('retried', []))}, driver time {s['seconds'] / 60:.1f} min, "
           f"logins {s.get('logins', 0)} (reused sessions {s.get('logins_reused', 0)}), steps traced "
           f"{s.get('steps_traced', 0)} (traces kept for failed steps: {s.get('traces_kept', 0)}).", "",
           "| Step | Result | Seconds | Notes |", "|---|---|---|---|"]
    for r in s["steps"]:
        notes = "; ".join(r["reasons"]) or (f"{r['pass_lines']} PASS lines" if r.get("pass_lines") else "")
        if r.get("traces"):
            notes += f" (Playwright traces: `{Path(r['traces'][0]).parent}/`)"
        out.append(f"| `{r['step']}` | {r['verdict']} | {r['seconds']} | {notes} |")
    fails = [r for r in s["steps"] if r.get("failure_lines")]
    if fails:
        out += ["", "#### Failure lines (first 30 per step)", ""]
        for r in fails:
            out.append(f"`{r['step']}` (`logs/{r['log']}`):")
            out.append("```")
            out += [l[:400] for l in r["failure_lines"]]
            out.append("```")
    if s.get("observations"):
        out += ["", "#### Observational drivers (for review, not a verdict)", "", "```", json.dumps(s["observations"], indent=1), "```"]
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("units")
    sub.add_parser("check")
    sub.add_parser("matrix")
    p = sub.add_parser("label")
    p.add_argument("shard")
    p = sub.add_parser("plan")
    p.add_argument("shard")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("plan-all")
    p.add_argument("n", type=int, nargs="?")
    p = sub.add_parser("run")
    p.add_argument("shard")
    p.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.cmd == "units":
        group_of = {x: label for label, names in GROUPS for x in names}
        print(f"setup ({SETUP_WEIGHT} min, every shard): " + ", ".join(n for n, _ in SETUP))
        for u in UNITS:
            print(f"{u.name:16} {u.weight:5.1f} min{' (last)' if u.last else ''}  [{group_of.get(u.name, 'no group')}]  "
                  f"{u.doc}")
            for name, spec in u.steps:
                print(f"    {name:26} {' '.join(spec['argv'])}{'  ' + str(spec['env']) if spec.get('env') else ''}")
    elif a.cmd == "check":
        sys.exit(check())
    elif a.cmd == "matrix":
        print(json.dumps(matrix(), separators=(",", ":")))
    elif a.cmd == "label":
        k, n = parse_shard(a.shard)
        print(label_for(k, n))
    elif a.cmd == "plan":
        k, n = parse_shard(a.shard)
        units = units_for(k, n)
        steps = steps_for(units)
        if a.json:
            print(json.dumps({"shard": a.shard, "label": label_for(k, n), "units": [u.name for u in units],
                              "steps": [{"step": s, "argv": spec["argv"]} for s, spec in steps]}, indent=1))
        else:
            print(f"shard {k}/{n} ({label_for(k, n)}): {[u.name for u in units]}")
            for s, spec in steps:
                print(f"  {s:34} {' '.join(spec['argv'])}")
    elif a.cmd == "plan-all":
        n = len(checked_groups())
        if a.n is not None and a.n != n:
            raise SystemExit(f"plan-all {a.n}: there are {n} groups (shard.py check lists them)")
        for i, (label, names) in enumerate(GROUPS, 1):
            print(f"shard {i}/{n} ({label}): ~{SETUP_WEIGHT + weight(names):.0f} min drivers "
                  f"(incl. {SETUP_WEIGHT:.0f} setup), ~{job_minutes(names):.0f} min job: "
                  f"{[u.name for u in group_units(names)]}")
    elif a.cmd == "run":
        k, n = parse_shard(a.shard)
        sys.exit(run(k, n, a.out))


if __name__ == "__main__":
    main()
