package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/**
 * Клиент -> сервер: список установленных у игрока модов, при подключении (раздел 9 ТЗ).
 * Кодируется одной строкой вида "modid1@version1;modid2@version2;..." — простое решение
 * для этапа 1, чтобы не зависеть от менее очевидных codec-хелперов для списков/массивов.
 */
public record ReportInstalledModsPayload(String modsData) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ReportInstalledModsPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "report_installed_mods"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReportInstalledModsPayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(ReportInstalledModsPayload::new, ReportInstalledModsPayload::modsData).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
