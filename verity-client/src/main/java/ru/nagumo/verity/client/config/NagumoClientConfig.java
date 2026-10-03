package ru.nagumo.verity.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.nagumo.verity.client.brain.ModelTier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Персистентный конфиг клиента (раздел 11 ТЗ, шаг 3) — простой JSON в стандартной
 * config-папке Fabric-клиента ({@code config/nagumo-client.json}), без GUI-экрана
 * (это отдельная задача позже, см. задачу этого прохода). Единственная точка входа —
 * {@link #get()}, ленивая загрузка при первом обращении, сохранение сразу при
 * каждом изменении (заглушка/этап без реального инференса — частые записи маленького
 * файла ничего не стоят, отдельный "dirty"-флаг или отложенное сохранение избыточны).
 *
 * *Решение (не описано явно в ТЗ):* {@code companionEnabled} и {@code modelTier}
 * персистятся оба (как просили), но ведущим полем считается {@code modelTier} —
 * {@code companionEnabled} всегда синхронизируется как {@code modelTier != OFF} при
 * каждом изменении через {@link #setModelTier}. Это единственный способ поменять
 * настройки в этом проходе ({@code /nagumosettings model ...}), отдельной команды
 * для independent-переключателя "companionEnabled" пока нет — так что рассинхрон
 * этих двух полей сейчас невозможен. Если позже появится GUI с отдельным чекбоксом
 * "включить компаньона" (независимым от выбора уровня), нужно будет решить, должен
 * ли выключатель "забывать" выбранный уровень или помнить его до следующего
 * включения — сейчас это не решено, потому что решать не для чего.
 *
 * Раздел 11 ТЗ, шаг 4: {@code companionName} добавлено сюда — имя компаньона теперь
 * клиентское (было — {@code CompanionRegistry} в verity-server). Генерируется один
 * раз при первом обращении, если пусто ({@link CompanionNameGenerator}, копия
 * бывшего {@code CompanionRegistry.generateDefaultName} — алгоритм не менялся).
 * Уникальность больше не проверяется (раздел 2 ревизии ТЗ).
 *
 * Раздел 11 ТЗ, шаг 5: {@code style} добавлено сюда для клиентского {@code brain}
 * ({@code ru.nagumo.verity.client.brain.BrainEngine}) — раздел 8 ТЗ описывает выбор
 * пресета характера игроком, а до этого прохода клиентской настройки для него не
 * было (только независимый серверный {@code /nagumo style}, оставшийся с версии ТЗ
 * до переноса "мозга" на клиент — он на реальный ответ brain больше не влияет, см.
 * отчёт). Значения — те же 4 ключа, что и у серверного
 * {@code CompanionRegistry.STYLE_PRESETS} ({@link #STYLE_PRESETS} ниже; не общий
 * класс с сервером специально, тот же компромисс, что и у {@link CompanionNameGenerator}).
 */
public final class NagumoClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Валидные пресеты характера (раздел 8 ТЗ) — те же ключи, что у серверного CompanionRegistry.STYLE_PRESETS. */
    public static final Set<String> STYLE_PRESETS = Set.of("friendly", "ironic", "business", "reserved");
    public static final String DEFAULT_STYLE = "friendly";

    private static NagumoClientConfig instance;

    /**
     * Дефолт — {@code true}: компаньон включён "из коробки" при первом запуске,
     * как и было в Milestone 1 (HUD виден сразу после установки мода, без
     * дополнительного действия игрока). Согласуется с {@code modelTier = LITE}
     * ниже — оба поля по умолчанию описывают "компаньон включён, уровень LITE".
     */
    public boolean companionEnabled = true;

    /**
     * Дефолт — {@link ModelTier#LITE}: раздел 6 ТЗ прямо называет её "дефолт для
     * большинства игровых видеокарт (6-8 ГБ VRAM)".
     */
    public ModelTier modelTier = ModelTier.LITE;

    /**
     * Позывной компаньона (раздел 2 ТЗ) — пусто до первой генерации. {@link #load()}
     * заполняет его {@link CompanionNameGenerator#generate()}, если он пуст (новый
     * конфиг или конфиг, сохранённый до появления этого поля).
     */
    public String companionName = "";

    /** Пресет характера (раздел 8 ТЗ) — дефолт "Дружелюбный и живой", как и на сервере. */
    public String style = DEFAULT_STYLE;

    private NagumoClientConfig() {
    }

    public static synchronized NagumoClientConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Сменить уровень модели и сразу сохранить — держит companionEnabled в синхроне (см. javadoc класса). */
    public void setModelTier(ModelTier tier) {
        this.modelTier = tier;
        this.companionEnabled = tier != ModelTier.OFF;
        save();
    }

    /**
     * Переименовать компаньона. Возвращает {@code null} при успехе, иначе — текст
     * причины отказа. Уникальность не проверяется (раздел 2 ревизии ТЗ) — только
     * базовая валидация, как и раньше в {@code CompanionRegistry.rename}.
     */
    public String rename(String newName) {
        if (newName == null || newName.isBlank() || newName.length() > 16) {
            return "Имя должно быть от 1 до 16 символов.";
        }
        this.companionName = newName;
        save();
        return null;
    }

    /**
     * Сменить пресет характера. Возвращает {@code null} при успехе, иначе — текст
     * причины отказа (неизвестный пресет).
     */
    public String setStyle(String newStyle) {
        if (newStyle == null || !STYLE_PRESETS.contains(newStyle)) {
            return "Неизвестный пресет. Доступные: " + STYLE_PRESETS;
        }
        this.style = newStyle;
        save();
        return null;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("nagumo-client.json");
    }

    private static NagumoClientConfig load() {
        Path path = configPath();
        NagumoClientConfig config = null;
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                NagumoClientConfig loaded = GSON.fromJson(json, NagumoClientConfig.class);
                if (loaded != null) {
                    // Поля Gson не тронет, если их не было в JSON (например, после
                    // ручного редактирования файла или конфига, сохранённого до
                    // появления поля) — modelTier/companionName могут прийти null.
                    if (loaded.modelTier == null) {
                        loaded.modelTier = ModelTier.LITE;
                    }
                    if (loaded.companionName == null) {
                        loaded.companionName = "";
                    }
                    if (loaded.style == null || !STYLE_PRESETS.contains(loaded.style)) {
                        loaded.style = DEFAULT_STYLE;
                    }
                    config = loaded;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("[NAGUMO-CONFIG] не удалось прочитать {} — использую дефолтные настройки: {}",
                        path, e.toString());
            }
        }
        boolean needsSave = config == null;
        if (config == null) {
            config = new NagumoClientConfig();
        }
        if (config.companionName.isBlank()) {
            config.companionName = CompanionNameGenerator.generate();
            needsSave = true;
        }
        if (needsSave) {
            config.save();
        }
        return config;
    }

    private void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[NAGUMO-CONFIG] не удалось сохранить {}: {}", path, e.toString());
        }
    }
}
