# Improvements — Proposals & Backlog

Concrete actionable items live on the board (`../TASKS.md`) so there's one place
a session checks first. Use this folder for improvement **proposals** that need
user discussion before becoming tasks — one file per proposal, named
`YYYY-MM-DD-short-slug.md`, linked from `../TASKS.md` when accepted.

Current backlog requiring a user decision (also noted in TASKS.md "Repo hygiene"):

1. Remove the two tracked debug APKs (~79 MB) from git / move them to GitHub
   Releases or LFS — pack is ≈ 288 MB.
2. Restore a CI workflow (`mobile-ci.yml`) — or fold it into the desktop-phased
   CI plan in `desktop-docs/09-platform-decisions.md` § CI.
3. Delete `this.md` (15-byte stub) and clean PWA leftovers from `.gitignore`
   (`.wxt`, `playwright-report`, `.superpowers` etc.).
4. Android SDK provisioning script for fresh VMs (currently manual, per
   `../PROJECT_CONTEXT.md`).
