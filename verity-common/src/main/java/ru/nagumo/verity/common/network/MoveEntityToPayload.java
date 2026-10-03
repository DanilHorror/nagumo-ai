package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/** Сервер -> клиент: переместить сущность-компаньона в указанную точку (раздел 9 ТЗ). */
public record MoveEntityToPayload(int entityId, double x, double y, double z) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MoveEntityToPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "move_entity_to"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MoveEntityToPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MoveEntityToPayload::entityId,
            ByteBufCodecs.DOUBLE, MoveEntityToPayload::x,
            ByteBufCodecs.DOUBLE, MoveEntityToPayload::y,
            ByteBufCodecs.DOUBLE, MoveEntityToPayload::z,
            MoveEntityToPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
