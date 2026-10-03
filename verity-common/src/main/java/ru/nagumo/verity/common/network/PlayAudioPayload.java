package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/**
 * Сервер -> клиент: воспроизвести аудио у сущности-компаньона.
 * Стадия 1 — заглушка: передаётся только ссылка/идентификатор (audioRef), а не сами
 * байты. Конкретный формат передачи TTS-аудио (сырые байты, локальный URL, поток через
 * Simple Voice Chat и т.п.) определяется на этапе реализации голосового пайплайна
 * (раздел 5 ТЗ, этап 3 плана) — тогда этот payload, скорее всего, изменится.
 */
public record PlayAudioPayload(int entityId, String audioRef) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayAudioPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "play_audio"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayAudioPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlayAudioPayload::entityId,
            ByteBufCodecs.STRING_UTF8, PlayAudioPayload::audioRef,
            PlayAudioPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
