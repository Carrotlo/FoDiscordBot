package me.foesio.foDiscordBot.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.foesio.foDiscordBot.FoDiscordBot;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Extension point for optional FoDiscordBot integrations.
 *
 * <p>Addons are separate Bukkit plugins and may contribute Discord commands,
 * interactions, network sync work, and editor items without becoming part of
 * the main plugin's feature set.</p>
 */
public interface FoDiscordBotAddon {

    String id();

    default void onRegister(FoDiscordBot plugin) {
    }

    default void onUnregister() {
    }

    default void onConfigReload() {
    }

    /**
     * Called on the reset command's worker after the host repository reset succeeds,
     * including successful resets that affect zero rows. Only addons on the executing
     * server receive this notification. No Bukkit main-thread access is allowed here.
     * Keep callbacks short and enqueue longer work on an addon-owned bounded executor.
     *
     * <p>Delivery is best-effort, not durable or transactional with addon storage.
     * A crash after commit can lose the notification. Direct repository calls and
     * leaderboard resets do not emit it. Repeated commands are separate operations;
     * consumers must not interpret every notification as a new network season.</p>
     */
    default void onRewardsReset(RewardReset reset) {
    }

    default void contributeCommands(List<CommandData> commands) {
    }

    default boolean handleSlashCommand(SlashCommandInteractionEvent event) {
        return false;
    }

    default boolean handleAutocomplete(CommandAutoCompleteInteractionEvent event) {
        return false;
    }

    default boolean handleButton(ButtonInteractionEvent event) {
        return false;
    }

    default void populateEditor(String page, Inventory inventory) {
    }

    default boolean handleEditorClick(String page, Player player, int slot) {
        return false;
    }

    default CompletableFuture<Void> syncPlayerNow(Player player) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> syncOnlinePlayersToNetwork() {
        return CompletableFuture.completedFuture(null);
    }
}
