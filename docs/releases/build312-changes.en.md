# DSHA dsha.v3 / build312

- New builtin guide plugin `dsh-saf-guide`: the cross-app file channel is now part of the system prompt (the port is read from `/root/.dsh/.bridge_port`, never guessed as 3090), so the assistant finds it without installing a skill; the section disappears when the capability is unavailable.
- Cross-app files ship in the coexist build: a new row in device grants, a management page (pick folder / allow writes / remove), a read-only browser, and nine bridge endpoints `/app/saf/*`.
- Base stays upstream build310 (`f0f11905`); the landscape Dock, keep-screen-on and winter icon mods are all kept.
- Verification scope: local offline compile and the seven `preBuild` gates pass; on-device grant, read/write and revoke are checked after install. versionCode 312 (based on build310), versionName dsha.v3, package dsga.mod.
