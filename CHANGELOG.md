# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Requesting a run (job request form, incident rerun and the service API) no
  longer requires `Item/Build` on the job; `BatchControl/Request` and
  `Item/Read` are enough, plus `BatchControl/ViewHistory` for an incident rerun.
  The request detail page and the approver notification state when the
  requester lacks `Item/Build`. On approval-required jobs, administrators can
  grant `Request` instead of `Build`. Jobs that do not require approval, and
  the instance while run control is off, keep Jenkins' own Build semantics.
  (D-38a)
