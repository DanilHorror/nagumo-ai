package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/**
 * Сервер -> клиент: заспавнить сущность-компаньона (раздел 9 ТЗ).
 * На этапе 1 не используется (реальный спавн сущности откладывается до этапа 2,
 * см. SETUP.md) — пакет уже описан, чтобы протокол был готов к этапу 2.
 */
public record SpawnEntityPayload(int entityId, String companionName, double x, double y, double z) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SpawnEntityPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "spawn_entity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpawnEntityPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpawnEntityPayload::entityId,
            ByteBufCodecs.STRING_UTF8, SpawnEntityPayload::companionName,
            ByteBufCodecs.DOUBLE, SpawnEntityPayload::x,
            ByteBufCodecs.DOUBLE, SpawnEntityPayload::y,
            ByteBufCodecs.DOUBLE, SpawnEntityPayload::z,
            SpawnEntityPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
