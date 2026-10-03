package ru.nagumo.verity.client.hud;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Тестовая команда переключения состояния HUD-виджета компаньона (раздел 11 ТЗ,
 * шаг 2: "визуальные состояния... без реального инференса — заглушка вместо ответа").
 *
 * Отдельный литерал {@code /nagumohud}, а не {@code /nagumo hud ...}: литерал
 * "nagumo" целиком занят серверным деревом команд (NagumoCommands) — если добавить
 * тут ещё один клиентский литерал "nagumo", повторится уже однажды словленный баг
 * (см. память проекта build-env-mappings.md): клиентский Brigadier не находит чужие
 * подветки дерева и гасит команду вместо того, чтобы пропустить её на сервер. Эта
 * команда не обращается к серверу вообще (чисто локальное переключение), поэтому
 * отдельный литерал безопаснее и не требует вмешательства в серверное дерево.
 */
public final class CompanionHudTestCommand {

    private static final String ARG_STATE = "state";

    private CompanionHudTestCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("nagumohud")
                        .then(ClientCommands.argument(ARG_STATE, StringArgumentType.word())
                                .executes(ctx -> setState(ctx.getSource(),
                                        StringArgumentType.getString(ctx, ARG_STATE))))));
    }

    private static int setState(FabricClientCommandSource source, String stateArg) {
        CompanionHudState state;
        try {
            state = CompanionHudState.valueOf(stateArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String available = Arrays.stream(CompanionHudState.values())
                    .map(s -> s.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
            source.sendError(Component.literal("Неизвестное состояние. Доступные: " + available));
            return 0;
        }
        CompanionHud.setState(state);
        source.sendFeedback(Component.literal("HUD-состояние компаньона: " + state.name().toLowerCase(Locale.ROOT)));
        return 1;
    }
}
