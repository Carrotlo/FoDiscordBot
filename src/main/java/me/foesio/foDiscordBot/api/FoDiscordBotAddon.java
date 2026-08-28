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
