package ru.nagumo.verity.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import ru.nagumo.verity.client.brain.CompanionMemory;
import ru.nagumo.verity.client.brain.ModelManager;
import ru.nagumo.verity.client.brain.ModelTier;
import ru.nagumo.verity.client.config.NagumoClientConfig;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Настройки клиента без GUI-экрана (раздел 11 ТЗ, шаг 3 — GUI/ModMenu отдельной
 * задачей позже). {@code /nagumosettings model} без аргумента печатает текущее
 * значение, {@code /nagumosettings model <off|mini|lite|standard>} меняет и
 * сохраняет конфиг ({@link NagumoClientConfig}).
 *
 * Откат 11.09.2026 (см. archive/server-side-voice-revert-2026-09-10): раньше
 * здесь же старт/стоп клиентского {@code VoicePipeline} по смене уровня — голосовой
 * пайплайн переехал обратно на сервер (SVC), у клиента больше нет своего захвата
 * микрофона, который нужно было бы включать/выключать.
 *
 * Раздел 11 ТЗ, шаг 5: с появлением {@code BrainEngine} {@code modelTier} и
 * {@code style} впервые влияют на что-то реальное — какую модель Ollama и какой
 * системный промпт использует клиентский brain на каждый {@code BrainRequestPayload}
 * от сервера. Но открытый разрыв из ТЗ (раздел 11) остаётся: {@code companionEnabled}
 * (то есть {@code modelTier == OFF}) НЕ останавливает сам захват микрофона на
 * сервере — сервер всё равно шлёт {@code BrainRequestPayload}, просто клиент отвечает
 * вежливым "компаньон выключен" вместо реального ответа LLM (см. {@code BrainEngine}).
 * Реальная остановка обработки требует отдельного пакета клиент→сервер — не сделано
 * в этом проходе, как и раньше.
 *
 * Отдельный литерал {@code /nagumosettings}, а не {@code /nagumo settings ...} —
 * та же причина, что и у {@code /nagumohud} (см. её javadoc): литерал "nagumo"
 * целиком занят серверным деревом команд, второй клиентский литерал с тем же
 * именем воспроизвёл бы уже словленный баг с Brigadier.
 *
 * *Решение (раздел 11 ТЗ, шаг 4, "реши сам"):* переименование — {@code name}
 * подкомандой ЗДЕСЬ же, рядом с {@code model}, а не отдельным литералом. И имя, и
 * уровень модели — настройки одного и того же компаньона, группировка под одним
 * {@code /nagumosettings} логичнее для игрока (одна команда — все настройки), а
 * отдельный литерал (как у {@code /nagumohud}) был нужен ТОЛЬКО чтобы не задеть
 * серверное дерево {@code /nagumo} — здесь мы и так уже не в этом дереве.
 *
 * *Решение (раздел 11 ТЗ, шаг 5, "реши сам"):* {@code style} — по той же логике,
 * третьей подкомандой рядом с {@code model}/{@code name}, а не отдельной командой.
 * Значения пресетов — те же 4 ключа, что уже существовали в СЕРВЕРНОМ
 * {@code /nagumo style} (раздел 8 ТЗ, {@code CompanionRegistry.STYLE_PRESETS}) —
 * переиспользованы, а не придуманы заново; сам серверный {@code /nagumo style} при
 * этом остаётся нетронутым легаси (он на реальный ответ brain теперь не влияет —
 * см. отчёт).
 */
public final class NagumoSettingsCommand {

    private static final String ARG_TIER = "tier";
    private static final String ARG_NAME = "name";
    private static final String ARG_STYLE = "style";

