package me.foesio.foDiscordBot.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.foesio.core.command.FoAdminArguments;
import me.foesio.core.command.FoAdminCommandContext;
import me.foesio.core.command.FoAdminMessages;
import me.foesio.core.command.FoAdminSubcommand;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.message.FoMessageService;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.LinkedAccount;
import me.foesio.foDiscordBot.service.LinkRepository;
import me.foesio.foDiscordBot.util.BukkitFutures;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class FoDiscordCommand implements Listener {

    private static final int CONFIRM_INVENTORY_SIZE = 27;
    private static final int CANCEL_SLOT = 11;
    private static final int CONTINUE_SLOT = 15;
    private final FoDiscordBot plugin;

    public FoDiscordCommand(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    public FoAdminMessages adminMessages() {
        return FoAdminMessages.builder()
                .generalNoPermission("ingame.no-permission", FoMessageService.missingMessageFallback("ingame.no-permission"))
                .generalPlayerOnly("ingame.players-only", FoMessageService.missingMessageFallback("ingame.players-only"))
                .usage("ingame.reload.usage", FoMessageService.missingMessageFallback("ingame.reload.usage"))
                .commandMissing("ingame.reload.error", FoMessageService.missingMessageFallback("ingame.reload.error"))
                .commandFailed("ingame.reload.error", FoMessageService.missingMessageFallback("ingame.reload.error"))
                .reloadSuccess("ingame.reload.success", FoMessageService.missingMessageFallback("ingame.reload.success"))
                .reloadFailed("ingame.reload.error", FoMessageService.missingMessageFallback("ingame.reload.error"))
                .editorOpened("ingame.editor.open", FoMessageService.missingMessageFallback("ingame.editor.open"))
                .versionCurrent("ingame.version.current", FoMessageService.missingMessageFallback("ingame.version.current"))
                .build();
    }

    public FoAdminSubcommand versionSubcommand() {
        return FoAdminSubcommand.builder("version", context -> {
            handleVersion(context.sender());
            return true;
        }).usage("version").build();
    }

    public FoAdminSubcommand reloadSubcommand() {
        return FoAdminSubcommand.builder("reload", context -> {
            plugin.messages().sendConfigured(context.sender(), "ingame.loading");
            boolean success = plugin.reloadPlugin();
            plugin.messages().sendConfigured(context.sender(), success ? "ingame.reload.success" : "ingame.reload.error");
            return true;
        }).usage("reload").build();
    }

    public FoAdminSubcommand editorSubcommand() {
        return FoAdminSubcommand.builder("editor", context -> {
            plugin.getConfigEditorService().openEditor(context.playerOrNull());
            return true;
        }).usage("editor").playerOnly().build();
    }

    public FoAdminSubcommand resetRewardsSubcommand() {
        return FoAdminSubcommand.builder("resetrewards", context -> {
                    handleResetRewards(context.sender(), context.args());
                    return true;
                })
                .aliases("resetreward", "reset-rewards", "reset")
                .usage("resetrewards <linked|booster> <player|discord|all> [gamemode]")
                .tabCompleter(this::completeResetRewards)
                .build();
    }

    public FoAdminSubcommand resetLeaderboardsSubcommand() {
        return FoAdminSubcommand.builder("resetleaderboards", context -> {
                    handleResetLeaderboards(context.sender(), context.args());
                    return true;
                })
                .aliases("resetleaderboard", "reset-leaderboards", "reset-leaderboard")
                .usage("resetleaderboards <gamemode|all> [board|all]")
                .tabCompleter(this::completeResetLeaderboards)
                .build();
    }

    private List<String> completeResetRewards(FoAdminCommandContext context) {
        String[] args = context.args();
        if (args.length == 2) {
            return FoAdminArguments.completeOptions(List.of("linked", "booster"), context.arg(1));
        }
        if (args.length == 3) {
            List<String> targets = new java.util.ArrayList<>();
            targets.add("all");
            for (Player player : Bukkit.getOnlinePlayers()) {
                targets.add(player.getName());
            }
            return FoAdminArguments.completeOptions(targets, context.arg(2));
        }
        if (args.length == 4 && plugin.getPluginConfig().networkEnabled()) {
            return FoAdminArguments.completeOptions(List.of(plugin.getPluginConfig().normalizedGamemodeId()), context.arg(3));
        }
        return List.of();
    }

    private List<String> completeResetLeaderboards(FoAdminCommandContext context) {
        String[] args = context.args();
        if (args.length == 2) {
            return FoAdminArguments.completeOptions(
                    List.of("all", plugin.getPluginConfig().normalizedGamemodeId()),
                    context.arg(1)
            );
        }
        if (args.length == 3) {
            List<String> boards = new java.util.ArrayList<>();
            boards.add("all");
            boards.addAll(plugin.getLeaderboardService().configuredBoardAliases());
            return FoAdminArguments.completeOptions(boards, context.arg(2));
        }
        return List.of();
    }

    private void handleVersion(CommandSender sender) {
        plugin.messages().send(sender, "ingame.version.author",
                FoMessageService.missingMessageFallback("ingame.version.author"), Map.of("author", "Carrotio"));
        plugin.messages().send(sender, "ingame.version.current",
                FoMessageService.missingMessageFallback("ingame.version.current"), Map.of(
                "version", plugin.getDescription().getVersion()
        ));
        plugin.getUpdateNoticeService().checkAndSendVersion(sender);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ResetConfirmationHolder holder)) {
            if (event.getInventory().getHolder() instanceof LeaderboardResetConfirmationHolder leaderboardHolder) {
                handleLeaderboardResetConfirmationClick(event, player, leaderboardHolder);
            }
            return;
        }
        if (!holder.ownerUuid().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            return;
        }

        if (slot == CANCEL_SLOT) {
            player.closeInventory();
            plugin.messages().sendConfigured(player, "ingame.reset-rewards.cancelled");
            return;
        }

        if (slot == CONTINUE_SLOT) {
            ResetRequest request = holder.request();
            player.closeInventory();
            executeReset(player, request);
        }
    }

    private void handleLeaderboardResetConfirmationClick(
            InventoryClickEvent event,
            Player player,
            LeaderboardResetConfirmationHolder holder
    ) {
        if (!holder.ownerUuid().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            return;
        }

        if (slot == CANCEL_SLOT) {
            player.closeInventory();
            plugin.messages().sendConfigured(player, "ingame.reset-leaderboards.cancelled");
            return;
        }

        if (slot == CONTINUE_SLOT) {
            LeaderboardResetRequest request = holder.request();
            player.closeInventory();
            executeLeaderboardReset(player, request);
        }
    }

    private void handleResetRewards(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            plugin.messages().sendConfigured(sender, "ingame.reset-rewards.usage");
            return;
        }

        RewardKind kind = RewardKind.from(args[1]);
        if (kind == null) {
            plugin.messages().sendConfigured(sender, "ingame.reset-rewards.usage");
            return;
        }

        String targetInput = args[2].trim();
        if (targetInput.isBlank()) {
            plugin.messages().sendConfigured(sender, "ingame.reset-rewards.usage");
            return;
        }

        String gamemodeId = resolveGamemode(args);
        if ("all".equalsIgnoreCase(targetInput)) {
            if (!(sender instanceof Player player)) {
                plugin.messages().sendConfigured(sender, "ingame.reset-rewards.players-only-all");
                return;
            }

            openResetConfirmation(player, new ResetRequest(kind, true, "all", null, "all players", gamemodeId));
            return;
        }

        Player onlineTarget = findOnlinePlayer(targetInput);
        ResetRequest request = new ResetRequest(
                kind,
                false,
                targetInput,
                onlineTarget != null ? onlineTarget.getUniqueId() : null,
                onlineTarget != null ? onlineTarget.getName() : targetInput,
                gamemodeId
        );
        executeReset(sender, request);
    }

    private void executeReset(CommandSender sender, ResetRequest request) {
        plugin.messages().sendConfigured(sender, "ingame.loading");
        BukkitFutures.supplyAsync(plugin, () -> runReset(request)).whenComplete((result, throwable) ->
                plugin.getCore().scheduler().runGlobal(() -> {
                    if (throwable != null) {
                        plugin.logWarning("Failed to reset reward claims: " + throwable.getMessage());
                        plugin.messages().sendConfigured(sender, "ingame.reset-rewards.error");
                        return;
                    }

                    if (result.status() == ResetStatus.NOT_FOUND) {
                        plugin.messages().send(sender, "ingame.reset-rewards.unknown-player",
                                FoMessageService.missingMessageFallback("ingame.reset-rewards.unknown-player"), Map.of(
                                "player", request.targetName()
                        ));
                        return;
                    }

                    plugin.messages().send(sender, "ingame.reset-rewards.success",
                            FoMessageService.missingMessageFallback("ingame.reset-rewards.success"), Map.of(
                            "type", request.kind().displayName(),
                            "target", result.targetName(),
                            "gamemode", request.gamemodeId(),
                            "rows", String.valueOf(result.changedRows())
                    ));
                }));
    }

    private ResetResult runReset(ResetRequest request) {
        try {
            LinkRepository repository = plugin.getLinkService().repository();
            if (request.all()) {
                int changedRows = switch (request.kind()) {
                    case LINKED -> repository.resetAllGamemodeRewardClaims(request.gamemodeId());
                    case BOOSTER -> repository.resetAllBoosterRewardClaims(request.gamemodeId());
                };
                return new ResetResult(ResetStatus.SUCCESS, changedRows, request.targetName());
            }

            if (request.kind() == RewardKind.LINKED) {
                String discordUserId = parseDiscordId(request.targetInput());
                if (discordUserId != null) {
                    int changedRows = repository.resetGamemodeRewardClaimsByDiscordId(discordUserId, request.gamemodeId());
                    return new ResetResult(ResetStatus.SUCCESS, changedRows, discordUserId);
                }
            }

            ResolvedTarget target = resolveTarget(repository, request);
            if (target == null) {
                return new ResetResult(ResetStatus.NOT_FOUND, 0, request.targetName());
            }

            int changedRows = switch (request.kind()) {
                case LINKED -> repository.resetGamemodeRewardClaims(target.playerUuid(), request.gamemodeId());
                case BOOSTER -> repository.resetBoosterRewardClaims(target.playerUuid(), request.gamemodeId());
            };
            return new ResetResult(ResetStatus.SUCCESS, changedRows, target.playerName());
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private ResolvedTarget resolveTarget(LinkRepository repository, ResetRequest request) throws Exception {
        if (request.targetUuid() != null) {
            return new ResolvedTarget(request.targetUuid(), request.targetName());
        }

        UUID parsedUuid = parseUuid(request.targetInput());
        if (parsedUuid != null) {
            LinkedAccount account = repository.findByPlayerUuid(parsedUuid).orElse(null);
            return new ResolvedTarget(parsedUuid, account != null ? account.playerName() : parsedUuid.toString());
        }

        LinkedAccount account = repository.findByPlayerName(request.targetInput()).orElse(null);
        if (account == null) {
            return null;
        }
        return new ResolvedTarget(account.playerUuid(), account.playerName());
    }

    private void openResetConfirmation(Player player, ResetRequest request) {
        ResetConfirmationHolder holder = new ResetConfirmationHolder(player.getUniqueId(), request);
        Inventory inventory = Bukkit.createInventory(holder, CONFIRM_INVENTORY_SIZE, GuiTitles.format("Confirm Reward Reset"));
        holder.setInventory(inventory);

        ItemStack filler = EditorItemFactory.filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(CANCEL_SLOT, EditorItemFactory.cancel());
        inventory.setItem(CONTINUE_SLOT, EditorItemFactory.confirm());

        player.openInventory(inventory);
        plugin.messages().sendConfigured(player, "ingame.reset-rewards.confirm-open");
    }

    private void handleResetLeaderboards(CommandSender sender, String[] args) {
        if (!plugin.getPluginConfig().networkEnabled()) {
            plugin.messages().sendConfigured(sender, "ingame.reset-leaderboards.network-disabled");
            return;
        }

        if (args.length < 2 || args.length > 3) {
            plugin.messages().sendConfigured(sender, "ingame.reset-leaderboards.usage");
            return;
        }

        String gamemodeInput = args[1].trim();
        String boardInput = args.length >= 3 ? args[2].trim() : "all";
        if (gamemodeInput.isBlank() || boardInput.isBlank()) {
            plugin.messages().sendConfigured(sender, "ingame.reset-leaderboards.usage");
            return;
        }

        LeaderboardResetRequest request = new LeaderboardResetRequest(
                normalizeResetScope(gamemodeInput),
                "all".equalsIgnoreCase(gamemodeInput),
                normalizeResetScope(boardInput),
                "all".equalsIgnoreCase(boardInput)
        );

        if (request.requiresConfirmation()) {
            if (!(sender instanceof Player player)) {
                plugin.messages().sendConfigured(sender, "ingame.reset-leaderboards.players-only-all");
                return;
            }

            openLeaderboardResetConfirmation(player, request);
            return;
        }

        executeLeaderboardReset(sender, request);
    }

    private void executeLeaderboardReset(CommandSender sender, LeaderboardResetRequest request) {
        plugin.messages().sendConfigured(sender, "ingame.loading");
        BukkitFutures.supplyAsync(plugin, () -> runLeaderboardReset(request)).whenComplete((rows, throwable) ->
                plugin.getCore().scheduler().runGlobal(() -> {
                    if (throwable != null) {
                        plugin.logWarning("Failed to reset leaderboard snapshots: " + throwable.getMessage());
                        plugin.messages().sendConfigured(sender, "ingame.reset-leaderboards.error");
                        return;
                    }

                    plugin.messages().send(sender, "ingame.reset-leaderboards.success",
                            FoMessageService.missingMessageFallback("ingame.reset-leaderboards.success"), Map.of(
                            "scope", describeLeaderboardResetScope(request),
                            "rows", String.valueOf(rows)
                    ));
                }));
    }

    private int runLeaderboardReset(LeaderboardResetRequest request) {
        try {
            return plugin.getLinkService().repository().resetLeaderboardSnapshots(
                    request.gamemodeId(),
                    request.boardAlias()
            );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void openLeaderboardResetConfirmation(Player player, LeaderboardResetRequest request) {
        LeaderboardResetConfirmationHolder holder = new LeaderboardResetConfirmationHolder(player.getUniqueId(), request);
        Inventory inventory = Bukkit.createInventory(holder, CONFIRM_INVENTORY_SIZE, GuiTitles.format("Confirm Leaderboard Reset"));
        holder.setInventory(inventory);

        ItemStack filler = EditorItemFactory.filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(CANCEL_SLOT, EditorItemFactory.cancel());
        inventory.setItem(CONTINUE_SLOT, EditorItemFactory.confirm());

        player.openInventory(inventory);
        plugin.messages().sendConfigured(player, "ingame.reset-leaderboards.confirm-open");
    }

    private String normalizeResetScope(String input) {
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "all" : normalized;
    }

    private String describeLeaderboardResetScope(LeaderboardResetRequest request) {
        if (request.allGamemodes() && request.allBoards()) {
            return "all leaderboards";
        }
        if (request.allGamemodes()) {
            return request.boardAlias() + " in all gamemodes";
        }
        if (request.allBoards()) {
            return "all boards in " + request.gamemodeId();
        }
        return request.boardAlias() + " in " + request.gamemodeId();
    }

    private String resolveGamemode(String[] args) {
        if (plugin.getPluginConfig().networkEnabled() && args.length >= 4 && !args[3].isBlank()) {
            return args[3].trim().toLowerCase(Locale.ROOT);
        }
        return plugin.getPluginConfig().normalizedGamemodeId();
    }

    private Player findOnlinePlayer(String input) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(input)) {
                return player;
            }
        }
        return null;
    }

    private UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String parseDiscordId(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim();
        if (normalized.startsWith("<@") && normalized.endsWith(">")) {
            normalized = normalized.substring(2, normalized.length() - 1);
            if (normalized.startsWith("!")) {
                normalized = normalized.substring(1);
            }
        }
        if (normalized.length() < 5 || normalized.length() > 32) {
            return null;
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (!Character.isDigit(normalized.charAt(index))) {
                return null;
            }
        }
        return normalized;
    }

    private enum RewardKind {
        LINKED("linked"),
        BOOSTER("booster");

        private final String displayName;

        RewardKind(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }

        private static RewardKind from(String input) {
            if ("linked".equalsIgnoreCase(input) || "link".equalsIgnoreCase(input)) {
                return LINKED;
            }
            if ("booster".equalsIgnoreCase(input) || "boost".equalsIgnoreCase(input)) {
                return BOOSTER;
            }
            return null;
        }
    }

    private enum ResetStatus {
        SUCCESS,
        NOT_FOUND
    }

    private record ResetRequest(
            RewardKind kind,
            boolean all,
            String targetInput,
            UUID targetUuid,
            String targetName,
            String gamemodeId
    ) {
    }

    private record ResetResult(ResetStatus status, int changedRows, String targetName) {
    }

    private record ResolvedTarget(UUID playerUuid, String playerName) {
    }

    private record LeaderboardResetRequest(
            String gamemodeId,
            boolean allGamemodes,
            String boardAlias,
            boolean allBoards
    ) {

        private boolean requiresConfirmation() {
            return allGamemodes || allBoards;
        }
    }

    private static final class ResetConfirmationHolder implements InventoryHolder {

        private final UUID ownerUuid;
        private final ResetRequest request;
        private Inventory inventory;

        private ResetConfirmationHolder(UUID ownerUuid, ResetRequest request) {
            this.ownerUuid = ownerUuid;
            this.request = request;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private UUID ownerUuid() {
            return ownerUuid;
        }

        private ResetRequest request() {
            return request;
        }
    }

    private static final class LeaderboardResetConfirmationHolder implements InventoryHolder {

        private final UUID ownerUuid;
        private final LeaderboardResetRequest request;
        private Inventory inventory;

        private LeaderboardResetConfirmationHolder(UUID ownerUuid, LeaderboardResetRequest request) {
            this.ownerUuid = ownerUuid;
            this.request = request;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private UUID ownerUuid() {
            return ownerUuid;
        }

        private LeaderboardResetRequest request() {
            return request;
        }
    }
}
