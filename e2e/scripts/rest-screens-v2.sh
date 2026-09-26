#!/usr/bin/env bash
# The screens that changed after e2e-01, checked at markup level so the browser
# pass only has to judge layout:
#
#   T-E2E-05 re-judgement  the decision screen now carries "Recent Runs of This
#                          Job" and a size control (?runs=5/10/20/50)
#   the allow-list         ?runs=100000 / -1 / abc must fall back to the default,
#                          never read more history than the largest option
#   UX-9                   the executed run is a link
#   UX-10                  an APPROVED-but-not-yet-run request says so
#   E2E-D4                 the global configuration label no longer collides
#   UX-11 / UX-12          active grants show the remaining time; "1 minute"
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### screens after the e2e-01 fixes"

bc_login requester
bc_login approver
bc_login admin

rows() {  # how many recent-run rows the decision screen rendered
  { grep -o 'href="[^"]*/job/batch-daily/[0-9]*/*"' "$1" || true; } | wc -l | tr -d ' '
}

# --- a PENDING request to look at
json='{"reason":"e2e-02 decision screen check: rerun after the feed was corrected","approver":"approver","parameter":[{"name":"DATE","value":"2026-05-05"},{"name":"MODE","value":"partial"}]}'
status=$(bc_post requester "$OUT_DIR/s2-create.html" "/job/batch-daily/batch-control/submit" \
        -D "$OUT_DIR/s2-create.headers" --data-urlencode "json=$json")
REQUEST_PATH=$(grep -i '^location:' "$OUT_DIR/s2-create.headers" | tail -1 \
        | sed -e 's/^[Ll]ocation: *//' -e 's#^https*://[^/]*##' | tr -d '\r')
echo "--- POST submit -> HTTP $status, $REQUEST_PATH"

status=$(bc_get approver "$OUT_DIR/s2-decision.html" "$REQUEST_PATH")
echo "--- GET $REQUEST_PATH (approver, default) -> HTTP $status"
# The size control changed shape in e98d211: it used to be a GET form (select +
# "Show" button), and is now a row of links, because core's hudson-behavior.js
# appends the CSRF crumb to every form and a GET form put it in the address bar.
# Both shapes are probed, so the output says which one the running build serves.
for needle in 'Recent Runs of This Job' '<th>Build' '<th>Result' '<th>Started' '<th>Duration' \
              'Runs to show' 'href="?runs=' 'aria-current' 'name="runs"' '>Show<' \
              'Reason' 'Parameters' 'Decision'; do
  if grep -q -- "$needle" "$OUT_DIR/s2-decision.html"; then r=YES; else r=NO; fi
  printf '    carries %-28s %s\n' "'$needle'" "$r"
done
echo "    recent-run rows: $(rows "$OUT_DIR/s2-decision.html")"
echo "    size options offered: $({ grep -o 'href="?runs=[0-9]*"\|<option value="[0-9]*"' "$OUT_DIR/s2-decision.html" || true; } | sort -u | tr '\n' ' ')"
echo "    current size marked: $({ grep -o 'aria-current="true">[0-9]*<\|<option value="[0-9]*" selected="selected"' "$OUT_DIR/s2-decision.html" || true; } | head -1)"
echo "    count sentence: $(grep -o 'Showing [^<]*' "$OUT_DIR/s2-decision.html" | head -1)"

# --- the size control and its allow-list
for value in 10 20 50 100000 -1 abc ''; do
  body="$OUT_DIR/s2-runs-${value:-empty}.html"
  status=$(bc_get approver "$body" "${REQUEST_PATH}?runs=$value")
  # The current size is marked by aria-current (links) or selected="selected"
  # (the pre-e98d211 form); read whichever the running build emits.
  sel=$({ grep -o 'aria-current="true">[0-9]*<' "$body" || true; } | head -1 \
        | sed -e 's/aria-current="true">//' -e 's/<$//')
  if [ -z "$sel" ]; then
    sel=$({ grep -o '<option value="[0-9]*" selected="selected"' "$body" || true; } | head -1 \
          | sed -e 's/<option value="//' -e 's/" selected="selected"//')
  fi
  printf -- "--- ?runs=%-8s -> HTTP %s, selected=%-3s rows=%s\n" "${value:-<empty>}" "$status" "${sel:-none}" "$(rows "$body")"
done

