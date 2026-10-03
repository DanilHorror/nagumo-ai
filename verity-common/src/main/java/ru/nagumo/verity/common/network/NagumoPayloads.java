package ru.nagumo.verity.common.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Регистрация всех сетевых пакетов (раздел 9 ТЗ). registerCommon() должен вызываться
 * и на клиенте (VerityClient), и на сервере (VerityServer) при инициализации —
 * оба физических сайда должны знать о всех типах пакетов, чтобы кодировать/декодировать их.
 *
 * Идемпотентна (guard {@link #registered}) начиная с 15.09.2026 — на сервере
 * {@link ru.nagumo.verity.server.voice.NagumoVoicechatPlugin#initialize} тоже зовёт
 * этот метод в начале своей инициализации: порядок entrypoint'ов Fabric между
 * зависимым модом (nagumo) и тем, от кого он зависит (voicechat), не гарантирован
 * настолько, чтобы полагаться на то, что VerityServer.onInitialize() успеет
 * зарегистрировать типы пакетов раньше, чем voicechat синхронно инициализирует
 * плагины SVC внутри своего собственного onInitialize() — на практике оказалось
 * наоборот (voicechat инициализируется первым), из-за чего PayloadTypeRegistry ещё
 * не знал о BrainReplyPayload в момент регистрации приёмника, и вызов бросал
 * IllegalArgumentException ("no payload type has been registered"), молча
 * проглатываемое PluginManager'ом SVC — приёмник ответа brain так и не
 * регистрировался, отсюда постоянный таймаут "задумался".
 */
public final class NagumoPayloads {

    private static final AtomicBoolean registered = new AtomicBoolean(false);

    private NagumoPayloads() {
    }

    public static void registerCommon() {
        if (!registered.compareAndSet(false, true)) {
            return; // уже зарегистрировано — Fabric бросает исключение на повторную регистрацию того же ID
        }
        // Сервер -> клиент
        PayloadTypeRegistry.clientboundPlay().register(PlayAnimationPayload.ID, PlayAnimationPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlayAudioPayload.ID, PlayAudioPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShowTextPayload.ID, ShowTextPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MoveEntityToPayload.ID, MoveEntityToPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SpawnEntityPayload.ID, SpawnEntityPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DespawnEntityPayload.ID, DespawnEntityPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SetCompanionHudStatePayload.ID, SetCompanionHudStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BrainRequestPayload.ID, BrainRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RecipeCompendiumPayload.ID, RecipeCompendiumPayload.CODEC);

        // Клиент -> сервер
        PayloadTypeRegistry.serverboundPlay().register(ReportInstalledModsPayload.ID, ReportInstalledModsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BrainReplyPayload.ID, BrainReplyPayload.CODEC);
    }
}
