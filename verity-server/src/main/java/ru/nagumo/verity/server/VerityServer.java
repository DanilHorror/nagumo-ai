package ru.nagumo.verity.server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ru.nagumo.verity.common.network.NagumoPayloads;
import ru.nagumo.verity.common.network.ReportInstalledModsPayload;
import ru.nagumo.verity.server.command.NagumoCommands;
import ru.nagumo.verity.server.companion.CompanionData;
import ru.nagumo.verity.server.companion.CompanionRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа серверного мода. Ревизия 10.09.2026 (см. ТЗ): компаньон больше
 * не физическая сущность в мире и не адресуется другими игроками — сервер только
 * хранит имя/стиль компаньона и регистрирует команды. verity-server в целом больше
 * не обязателен для v1 (личность/LLM переезжают на клиент, раздел 3/6 ТЗ), но пока
 * оставлен как есть, без адресации/спавна сущности/уникальности имени.
 */
public final class VerityServer implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    private static CompanionRegistry companions;

    /**
     * Доступ к реестру компаньонов для кода, создаваемого вне основного графа
     * зависимостей — прежде всего {@code NagumoVoicechatPlugin} (его инстанцирует сам
     * Simple Voice Chat по entrypoint "voicechat", без возможности передать ссылку
     * через конструктор). Возвращает {@code null}, пока сервер не инициализировался.
     *
     * ВОССТАНОВЛЕНО при откате голосового пайплайна на сервер (11.09.2026, см.
     * archive/server-side-voice-revert-2026-09-10) — этот же паттерн уже
     * был здесь до переноса на клиент и был убран как мёртвый код, когда
     * NagumoVoicechatPlugin временно перестал существовать на сервере.
     */
    public static CompanionRegistry companions() {
        return companions;
    }

    @Override
    public void onInitialize() {
        companions = new CompanionRegistry();
        NagumoPayloads.registerCommon();
        NagumoCommands.register(companions);

        // База знаний компаньона по рецептам (обсуждение 15.09.2026,
        // см. RecipeCompendiumSender) — полный RecipeManager есть только здесь, на
        // сервере; клиент сам построить такой список не может (см. javadoc
        // RecipeCompendiumPayload). ИСПРАВЛЕНО 16.09.2026: фильтр "без спойлеров" убран
        // (см. javadoc RecipeCompendiumSender) — компендиум одинаковый для всех игроков,
        // поэтому собирается один раз при старте мира, а не на каждый JOIN.
        ServerLifecycleEvents.SERVER_STARTED.register(RecipeCompendiumSender::buildOnce);

        // При входе игрока — создать (если ещё нет) данные его компаньона с дефолтным
        // позывным. Сущности в мире больше нет (ревизия 10.09.2026) — компаньон
        // существует только как запись в реестре + HUD на клиенте.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            CompanionData data = companions.getOrCreate(player.getUUID());
            player.sendSystemMessage(
                    Component.literal("Твой ИИ-компаньон теперь с тобой. Его зовут " + data.getName()
                            + ". Переименовать: /nagumo name <имя>")
            );

            RecipeCompendiumSender.sendTo(player, server);
        });

        // Этап 1: просто логируем список модов (раздел 9 ТЗ) — реальное использование
        // (подсказки по конкретным модам) появится со слоем прямого поиска (раздел 6.3).
        ServerPlayNetworking.registerGlobalReceiver(ReportInstalledModsPayload.ID, (payload, context) ->
                LOGGER.info("Игрок " + context.player().getName().getString()
                        + " прислал список модов: " + payload.modsData()));

        LOGGER.info("NAGUMO AI (verity-server) инициализирован.");
    }
}
