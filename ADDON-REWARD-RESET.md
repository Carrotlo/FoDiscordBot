# Reward reset notifications

Addons can override the default `FoDiscordBotAddon.onRewardsReset(RewardReset)` method.
No new command, permission, configuration, database schema, or automatic reset is introduced.

The reward reset command emits a notification only after its repository operation succeeds,
including a zero-row success. The payload identifies linked versus booster rewards, gamemode,
all-player versus Minecraft UUID versus Discord-ID scope, changed rows, and an operation UUID.
Existing confirmation and permission checks are unchanged. Failed, cancelled, unknown-player,
leaderboard-reset and direct repository-call paths do not notify addons.

Callbacks execute on the command worker and must not access main-thread-only Bukkit APIs.
Keep them short; queue longer processing on a bounded addon executor. Runtime/linkage failures
are isolated per addon. Host success is not rolled back or retried because a callback fails.
Only addons registered on the executing server are notified. Existing addons remain compatible
through the default no-op method.

Delivery is best-effort: a crash between the host commit and notification can lose the event.
There is no durable operation log or automatic replay. Each separately executed command has a
new operation UUID, even for identical arguments; this is not a season identifier. Retain the
same UUID if forwarding the payload and deduplicate it in the consumer. Do not implement an
atomic network-wide season rollover using this callback alone. Durable recovery/reconciliation
is separate work, and a player-specific or booster reset is not a global seasonal reset.

## Validation

The standalone Java test checks zero-row notifications, target validation, default no-op addon
compatibility, exception/linkage isolation and stable payload delivery to subsequent addons.
Changed API, notifier, tests and command compile with Java 21 against the published 5.1
artifact and Paper 1.21.11. For that focused check only, the command's core imports were
mapped in a temporary copy to the published artifact's shaded core namespace. The
upstream sources retain their original imports. The standalone test passed.

A complete Maven build was not performed because the local build environment does not
provide the existing `fo-plugin-core:1.4` dependency. Maintainer full-build validation
and a real-server reset test are required before release; no release binary is supplied.
