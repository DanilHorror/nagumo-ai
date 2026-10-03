package ru.nagumo.verity.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.network.chat.Component;
import ru.nagumo.verity.client.brain.BrainEngine;
import ru.nagumo.verity.client.brain.CompanionMemory;
import ru.nagumo.verity.client.brain.GameKnowledgeIndex;
import ru.nagumo.verity.client.command.NagumoSettingsCommand;
import ru.nagumo.verity.client.hud.CompanionHud;
import ru.nagumo.verity.client.hud.CompanionHudState;
import ru.nagumo.verity.client.hud.CompanionHudTestCommand;
import ru.nagumo.verity.common.network.BrainReplyPayload;
import ru.nagumo.verity.common.network.BrainRequestPayload;
import ru.nagumo.verity.common.network.DespawnEntityPayload;
import ru.nagumo.verity.common.network.MoveEntityToPayload;
import ru.nagumo.verity.common.network.NagumoPayloads;
import ru.nagumo.verity.common.network.PlayAnimationPayload;
import ru.nagumo.verity.common.network.PlayAudioPayload;
import ru.nagumo.verity.common.network.RecipeCompendiumPayload;
import ru.nagumo.verity.common.network.ReportInstalledModsPayload;
import ru.nagumo.verity.common.network.SetCompanionHudStatePayload;
import ru.nagumo.verity.common.network.ShowTextPayload;
import ru.nagumo.verity.common.network.SpawnEntityPayload;

import java.util.Locale;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа клиентского мода "НАГУМО ИИ" (распространяется игрокам, раздел 3 ТЗ).
 *
 * Ревизия 10.09.2026 (см. ТЗ): компаньон больше не сущность в мире — вместо
 * рендера/анимаций GeckoLib теперь HUD-виджет ({@link CompanionHud}, раздел 7 ТЗ).
 * Оставшиеся entity-пакеты (SpawnEntity/DespawnEntity/PlayAnimation/PlayAudio/
 * MoveEntityTo) пока только логируются заглушкой, как и раньше, — реального
 * адресата (сущности) для них больше не существует; протокол в целом по разделу 9
 * ревизии больше не обязателен и станет предметом отдельной чистки позже.
 *
 * Откат 11.09.2026 (см. archive/server-side-voice-revert-2026-09-10):
 * голосовой пайплайн (захват микрофона, STT, TTS) уехал обратно на сервер вместе с
 * Simple Voice Chat — здесь остался только приём {@link SetCompanionHudStatePayload}
 * (сервер сам двигает HUD по ходу обработки реплики) и не участвующий в голосе HUD/
 * настройки, как и раньше.
 *
 * Раздел 11 ТЗ, шаг 5: LLM-инференс ({@link BrainEngine}) остаётся клиентским даже
 * после отката голоса на сервер (раздел 3/6 ТЗ — экономия GPU касается только
 * инференса), поэтому появляется приём {@link BrainRequestPayload} от сервера и
 * ответ {@link BrainReplyPayload} обратно — сервер прислал распознанный текст, тут
 * его отдаём в {@link BrainEngine}, а результат уходит назад на сервер для TTS.
 */
