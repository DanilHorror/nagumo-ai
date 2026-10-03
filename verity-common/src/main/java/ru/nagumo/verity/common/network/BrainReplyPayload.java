package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/**
 * Клиент -> сервер: готовый текст ответа компаньона на {@link BrainRequestPayload}
 * с тем же {@code requestId}. Сервер озвучивает {@code replyText} через Piper и
 * показывает его через {@link ShowTextPayload}, как раньше делал с эхо-заглушкой.
 */
public record BrainReplyPayload(long requestId, String replyText) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BrainReplyPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "brain_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BrainReplyPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BrainReplyPayload::requestId,
            ByteBufCodecs.STRING_UTF8, BrainReplyPayload::replyText,
            BrainReplyPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
