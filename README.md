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

Compile against the matching jar (`provided` in Maven, `compileOnly` in Gradle); install it locally if needed. Declare `FoDiscordBot` in your plugin metadata (`softdepend` for an optional integration).

```xml
<dependency>
  <groupId>me.foesio</groupId>
  <artifactId>FoDiscordBot</artifactId>
  <version>5.2</version>
  <scope>provided</scope>
</dependency>
```

```java
import me.foesio.foDiscordBot.api.FoDiscordBotApi;
import me.foesio.foDiscordBot.api.event.AccountLinkedEvent;

FoDiscordBotApi api = Bukkit.getServicesManager().load(FoDiscordBotApi.class);
if (api != null) api.findLinkByPlayer(player.getUniqueId());

@EventHandler
public void onAccountLinkedEvent(AccountLinkedEvent event) {
    Bukkit.getLogger().info(event.getClass().getSimpleName());
}
```

**API methods** (overloads grouped):

- `FoDiscordBotApi`: `findLinkByPlayer`, `findLinkByDiscordId`, `findBoosterReward`, `refreshSlashCommands`, `linkWithCode`, `unlinkByPlayer`, `unlinkByDiscordId`.
- `FoDiscordBotAddon`: `id`, `onRegister`, `onUnregister`, `onConfigReload`, `contributeCommands`, `handleSlashCommand`, `handleAutocomplete`, `handleButton`, `populateEditor`, `handleEditorClick`, `syncPlayerNow`, `syncOnlinePlayersToNetwork`.
- `DatabaseAction`: `execute`.

**Other API types:** `BoosterRewardSnapshot`, `CommandRefreshResult`, `LinkRequestResult`, `LinkedIdentity`, `UnlinkRequestResult`.

**Events:** `AccountLinkedEvent`, `AccountUnlinkedEvent`, `BoosterRewardResultEvent`, `DiscordBotReadyEvent`, `DiscordToMinecraftChatRelayEvent`, `LinkRewardResultEvent`, `MinecraftToDiscordChatRelayEvent`.

[Browse API source](src/main/java/me/foesio/foDiscordBot/api) for signatures and event details.

</details>
