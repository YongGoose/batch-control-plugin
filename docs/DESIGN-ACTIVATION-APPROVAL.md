# Design: separating job creation from job activation

Status: **proposal, awaiting the owner's decision.** Nothing here is implemented.
It exists to be argued with before code is written.

## 1. What was asked

> Make it so that an open permission window cannot clear the lock. A window has to
> be open for the job to be created in the first place, so a lock the same window
> can undo means nothing. Separate them completely.

The observation is correct, and it invalidates the shape we were about to ship.

## 2. Why the current shape does not deliver it

D-34 makes a newly created job start with `blockTimer` and `blockUpstream` set, so
it will not run on a schedule until someone clears them. Both are fields of
`BatchControlJobProperty`, which lives in the job's `config.xml`.

That means clearing them requires exactly one thing: `Item.CONFIGURE` on the job.
A CONFIGURE permission window grants precisely that. So this whole sequence fits
inside a single approved window:

1. Create the job (needs `Item.CREATE`, in the window).
2. Open its configuration (needs `Item.CONFIGURE`, same window).
3. Untick both boxes (same permission, same window).
4. The job is now in service, and no approver ever consented to that.

The approver approved a window. They did not approve a job entering production.
The lock is real against someone with no window at all, and decorative against
the only people who can create jobs in the first place.

## 3. The constraint that decides the design

The instinct is to hide or disable the two checkboxes for window holders. That
does not work, and it is worth being explicit about why, because it is the same
mistake that produced E2E-D1.

`config.xml` can be written through at least five paths:

| Path | Goes through the config form? |
|---|---|
| The web configuration page | yes |
| `POST /job/X/config.xml` (REST) | no |
| `jenkins-cli.jar update-job` | no |
| Groovy, the script console, any plugin calling `setProperty` | no |
| JCasC or programmatic job creation | no |

The form is one path out of five. A guarantee enforced in the form is not a
guarantee, it is a suggestion to people using a browser. Hiding a checkbox from a
window holder leaves that same holder able to `curl` the field off.

We already proved the neighbouring idea impossible in the H1 PoC: a save cannot be
vetoed, because `SaveableListener` fires *after* the write has happened.

**So the authority cannot live in the job's configuration at all.** As long as the
fact "this job may run unattended" is stored somewhere `Item.CONFIGURE` can write,
`Item.CONFIGURE` decides it.

## 4. The model

Move the decision out of `config.xml` into the plugin's own store, which no
Jenkins permission writes directly.

```
$JENKINS_HOME/batch-control/activations/<encoded job full name>.xml

  activated      : boolean
  activatedBy    : the approver who granted it
  activatedAt    : epoch millis
  requestId      : the ACTIVATION request that authorised it
  deactivatedBy  : set when put back on hold
  deactivatedAt  : set when put back on hold
```

This is the `script-security` pattern, the one precedent in the ecosystem for
exactly this problem. `ScriptApproval` lets the save succeed and refuses the
*use*. Nobody has to intercept a write; the effect is withheld until an approval
exists. It is accepted practice in Jenkins, and it is the only shape that survives
all five save paths, because every path eventually has to go through the queue.

### The gate rule

The queue gate stops reading the job property as the decision and reads two
independent inputs, ANDed:

```
timer or upstream cause, on a run-controlled job:
    pass only if  activationStore.isActivated(job)
                  AND NOT property.blockTimer / blockUpstream
```

The two controls answer different questions and both must say yes:

- **Activation** — "has an approver consented to this job running unattended?"
  Stored outside the job. Changed only by an approved ACTIVATION request.
- **`blockTimer` / `blockUpstream`** — "does the job's owner want this trigger
  blocked right now?" Ordinary job configuration, changed by `Item.CONFIGURE`.

Clearing a checkbox therefore cannot activate anything. It can only decline to add
a second block on top of activation. That is what full separation means in code:
neither mechanism can substitute for the other, in either direction.

## 5. A new request type: ACTIVATION

A third type alongside the run request and the permission window.

- The requester asks to activate job X, with a reason.
- An approver decides. `ApprovalPolicy` already requires the designated approver
  specifically, and that carries over unchanged.
- On approval the store is written and a `ChangeRecord` is emitted.
- The request carries the job's full name, which matters: it is the first request
  type here whose subject is a *specific named thing* rather than a person plus a
  time window. `GrantRequest` has no field describing what will be changed. An
  ACTIVATION request has exactly one subject and no window.

