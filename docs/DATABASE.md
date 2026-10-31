# SQLite database proposal

The proposed relational schema is in [database-schema.json](./database-schema.json). It supports multiple user records while enforcing one board per user. A board owns ordered columns; cards belong to one board and one of its columns. Composite keys permit the same fixed column/card identifiers to be reused in different users' boards.

## MVP account and board

The MVP keeps authentication credentials hardcoded in the backend as `user` / `password`; it does not store a password or password hash in SQLite. On initialization, seed the corresponding `users` row with the stable ID `mvp-user` and create that user's board and five initial columns if they do not already exist. Future authentication changes will require an explicit schema and credential-storage decision.

The existing signed session identifies the MVP username. Board API operations must resolve that identity to a user row and constrain reads and writes to that user's board.

## Initialization, ordering, and deletion

Create the database file and tables if they are missing. Enable SQLite foreign-key enforcement on every connection, initialize or migrate the schema transactionally, and track the schema version with `PRAGMA user_version`. Seed the user and demo board only when missing; do not overwrite existing board content on restart.

Column `position` defines the fixed column order. Card `position` defines ordering within its column. The five-column set and the prohibition on adding/removing columns are application-level invariants; renaming columns is allowed. Board updates should use a transaction so a move or reorder cannot leave inconsistent positions. Deleting a card removes that card; deleting a board or user cascades to its dependent rows. The MVP UI/API does not delete users, boards, or columns.

## Container persistence

Store the SQLite file at `/data/kanban.sqlite3` inside the container and mount a named Docker volume `pm-data` at `/data`. The volume must be retained when the application container is stopped or recreated so board changes persist across restarts. A local non-container run may use a configurable database path with the same schema.

This is a proposal only. Part 6 implementation must wait for user sign-off.