public final class VerityClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    @Override
    public void onInitializeClient() {
        NagumoPayloads.registerCommon();

        // HUD-виджет компаньона (раздел 7 ТЗ) + тестовая команда переключения его
        // состояния (раздел 11 ТЗ, шаг 2) — пока без реального голоса/LLM.
        CompanionHud.register();
        CompanionHudTestCommand.register();

        // Настройки клиента (раздел 11 ТЗ, шаг 3): уровень модели / выключатель
        // компаньона, персистентно в config/nagumo-client.json — без GUI-экрана.
        NagumoSettingsCommand.register();

        // При входе на сервер — сообщить, какие моды установлены у игрока (раздел 9 ТЗ).
        // Пригодится позже для слоя прямого поиска по конкретным модам (раздел 6.3),
        // без обращения к LLM. Формат: "modid1@version1;modid2@version2".
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String modsData = FabricLoader.getInstance().getAllMods().stream()
                    .map(mod -> {
                        ModMetadata meta = mod.getMetadata();
                        return meta.getId() + "@" + meta.getVersion().getFriendlyString();
                    })
                    .collect(Collectors.joining(";"));
            ClientPlayNetworking.send(new ReportInstalledModsPayload(modsData));

            // База знаний brain по предметам (обсуждение 15.09.2026,
            // см. GameKnowledgeIndex) — реестр предметов доступен на клиенте только
            // ПОСЛЕ входа в мир, поэтому строим индекс здесь же, не в onInitializeClient.
            // Ваниль и моды индексируются одинаково (общие реестры). Рецепты сюда больше
            // не входят — они приходят отдельно от сервера, см. приёмник
            // RecipeCompendiumPayload.ID ниже (тот же отчёт: recipe-книга игрока на JOIN
            // почти всегда пуста, полного списка рецептов на клиенте вообще нет).
            GameKnowledgeIndex.build();

            // Долгосрочная память компаньона (обсуждение 15.09.2026,
            // см. CompanionMemory) — отдельная на каждый сервер/мир И на каждого
            // игрока (уточнено тем же днём — см. javadoc resolveMemoryKey). Ключ
            // определяется здесь (client знает и сервер, и игрока), а не внутри
            // CompanionMemory/BrainEngine.
            CompanionMemory.load(CompanionMemory.resolveMemoryKey(client));
        });

        // Раздел 11 ТЗ, шаг 5: история диалога brain теперь персистентна per-server
        // (см. CompanionMemory) — на выход из мира не стираем, а сохраняем и выгружаем
        // состояние текущего сервера. База знаний игры, наоборот, не персистентна —
        // перестраивается заново при следующем входе (реестры и так локальны игре).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CompanionMemory.unload();
            GameKnowledgeIndex.clear();
        });

        // Показ текста от компаньона — этап 1: просто сообщение в чат.
        ClientPlayNetworking.registerGlobalReceiver(ShowTextPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().player != null) {
                        context.client().player.sendSystemMessage(Component.literal(payload.text()));
                    }
                }));

        // Состояние HUD теперь двигает сервер по ходу обработки голосовой реплики
        // (откат 11.09.2026, раздел 11 ТЗ) — просто разбираем строку в enum, неизвестное
        // значение логируем и игнорируем, а не роняем клиент на IllegalArgumentException.
        ClientPlayNetworking.registerGlobalReceiver(SetCompanionHudStatePayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    try {
                        CompanionHud.setState(CompanionHudState.valueOf(payload.state().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("[NAGUMO-HUD] сервер прислал неизвестное состояние HUD: \"{}\"", payload.state());
                    }
                }));

        // Раздел 11 ТЗ, шаг 5: сервер прислал распознанную реплику — сгенерировать
        // ответ клиентским brain (не на сетевом потоке — BrainEngine сам уходит на
        // свой executor) и отправить его обратно. ClientPlayNetworking.send потокобезопасен,
        // в execute() на клиентский поток переключаться не нужно (мы не трогаем состояние игры).
        ClientPlayNetworking.registerGlobalReceiver(BrainRequestPayload.ID, (payload, context) ->
                BrainEngine.generateReplyAsync(payload.recognizedText(), reply ->
                        ClientPlayNetworking.send(new BrainReplyPayload(payload.requestId(), reply))));

        // База знаний по рецептам (обсуждение 15.09.2026, см.
        // GameKnowledgeIndex) — сервер шлёт ВСЕ рецепты игры (без фильтра по прогрессу
        // игрока — убран 16.09.2026, см. javadoc RecipeCompendiumSender) сразу после
        // JOIN (см. RecipeCompendiumSender), сюда он может прийти на тик-другой позже
        // самого JOIN, поэтому индексируется отдельным вызовом, а не частью
        // GameKnowledgeIndex.build() выше.
        ClientPlayNetworking.registerGlobalReceiver(RecipeCompendiumPayload.ID, (payload, context) ->
                GameKnowledgeIndex.applyRecipes(payload.recipes()));

        // Оставшиеся entity-пакеты — сущности в мире больше нет (ревизия 10.09.2026),
        // адресата у них нет, поэтому только лог (не удалены целиком в этом проходе,
        // см. отчёт: раздел 9 ревизии ТЗ говорит, что протокол в целом не нужен).
        ClientPlayNetworking.registerGlobalReceiver(PlayAnimationPayload.ID, (payload, context) ->
                LOGGER.info("[legacy] PlayAnimation: entity=" + payload.entityId() + " anim=" + payload.animationId()));
        ClientPlayNetworking.registerGlobalReceiver(PlayAudioPayload.ID, (payload, context) ->
                LOGGER.info("[legacy] PlayAudio: entity=" + payload.entityId() + " audioRef=" + payload.audioRef()));
        ClientPlayNetworking.registerGlobalReceiver(MoveEntityToPayload.ID, (payload, context) ->
                LOGGER.info("[legacy] MoveEntityTo: entity=" + payload.entityId()
                        + " -> (" + payload.x() + ", " + payload.y() + ", " + payload.z() + ")"));
        ClientPlayNetworking.registerGlobalReceiver(SpawnEntityPayload.ID, (payload, context) ->
                LOGGER.info("[legacy] SpawnEntity: " + payload.companionName() + " id=" + payload.entityId()));
        ClientPlayNetworking.registerGlobalReceiver(DespawnEntityPayload.ID, (payload, context) ->
                LOGGER.info("[legacy] DespawnEntity: entity=" + payload.entityId()));

        // Тестовая команда /nagumo ask <текст> регистрируется на СЕРВЕРЕ (NagumoCommands)
        // вместе с name/style — единым деревом /nagumo. Клиентский литерал "nagumo" здесь
        // не регистрируется: раньше он перехватывал все /nagumo-подкоманды и не пропускал
        // их на сервер (см. память проекта). HUD-тест-команда поэтому живёт под отдельным
        // литералом /nagumohud (см. CompanionHudTestCommand), а не под общим деревом.

        LOGGER.info("NAGUMO AI (verity-client) инициализирован.");
    }
}
