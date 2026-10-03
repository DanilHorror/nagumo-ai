package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/**
 * Сервер -> клиент: попросить клиентский {@code brain} (раздел 6 ТЗ) сгенерировать
 * ответ на распознанную голосовую реплику владельца. Инференс сознательно остаётся
 * клиентским (экономия GPU, раздел 3/6 ТЗ) даже после отката голосового пайплайна на
 * сервер (раздел 5 ТЗ) — отсюда сетевой round-trip между STT на сервере и LLM на клиенте:
 * этот пакет туда, {@link BrainReplyPayload} обратно.
 *
 * {@code requestId} сейчас не нужен для сопоставления (пайплайн на игрока
 * последовательный — сервер ждёт максимум один ответ за раз), но передаётся на
 * будущее и для однозначного логирования по обе стороны.
 */
public record BrainRequestPayload(long requestId, String recognizedText) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BrainRequestPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "brain_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BrainRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BrainRequestPayload::requestId,
            ByteBufCodecs.STRING_UTF8, BrainRequestPayload::recognizedText,
            BrainRequestPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
