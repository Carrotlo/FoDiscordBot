package me.foesio.foDiscordBot.service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.AdvancementEntryView;
import me.foesio.foDiscordBot.model.AdvancementProfileView;
import me.foesio.foDiscordBot.model.AdvancementTabView;
import org.bukkit.plugin.Plugin;

public final class FoAdvancementsHook {

    private final FoDiscordBot plugin;
    private final Map<MethodKey, Method> methodCache = new ConcurrentHashMap<>();

    public FoAdvancementsHook(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return api() != null;
    }

    public AdvancementProfileView snapshot(String gamemodeId, UUID playerUuid, String playerName) {
        Object api = api();
        if (api == null) {
            return null;
        }

        Object snapshot = invoke(api, "snapshot", new Class<?>[]{UUID.class, String.class}, playerUuid, playerName);
        if (snapshot == null) {
            return null;
        }

        return readProfile(gamemodeId, snapshot);
    }

    private Object api() {
        Plugin external = plugin.getServer().getPluginManager().getPlugin("FoAdvancements");
        if (external == null || !external.isEnabled()) {
            return null;
        }

        try {
            return invoke(external, "api", new Class<?>[0]);
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private AdvancementProfileView readProfile(String gamemodeId, Object snapshot) {
        List<AdvancementTabView> tabs = new ArrayList<>();
        for (Object tab : listValue(snapshot, "tabs")) {
            tabs.add(readTab(tab));
        }

        return new AdvancementProfileView(
                gamemodeId,
                uuidValue(snapshot, "playerUuid"),
                stringValue(snapshot, "playerName"),
                stringValue(snapshot, "pluginVersion"),
                intValue(snapshot, "points"),
                intValue(snapshot, "completed"),
                intValue(snapshot, "total"),
                List.copyOf(tabs)
        );
    }

    private AdvancementTabView readTab(Object snapshot) {
        List<AdvancementEntryView> advancements = new ArrayList<>();
        for (Object advancement : listValue(snapshot, "advancements")) {
            advancements.add(readAdvancement(advancement));
        }

        return new AdvancementTabView(
                stringValue(snapshot, "id"),
                stringValue(snapshot, "title"),
                stringListValue(snapshot, "description"),
                stringValue(snapshot, "icon"),
                stringValue(snapshot, "background"),
                intValue(snapshot, "completed"),
                intValue(snapshot, "total"),
                List.copyOf(advancements)
        );
    }

    private AdvancementEntryView readAdvancement(Object snapshot) {
        return new AdvancementEntryView(
                stringValue(snapshot, "id"),
                stringValue(snapshot, "fullId"),
                stringValue(snapshot, "title"),
                stringListValue(snapshot, "description"),
                stringValue(snapshot, "icon"),
                stringValue(snapshot, "frame"),
                intValue(snapshot, "current"),
                intValue(snapshot, "required"),
                booleanValue(snapshot, "completed"),
                booleanValue(snapshot, "visible"),
                booleanValue(snapshot, "hidden"),
                intValue(snapshot, "points")
        );
    }

    private Object value(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = methodCache.computeIfAbsent(
                    new MethodKey(target.getClass(), methodName, List.copyOf(Arrays.asList(parameterTypes))),
                    key -> {
                        try {
                            return key.targetClass().getMethod(key.methodName(), key.parameterTypes().toArray(Class<?>[]::new));
                        } catch (ReflectiveOperationException exception) {
                            throw new IllegalStateException("Could not read FoAdvancements API method " + key.methodName(), exception);
                        }
                    }
            );
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | IllegalStateException exception) {
            throw new IllegalStateException("Could not read FoAdvancements API method " + methodName, exception);
        }
    }

    private String stringValue(Object target, String methodName) {
        Object value = value(target, methodName);
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object target, String methodName) {
        Object value = value(target, methodName);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean booleanValue(Object target, String methodName) {
        Object value = value(target, methodName);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private UUID uuidValue(Object target, String methodName) {
        Object value = value(target, methodName);
        return value instanceof UUID uuid ? uuid : new UUID(0L, 0L);
    }

    private List<?> listValue(Object target, String methodName) {
        Object value = value(target, methodName);
        return value instanceof List<?> list ? list : List.of();
    }

    private List<String> stringListValue(Object target, String methodName) {
        List<String> values = new ArrayList<>();
        for (Object value : listValue(target, methodName)) {
            values.add(String.valueOf(value));
        }
        return List.copyOf(values);
    }

    private record MethodKey(Class<?> targetClass, String methodName, List<Class<?>> parameterTypes) {
    }
}
