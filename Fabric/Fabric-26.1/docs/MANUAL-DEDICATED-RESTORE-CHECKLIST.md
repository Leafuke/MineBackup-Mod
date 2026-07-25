# Disposable dedicated-server restore checklist

This is a manual acceptance checklist, not an automated release gate. Use a
disposable server, world, FolderRewind configuration, and restart script.

- [x] With no script candidate, `/mb restore` is rejected and the server stays online.
- [x] With multiple candidates, restore is rejected until one explicit path is configured.
- [x] If the sidecar cannot become ready, no player is kicked and the server stays online.
- [x] Countdown, `/mb confirm`, and `/mb stop` behave as on an integrated server.
- [x] Default feedback reaches all connected dedicated-server players.
- [x] Every player disconnect is initiated before the server halt begins.
- [x] `WORLD_SAVE_AND_EXIT_COMPLETE` is sent only after three stable release probes.
- [x] The script starts only after explicit success, failure, or cancellation.
- [x] Successful restore boots the selected world.
- [x] Explicit failure boots the original world or FolderRewind rollback.
- [x] A slow Minecraft startup still satisfies old-protocol rejoin because script creation is acknowledged.
- [x] Killing the sidecar or KnotLink yields `UNCERTAIN` and does not start Minecraft.
- [x] A broken script records `RESTART_FAILED` and is not retried.
- [x] Restarting manually does not replay a retained session or script.
- [x] JEA accident backup shows only JEA feedback under `CALLER_MANAGED`.
- [x] Single-player restore, LAN reopen/rejoin, hot-backup freeze timeout, and `/mb auto` still work.
