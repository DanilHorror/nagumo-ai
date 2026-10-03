package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/** Сервер -> клиент: показать текст (реплику компаньона), раздел 9 ТЗ. */
public record ShowTextPayload(int entityId, String text) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShowTextPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "show_text"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShowTextPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShowTextPayload::entityId,
            ByteBufCodecs.STRING_UTF8, ShowTextPayload::text,
            ShowTextPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
