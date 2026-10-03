package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/** Сервер -> клиент: проиграть анимацию у сущности-компаньона (раздел 9 ТЗ). */
public record PlayAnimationPayload(int entityId, String animationId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayAnimationPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "play_animation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayAnimationPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlayAnimationPayload::entityId,
            ByteBufCodecs.STRING_UTF8, PlayAnimationPayload::animationId,
            PlayAnimationPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
