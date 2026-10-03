# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- While change control is on, moving an item between folders (`Item/Move`) by a
  user without `Overall/Administer` requires `Item/Delete` on the item and
  `Item/Create` at the destination, each standing or from an active permission
  window; a `CREATE` window's name restriction applies to the moved item's name.
  A refused move changes nothing, shows a plain message and is recorded as
  `GRANT_VIOLATION`. The standing change permissions monitor lists `Item/Move`.
  See [LIMITATIONS item 44](docs/LIMITATIONS.md#moving-items). (D-59)

### Security

- Closed a bypass in which moving a job into or out of a permission window's
  scope let a user edit or delete a job the window was not meant to cover. (D-59)
