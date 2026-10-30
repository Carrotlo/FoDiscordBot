# FoDiscordBot

[![Modrinth](https://img.shields.io/badge/Download-Modrinth-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/fodiscordbot) [![Versions](https://img.shields.io/badge/Supported_versions-Modrinth-1bd96a)](https://modrinth.com/plugin/fodiscordbot/versions) [![Discord](https://img.shields.io/badge/Support-Discord-5865f2?logo=discord&logoColor=white)](https://discord.gg/kEF77QCfu7)

Discord integration for linking, chat, profiles, rewards, and leaderboards.

*Developed by Carrotio.*

## Features

- Connects a Minecraft server to a configurable Discord bot.
- Supports account linking and unlinking with generated link codes.
- Provides chat bridging, profiles, rewards, rank synchronisation, and leaderboards.
- Includes administration controls and player-facing Discord commands.
- Exposes an add-on interface for separate integration plugins.

## Download and dependencies

Download the latest compatible version from [Modrinth](https://modrinth.com/plugin/fodiscordbot). Check the [versions page](https://modrinth.com/plugin/fodiscordbot/versions) for supported Minecraft versions and server platforms.

- **Required:** None
- **Optional integrations:** `PlaceholderAPI`, `SkinsRestorer`

## Support

For help or issue reports, join the [Carrotio Discord server](https://discord.gg/kEF77QCfu7).

<details>
<summary><strong>Commands and permissions</strong></summary>

### Commands

- `/fodiscordbotadmin <version|reload|editor|resetrewards|resetleaderboards>` — Manage FoDiscordBot Aliases: `/discordbotadmin`, `/fodiscord`, `/fodiscordbot`.
- `/link` — Generate a Discord link code
- `/unlink` — Unlink your Discord account
- `/discord` — Show the Discord invite link

### Permissions

- `fodiscordbot.admin` — Reload and administrate FoDiscordBot Default: `op`.
- `fodiscord.admin` — Legacy FoDiscordBot admin permission Default: `op`.
- `fodiscord.use` — Use FoDiscordBot player commands Default: `true`.

</details>

<details>
<summary><strong>PlaceholderAPI</strong></summary>

This plugin does not register public PlaceholderAPI placeholders.

</details>

<details>
<summary><strong>Configuration</strong></summary>

- `config.yml` — Main behavior and feature settings.
- `messages.yml` — Player-facing messages and interface text.
- `sounds.yml` — Configurable interaction sounds.

Main config areas: `discord`, `server-ip`, `network`, `chat-bridge`, `linking`, `booster`, `rank-sync`, `profile`, `leaderboards`.

</details>

<details>
<summary><strong>Developer API</strong></summary>

FoDiscordBot provides a Bukkit service for account operations and Bukkit events for integration hooks. Compile against the matching FoDiscordBot jar; do not bundle another copy of its classes into a consumer plugin. The provider uses Java 21 and `plugin.yml`. The current checkout declares `me.foesio:FoDiscordBot:5.2` in `pom.xml`; no public Maven repository for this artifact is promised. Install the built jar locally or use a private repository that contains it.

```xml
<dependency>
  <groupId>me.foesio</groupId>
  <artifactId>FoDiscordBot</artifactId>
  <version>5.2</version>
  <scope>provided</scope>
</dependency>
```

For a required integration, put `depend: [FoDiscordBot]` in the consumer's `plugin.yml`. For an optional integration, use `softdepend: [FoDiscordBot]`, check for the plugin before loading a separate integration class that references FoDiscordBot types, and handle an absent service. Reacquire the service after a provider disable/enable cycle. FoDiscordBot registers it after its database is initialized and unregisters it on disable.

```java
RegisteredServiceProvider<FoDiscordBotApi> registration =
        Bukkit.getServicesManager().getRegistration(FoDiscordBotApi.class);
if (registration != null) {
    FoDiscordBotApi api = registration.getProvider();
    api.findLinkByDiscordId(discordUserId).thenAccept(link ->
            link.ifPresent(identity -> getLogger().info(identity.playerUuid().toString())));
}
```

Import `org.bukkit.plugin.RegisteredServiceProvider`, `org.bukkit.Bukkit`, and `me.foesio.foDiscordBot.api.FoDiscordBotApi`. Query and action methods return `CompletableFuture` and perform database work on an async worker. Do not block a server or region thread waiting for a future. Completion callbacks may run off-thread; schedule Bukkit player/world work on the correct player or region scheduler. Invalid arguments and database failures complete exceptionally. Cached service references fail or return unavailable after FoDiscordBot disables.

### Service methods

| Method | Meaning |
| --- | --- |
| `findLinkByPlayer(UUID)` / `findLinkByDiscordId(String)` | Return a committed, immutable `LinkedIdentity`, or `Optional.empty()` if not linked. Pending codes are excluded. |
| `findBoosterReward(UUID, String)` | Return local reward state for a gamemode. A missing row returns an inactive snapshot. `currentReward` and `linkBroken` describe stored state, not a live Discord role or current preference. |
| `linkWithCode(code, discordUserId, username, displayName)` | Consume a normal one-time code through the same cooldown, validation, persistence, role, and reward path as Discord linking. The caller must have verified the Discord identity; the code alone does not prove ownership of the supplied Discord ID. `LinkRequestResult` reports success, cooldown, invalid/expired code, or an existing link. No direct UUID-to-Discord linking is offered. |
| `unlinkByPlayer(UUID)` / `unlinkByDiscordId(String)` | Process unlink rewards and booster state, commit removal, and request Discord role removal. `UNLINKED` means the database unlink committed; Discord role removal is asynchronous. `NOT_LINKED` means no committed link was found. |
| `refreshSlashCommands()` | Update the current bot's slash commands without reconnecting. Returns `UPDATED` after Discord accepts the update, `UNAVAILABLE` when the bot is not connected, or `FAILED` for a rejected update. Client-side propagation may take longer. |

Existing `FoDiscordBotAddon` callbacks remain available for add-on commands, interactions, editor entries, and network sync. The existing raw database connection API is intended for add-on-owned tables, not direct mutation of FoDiscordBot's link or reward state.

### Events

Register listeners through Bukkit's normal `PluginManager.registerEvents`. Events are local to the node that performs the operation; a shared MySQL database does not broadcast events to other servers. Payload snapshots are immutable except for the relay-only `message` property.

| Event | Timing and effect |
| --- | --- |
| `AccountLinkedEvent` | Async, after a successful link commit. Includes the committed identity and `AccountChangeCause`. No event for invalid, expired, cooldown, or already-linked attempts. Rewards and Discord role updates may still be pending. |
| `AccountUnlinkedEvent` | Async, after a successful unlink commit. Includes the former identity and cause. No event for `NOT_LINKED`. Unlink reward work runs before the commit; Discord role removal may still be pending. |
| `LinkRewardResultEvent` | Async after a link reward attempt resolves as `GRANTED` or `FAILED`. No event for not-linked or already-claimed checks. `FAILED` can mean partial command progress; listeners should not retry commands themselves. |
| `BoosterRewardResultEvent` | Async after local booster reward processing resolves as `GRANTED`, `REMOVED`, or `FAILED`. No event for skipped checks. This is not a live Discord booster-role event. |
| `DiscordBotReadyEvent` | Async after each successful bot start or explicit restart, once command registration and relay queue startup complete. It does not promise an event for every JDA auto-reconnect. |
| `MinecraftToDiscordChatRelayEvent` | Fired before FoDiscordBot forwards Minecraft chat to Discord or queues it for network delivery. Its async flag matches the source chat call. Network queue delivery fires the event again on the primary Discord node, asynchronously, so a listener there can also veto delivery. Cancelling stops only relay delivery, not the original Minecraft message. Listeners may replace the relay text. |
| `DiscordToMinecraftChatRelayEvent` | Async on the Discord callback thread, after sanitizing Discord content and before Minecraft broadcast. Cancelling stops only the Minecraft relay, not the original Discord message. Listeners may replace the relay text, which is sanitized again before broadcast. |

Do not access Bukkit player or world state directly from asynchronous listeners. A relay event's text can be changed only during its dispatch; treat its IDs as stable values, not mutable Bukkit objects. No event is cancellable except the two relay events.

### Event listener example

Inside a Bukkit `Listener` registered by your plugin (import this event class, `org.bukkit.event.EventHandler`, and `org.bukkit.Bukkit` where used):

```java
@EventHandler
public void onAccountLinked(AccountLinkedEvent event) {
    Bukkit.getLogger().info("Account linked via " + event.cause());
}
```

### Complete public API method index

Signatures use short type names; import Fo types from this plugin's `api` package and Bukkit types from Bukkit.

#### `FoDiscordBotApi`

```java
CompletableFuture<Optional<LinkedIdentity>> findLinkByPlayer(UUID);
CompletableFuture<Optional<LinkedIdentity>> findLinkByDiscordId(String);
CompletableFuture<BoosterRewardSnapshot> findBoosterReward(UUID, String);
CompletableFuture<CommandRefreshResult> refreshSlashCommands();
CompletableFuture<LinkRequestResult> linkWithCode(String, String, String, String);
CompletableFuture<UnlinkRequestResult> unlinkByPlayer(UUID);
CompletableFuture<UnlinkRequestResult> unlinkByDiscordId(String);
```

#### `FoDiscordBotAddon`

```java
String id();
void onRegister(me.foesio.foDiscordBot.FoDiscordBot);
void onUnregister();
void onConfigReload();
void contributeCommands(List<net.dv8tion.jda.api.interactions.commands.build.CommandData>);
boolean handleSlashCommand(net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent);
boolean handleAutocomplete(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent);
boolean handleButton(net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent);
void populateEditor(String, Inventory);
boolean handleEditorClick(String, Player, int);
CompletableFuture<Void> syncPlayerNow(Player);
CompletableFuture<Void> syncOnlinePlayersToNetwork();
```

#### `DatabaseAction`

```java
T execute(Connection) throws SQLException;
```

**Other public contract types:** `BoosterRewardSnapshot`, `CommandRefreshResult`, `LinkRequestResult`, `LinkedIdentity`, `UnlinkRequestResult`.

### Complete Bukkit event index

- `AccountLinkedEvent`.
- `AccountUnlinkedEvent`.
- `BoosterRewardResultEvent`.
- `DiscordBotReadyEvent`.
- `DiscordToMinecraftChatRelayEvent`.
- `LinkRewardResultEvent`.
- `MinecraftToDiscordChatRelayEvent`.

</details>