### Deactivation is deliberately asymmetric

Activation is expensive: it needs approval. Deactivation, putting a job on hold,
should be **cheap** — no approval, no window, immediate, available to anyone with
the hold permission.

Stopping a batch job is safe. Starting one is not. Making both symmetrical would
mean that during an incident you need an approver's attention before you can stop
a job that is actively causing damage, which is the opposite of what change
control is for. This also answers the owner's scenario 3 (putting a running job on
hold) with a mechanism instead of a configuration edit.

## 6. The dangerous part: upgrade

If `activated` defaults to false, then the moment this version is installed
**every existing job stops running on schedule.** For a plugin whose entire
audience is people running batch jobs, that is not a migration, it is an outage.

So an `@Initializer` seeds every job that exists at first upgrade as activated,
with `activatedBy = "upgrade"` and a `ChangeRecord` saying so. Jobs created after
that point default to not activated. The seeding must be idempotent and keyed by a
stored schema marker, not by "is the directory empty" — that would re-seed after
someone clears it.

Two consequences, stated now rather than discovered later:

- The guarantee applies to jobs created from this version onward. Existing jobs
  are grandfathered, and the documentation has to say so instead of implying a
  clean slate.
- JCasC will not carry activation state, because it is not job configuration.
  That is the correct outcome: a configuration-as-code apply must not be able to
  put a job into service.

## 7. What D-34 keeps doing

D-34 is not wasted under this design, and not merely compatible with it. It is the
visible half.

The activation store is authoritative but invisible in the job's configuration.
`blockTimer` / `blockUpstream` set on creation are what a person *sees* when they
open a new job and ask why it is not running. They also hold the line for anyone
who installs this plugin without wiring up the wrapping authorization strategy,
where the activation flow has no approver to route to. Merge D-34 as it stands.

## 8. Checked against the Jenkins hosting rules

| Rule | This design |
|---|---|
| No core patches, extension points only | Holds. One new store, one new request type, the existing `Queue.QueueDecisionHandler` |
| Do not weaken or redefine core permissions | Holds. We add a condition; we never grant what core would deny |
| Do not silently break existing installations on upgrade | This is the risk. Addressed in section 6, and the item most likely to be raised in hosting review, so the seeding should be the best-tested code in the change |
| State outside `config.xml` | Normal. `script-security` and `credentials` do it; we already write to `$JENKINS_HOME/batch-control/` |
| Refusing the effect rather than the save | Has an accepted precedent (`ScriptApproval`). Worth citing explicitly in the hosting request, since a reviewer meeting it cold may read it as surprising |
| New permission | `BatchControl/RequestActivation`, or reuse the existing request permission. Reuse is simpler, and the approval step is the real gate |

I see no rule this violates. The upgrade path is the only place a reviewer is
likely to push back, and they would be right to.

## 9. Scope

| Piece | Rough size |
|---|---|
| Activation store and model | small, mirrors the existing store classes |
| ACTIVATION request type, service, policy wiring | medium, follows the run-request lane closely |
| Queue gate change | small: a condition, not a restructure |
| Hold / deactivate path | small |
| Upgrade seeding and schema marker | small code, **disproportionately large test surface** |
| Screens: request form, approval row, job sidebar entry, admin list | medium |
| Docs: SPEC items, a DECISIONS entry, README, LIMITATIONS | medium |

The honest summary: the code is not large, but the test obligation is, and it
concentrates in one place. Everything else fails loudly. Bad seeding fails
silently, in production, on somebody else's Jenkins.

## 10. Decisions the owner still has to make

1. **Grandfathering.** Section 6 seeds existing jobs as activated to avoid an
   outage. The alternative, everything starting on hold so operators activate
   deliberately, is more honest to the concept and far more disruptive. Which?
2. **Does deactivation need approval?** Section 5 argues no, on incident-response
   grounds. Say if you want it approved anyway.
3. **Scope of an activation.** Per job (proposed), or per job plus config hash so
   that changing the job's steps forces re-activation? The second is closer to
   real change control and generates approval churn on every edit. Per-run
   approval was already rejected for that same reason.
4. **Permission.** A new `BatchControl/RequestActivation`, or reuse the existing
   request permission?