# --- UX-10: the APPROVED-but-not-executed notice, then UX-9: the executed run link
#
# The notice is only correct while a request is APPROVED with no run recorded, and
# on this environment that window is shorter than one HTTP round trip. Taking the
# executors away holds the approved build in the queue so the state can be read;
# they are given back immediately afterwards.
cat > "$OUT_DIR/s2-executors.groovy" <<'GROOVY'
import jenkins.model.Jenkins
Jenkins.get().setNumExecutors(EXECUTORS)
return "numExecutors = ${Jenkins.get().getNumExecutors()}"
GROOVY
sed 's/EXECUTORS/0/' "$OUT_DIR/s2-executors.groovy" > "$OUT_DIR/s2-executors-0.groovy"
sed 's/EXECUTORS/2/' "$OUT_DIR/s2-executors.groovy" > "$OUT_DIR/s2-executors-2.groovy"
echo "--- $(bc_script "$OUT_DIR/s2-executors-0.groovy") (holding the approved build in the queue)"

status=$(bc_post approver "$OUT_DIR/s2-approve.html" "${REQUEST_PATH}approve" --data-urlencode "comment=e2e-02")
echo "--- POST approve -> HTTP $status"
status=$(bc_get approver "$OUT_DIR/s2-approved.html" "$REQUEST_PATH")
if grep -q -e 'batch-control-approved-notice' \
        -e 'This request is approved and the run starts shortly' "$OUT_DIR/s2-approved.html"; then
  echo "    APPROVED notice right after approving: YES"
else
  echo "    APPROVED notice right after approving: NO (the run may already have started)"
fi
echo "    status now: $(grep -o '>APPROVED<\|>EXECUTED<' "$OUT_DIR/s2-approved.html" | sort -u | tr '\n' ' ')"

echo "--- $(bc_script "$OUT_DIR/s2-executors-2.groovy") (letting the queue drain again)"
echo "--- waiting for the run to finish, then re-reading the page"
for _ in $(seq 1 30); do
  bc_get admin "$OUT_DIR/s2-last.json" "/job/batch-daily/lastBuild/api/json?tree=number,building" > /dev/null
  if grep -q '"building":false' "$OUT_DIR/s2-last.json"; then break; fi
  sleep 2
done
status=$(bc_get approver "$OUT_DIR/s2-executed.html" "$REQUEST_PATH")
echo "--- GET $REQUEST_PATH after execution -> HTTP $status"
echo "    status now: $(grep -o '>APPROVED<\|>EXECUTED<' "$OUT_DIR/s2-executed.html" | sort -u | tr '\n' ' ')"
echo "    executed run rendered as: $(grep -o '<th style="text-align: left;">Executed run</th>[^§]\{0,200\}' "$OUT_DIR/s2-executed.html" | grep -o '<a href="[^"]*">[^<]*</a>\|<td>[^<]*</td>' | head -1)"
if grep -q -e 'batch-control-approved-notice' \
        -e 'This request is approved and the run starts shortly' "$OUT_DIR/s2-executed.html"; then
  echo "    APPROVED notice still shown after execution: YES (it should be gone)"
else
  echo "    APPROVED notice still shown after execution: NO (correct)"
fi

# --- E2E-D4: the global configuration label
status=$(bc_get admin "$OUT_DIR/s2-config.html" "/manage/configure")
echo "--- GET /manage/configure -> HTTP $status"
if grep -q 'Enable run controlWhen enabled' "$OUT_DIR/s2-config.html"; then
  echo "    label/description collision still present: YES"
else
  echo "    label/description collision still present: NO (fixed)"
fi
echo "    'When enabled, protected jobs require' still inline on the page: $(grep -c 'When enabled, protected jobs require' "$OUT_DIR/s2-config.html" || true)"
status=$(bc_get admin "$OUT_DIR/s2-help.html" "/descriptor/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration/help/runControlEnabled")
echo "    the field's help page -> HTTP $status, $(grep -o 'approved run request[^<]*' "$OUT_DIR/s2-help.html" | head -1)"

# --- UX-11 / UX-12 on the grants screen
bash "$(dirname "$0")/grant-setup.sh" batch-daily 30 > /dev/null
status=$(bc_get requester "$OUT_DIR/s2-grants.html" "/batch-control/grants/")
echo "--- GET /batch-control/grants/ (requester, one active grant) -> HTTP $status"
echo "    'Remaining' column: $(grep -c '<th>Remaining</th>' "$OUT_DIR/s2-grants.html" || true)"
echo "    remaining value rendered: $(grep -o '[0-9]* min [0-9]* sec\|[0-9]* min\|expired' "$OUT_DIR/s2-grants.html" | head -2 | tr '\n' ' ')"
echo "    duration options: $(grep -o '<option value="[0-9]*">[^<]*</option>' "$OUT_DIR/s2-grants.html" | tr '\n' ' ')"
