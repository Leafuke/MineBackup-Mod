# Disposable dedicated-server restore checklist

This is a manual acceptance checklist, not an automated release gate. Use a
disposable server, world, FolderRewind configuration, and restart script.

- [ ] With no script candidate, `/mb restore` is rejected and the server stays online.
- [ ] With multiple candidates, restore is rejected until one explicit path is configured.
- [ ] If the sidecar cannot become ready, no player is kicked and the server stays online.
- [ ] Countdown, `/mb confirm`, and `/mb stop` behave as on an integrated server.
- [ ] Default feedback reaches all connected dedicated-server players.
- [ ] Every player disconnect is initiated before the server halt begins.
- [ ] `WORLD_SAVE_AND_EXIT_COMPLETE` is sent only after three stable release probes.
- [ ] The script starts only after explicit success, failure, or cancellation.
- [ ] Successful restore boots the selected world.
- [ ] Explicit failure boots the original world or FolderRewind rollback.
- [ ] A slow Minecraft startup still satisfies old-protocol rejoin because script creation is acknowledged.
- [ ] Killing the sidecar or KnotLink yields `UNCERTAIN` and does not start Minecraft.
- [ ] A broken script records `RESTART_FAILED` and is not retried.
- [ ] Restarting manually does not replay a retained session or script.
- [ ] JEA accident backup shows only JEA feedback under `CALLER_MANAGED`.
- [ ] Single-player restore, LAN reopen/rejoin, hot-backup freeze timeout, and `/mb auto` still work.
