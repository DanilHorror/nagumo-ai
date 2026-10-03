package ru.nagumo.verity.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.client.config.NagumoClientConfig;
import ru.nagumo.verity.common.VerityCommon;

import java.util.concurrent.atomic.AtomicReference;

/**
 * HUD-виджет компаньона (раздел 7 ТЗ): маленький плавающий индикатор состояния в
 * верхнем правом углу экрана — не физическая сущность в мире, не мешает центру
 * обзора. Рендерится через Fabric {@link HudElementRegistry} (в этой версии Fabric
 * API актуальный способ подключиться к HUD — не старый {@code HudRenderCallback},
 * тот класс в fabric-rendering-v1 0.152.x/26.2 отсутствует).
 *
 * Пока БЕЗ реального голоса/LLM: состояние переключается только тестовой командой
 * (см. {@link CompanionHudTestCommand}), реальные переходы появятся вместе с
 * голосовым пайплайном и brain на клиенте (раздел 11 ТЗ, шаги 4+). Начиная с шага 3
 * виджет вообще не рисуется, если компаньон выключен в конфиге ({@link
 * ru.nagumo.verity.client.config.NagumoClientConfig#companionEnabled}).
 */
public final class CompanionHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "companion_hud");

    /** Отступ от края экрана и размер индикатора, в пикселях GUI-масштаба. */
    private static final int MARGIN = 6;
    private static final int SIZE = 20;

    private static final AtomicReference<CompanionHudState> STATE =
            new AtomicReference<>(CompanionHudState.IDLE);

    private CompanionHud() {
    }

    public static void setState(CompanionHudState state) {
        STATE.set(state);
    }

    public static CompanionHudState getState() {
        return STATE.get();
    }

    /** Зарегистрировать элемент в HUD — вызывается один раз при инициализации клиента. */
    public static void register() {
        HudElementRegistry.addLast(ID, CompanionHud::render);
    }

    private static void render(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        // Раздел 11 ТЗ, шаг 3: companionEnabled=false (эквивалент modelTier=OFF,
        // см. NagumoClientConfig) — виджет не рендерится вообще, а не просто гаснет.
        if (!NagumoClientConfig.get().companionEnabled) {
            return;
        }

        // Раньше здесь была ручная проверка Options.hideGui (не рендерить под F1) —
        // в этой версии MC у Options такого поля больше нет (переименовано/убрано,
        // не нашёл замену за разумное время поиска). Не проверено, скрывает ли
        // HudElementRegistry элементы под F1 сам по себе (как это делает
        // VanillaHudElements для встроенного HUD) — если нет, наш индикатор будет
        // виден на чистых скриншотах (F2) и в кинематографическом режиме. Стоит
        // перепроверить на реальном клиенте и добавить проверку, если понадобится.
        Minecraft client = Minecraft.getInstance();
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int centerX = screenWidth - MARGIN - SIZE / 2;
        int centerY = MARGIN + SIZE / 2;

        CompanionHudState state = STATE.get();

        // Лёгкая анимация без сложной модели (раздел 7 ТЗ) — indicator слегка
        // пульсирует в активных состояниях, отдельного тикера не требуется: для
        // визуальной заглушки этапа 2 достаточно системного времени.
        int pulse = (state == CompanionHudState.THINKING || state == CompanionHudState.SPEAKING)
                ? Math.round(2 * (float) Math.sin(System.currentTimeMillis() / 150.0))
                : 0;
        int half = SIZE / 2 + pulse;

        guiGraphics.fill(centerX - half, centerY - half, centerX + half, centerY + half, 0xA0000000);
        guiGraphics.fill(centerX - half + 2, centerY - half + 2, centerX + half - 2, centerY + half - 2,
                colorFor(state));

        String label = labelFor(state);
        if (!label.isEmpty()) {
            Font font = client.font;
            guiGraphics.centeredText(font, label, centerX, centerY - font.lineHeight / 2, 0xFFFFFFFF);
        }
    }

    private static int colorFor(CompanionHudState state) {
        return switch (state) {
            case IDLE -> 0xFF6E7681;
            case LISTENING -> 0xFF3FB950;
            case THINKING -> 0xFFD29922;
            case SPEAKING -> 0xFF58A6FF;
        };
    }

    private static String labelFor(CompanionHudState state) {
        return switch (state) {
            case IDLE -> "";
            case LISTENING -> "...";
            case THINKING -> "?";
            case SPEAKING -> "!";
        };
    }
}
