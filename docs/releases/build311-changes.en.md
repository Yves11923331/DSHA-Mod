# DSHA dsha.v2 / build311

- Settings · Device grants gains "Cross-app files": grant folder by folder through the system document picker, toggle assistant writes per folder, and revoke a grant at any time.
- New `/app/saf/*` bridge endpoints: list folders, read files (text, base64, ranged), write small text, pull a file into the container, push a container file back, create folders and confirm-delete. Only folder ids from the grant list are accepted.
- Rebased onto upstream build310 (switchable release identity, island notification fixes, backup and bridge install-source boundary fixes) while keeping the landscape Dock, keep-screen-on and winter icon mods.
- Verification scope: local offline compile and source checks pass; device checks for grant, read/write and revoke happen after the cloud build. versionCode 311 (based on build310), versionName dsha.v2, package dsga.mod.
