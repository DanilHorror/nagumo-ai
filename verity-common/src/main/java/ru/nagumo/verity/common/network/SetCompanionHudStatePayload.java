package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

/**
 * Сервер -> клиент: сменить состояние HUD-виджета компаньона (раздел 7/11 ТЗ).
 *
 * Добавлен при откате голосового пайплайна на сервер (11.09.2026, см. ТЗ и
 * archive/server-side-voice-revert-2026-09-10/README.md) — раньше HUD и голос жили
 * в одном процессе (клиент), состояние менялось прямым вызовом
 * {@code CompanionHud.setState(...)}; теперь пайплайн на сервере, и ему нужен
 * сетевой способ доехать до HUD того же игрока, чей микрофон он обрабатывает.
 *
 * {@code state} — имя константы {@code CompanionHudState} (verity-client), как
 * простая строка: verity-common не может ссылаться на клиентский enum напрямую
 * (verity-common используется и сервером, где этого класса нет на classpath), а
 * заводить копию enum здесь ради одного клиента — лишняя сущность. Получатель
 * разбирает строку через {@code CompanionHudState.valueOf(...)} и не падает на
 * незнакомом значении (см. VerityClient).
 */
public record SetCompanionHudStatePayload(String state) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetCompanionHudStatePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "set_companion_hud_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCompanionHudStatePayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(SetCompanionHudStatePayload::new, SetCompanionHudStatePayload::state).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
