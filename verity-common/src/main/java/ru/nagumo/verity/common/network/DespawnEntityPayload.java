package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/** Сервер -> клиент: удалить сущность-компаньона, например при выходе владельца (раздел 9 ТЗ). */
public record DespawnEntityPayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DespawnEntityPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "despawn_entity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DespawnEntityPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(DespawnEntityPayload::new, DespawnEntityPayload::entityId).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