    private NagumoSettingsCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("nagumosettings")
                        .then(ClientCommands.literal("model")
                                .executes(ctx -> printCurrentTier(ctx.getSource()))
                                .then(ClientCommands.argument(ARG_TIER, StringArgumentType.word())
                                        .executes(ctx -> setTier(ctx.getSource(),
                                                StringArgumentType.getString(ctx, ARG_TIER)))))
                        .then(ClientCommands.literal("name")
                                .executes(ctx -> printCurrentName(ctx.getSource()))
                                // greedyString, не word(): word() читает через
                                // readUnquotedString (только ASCII) — на кириллице съедает
                                // 0 символов и падает (см. память проекта / серверный
                                // NagumoCommands.ARG_NAME с той же причиной).
                                .then(ClientCommands.argument(ARG_NAME, StringArgumentType.greedyString())
                                        .executes(ctx -> setName(ctx.getSource(),
                                                StringArgumentType.getString(ctx, ARG_NAME)))))
                        .then(ClientCommands.literal("style")
                                .executes(ctx -> printCurrentStyle(ctx.getSource()))
                                // word() тут ок — пресеты (friendly/ironic/business/reserved)
                                // только ASCII, в отличие от name (см. выше).
                                .then(ClientCommands.argument(ARG_STYLE, StringArgumentType.word())
                                        .executes(ctx -> setStyle(ctx.getSource(),
                                                StringArgumentType.getString(ctx, ARG_STYLE)))))
                        // Долгосрочная память (обсуждение 15.09.2026,
                        // см. CompanionMemory) — тоже здесь, а не в серверном /nagumo:
                        // память целиком клиентские данные (CompanionMemory), та же причина
                        // что и у остальных подкоманд этого класса (см. javadoc выше).
                        .then(ClientCommands.literal("memory")
                                .executes(ctx -> printMemory(ctx.getSource())))
                        .then(ClientCommands.literal("forget")
                                .executes(ctx -> forgetMemory(ctx.getSource())))));
    }

    private static int printCurrentTier(FabricClientCommandSource source) {
        NagumoClientConfig config = NagumoClientConfig.get();
        source.sendFeedback(Component.literal("Текущий уровень модели: "
                + config.modelTier.name().toLowerCase(Locale.ROOT)
                + " (компаньон " + (config.companionEnabled ? "включён" : "выключен") + ")"));
        return 1;
    }

    private static int setTier(FabricClientCommandSource source, String tierArg) {
        ModelTier tier;
        try {
            tier = ModelTier.valueOf(tierArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String available = Arrays.stream(ModelTier.values())
                    .map(t -> t.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
            source.sendError(Component.literal("Неизвестный уровень. Доступные: " + available));
            return 0;
        }

        NagumoClientConfig.get().setModelTier(tier);
        // ensureModelAvailable реально качает веса через Ollama (может занять минуты) —
        // асинхронно, чтобы не подвесить командный поток клиента на время скачивания.
        ModelManager.ensureModelAvailableAsync(tier);

        String feedback = tier == ModelTier.OFF
                ? "Компаньон выключен."
                : "Уровень модели: " + tier.name().toLowerCase(Locale.ROOT)
                        + " (модель Ollama при необходимости скачается в фоне — см. лог клиента).";
        source.sendFeedback(Component.literal(feedback));
        return 1;
    }

    private static int printCurrentName(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Текущее имя компаньона: " + NagumoClientConfig.get().companionName));
        return 1;
    }

    private static int setName(FabricClientCommandSource source, String newName) {
        String error = NagumoClientConfig.get().rename(newName);
        if (error != null) {
            source.sendError(Component.literal(error));
            return 0;
        }
        source.sendFeedback(Component.literal("Теперь твоего компаньона зовут " + newName + "."));
        return 1;
    }

    private static int printCurrentStyle(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Текущий характер компаньона: " + NagumoClientConfig.get().style));
        return 1;
    }

    private static int setStyle(FabricClientCommandSource source, String newStyle) {
        String error = NagumoClientConfig.get().setStyle(newStyle);
        if (error != null) {
            source.sendError(Component.literal(error));
            return 0;
        }
        source.sendFeedback(Component.literal("Характер компаньона: " + newStyle + "."));
        return 1;
    }

    private static int printMemory(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Память о тебе на этом сервере: "
                + CompanionMemory.memorySummaryForDisplay()));
        return 1;
    }

    private static int forgetMemory(FabricClientCommandSource source) {
        CompanionMemory.forgetCurrentServer();
        source.sendFeedback(Component.literal("Забыл всё, что помнил о тебе на этом сервере."));
        return 1;
    }
}
