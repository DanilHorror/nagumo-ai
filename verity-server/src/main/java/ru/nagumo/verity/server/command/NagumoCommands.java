package ru.nagumo.verity.server.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ru.nagumo.verity.server.companion.CompanionData;
import ru.nagumo.verity.server.companion.CompanionRegistry;
import ru.nagumo.verity.server.voice.NagumoVoicechatPlugin;

/** Регистрация команд /nagumo ... (раздел 9 ТЗ). */
public final class NagumoCommands {

    private NagumoCommands() {
    }

    // Идентификаторы аргументов — только ASCII (кириллица в имени узла-аргумента
    // Brigadier'а — плохая практика при синхронизации дерева команд на клиент).
    private static final String ARG_NAME = "name";
    private static final String ARG_PRESET = "preset";
    private static final String ARG_TEXT = "text";

    public static void register(CompanionRegistry registry) {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(Commands.literal("nagumo")
                        .then(Commands.literal("name")
                                // greedyString, а не word()/.string(): Brigadier читает
                                // word() через readUnquotedString(), который в неэкранированном
                                // вводе принимает только ASCII ([0-9A-Za-z_.+-]) — на первой же
                                // кириллической букве съедает 0 символов и падает
                                // ("Ожидался пробел, разделяющий аргументы..."). greedyString
                                // читает весь остаток ввода без ограничений по алфавиту;
                                // безопасно, т.к. name — последний (и единственный) аргумент.
                                .then(Commands.argument(ARG_NAME, StringArgumentType.greedyString())
                                        .executes(ctx -> renameCompanion(ctx.getSource(), registry,
                                                StringArgumentType.getString(ctx, ARG_NAME)))))
                        .then(Commands.literal("style")
                                .then(Commands.argument(ARG_PRESET, StringArgumentType.word())
                                        .executes(ctx -> setStyle(ctx.getSource(), registry,
                                                StringArgumentType.getString(ctx, ARG_PRESET)))))
                        // /nagumo ask — серверная команда (раньше была клиентской в
                        // VerityClient, но клиентский литерал "nagumo" перехватывал ВСЕ
                        // /nagumo-подкоманды и не пропускал их на сервер — Brigadier на
                        // клиенте не находил name/style в своём дереве и падал с
                        // "Incorrect argument for command", команда не уходила серверу).
                        // Теперь всё дерево /nagumo — только на сервере, одно и цельное.
                        .then(Commands.literal("ask")
                                .then(Commands.argument(ARG_TEXT, StringArgumentType.greedyString())
                                        .executes(ctx -> askCompanion(ctx.getSource(), registry,
                                                StringArgumentType.getString(ctx, ARG_TEXT)))))
                        // /nagumo speak — A/B-тест TTS-движков (обсуждение 15.09.2026, Silero
                        // против Piper, см. ТЗ) — озвучивает текст НАПРЯМУЮ, минуя brain,
                        // чтобы слышать именно заданную фразу, а не перефразированный ответ LLM.
                        .then(Commands.literal("speak")
                                .then(Commands.argument(ARG_TEXT, StringArgumentType.greedyString())
                                        .executes(ctx -> speakText(ctx.getSource(), registry,
                                                StringArgumentType.getString(ctx, ARG_TEXT)))))
                ));
    }

    /**
     * /nagumo ask — текстовый вход в тот же brain-пайплайн, что и голос (раздел 11 ТЗ,
     * шаг 5). Раньше была эхо-заглушкой (см. git-историю) — теперь, когда голосовой
     * пайплайн (NagumoVoicechatPlugin) реально дозванивается до клиентского brain,
     * полезнее дать тот же путь и текстом: удобно проверять brain/GameKnowledgeIndex
     * без микрофона/SVC-клиента. NagumoVoicechatPlugin.instance() может быть null,
     * только если SVC-плагин ещё не инициализировался (voicechat не установлен/не
     * загрузился) — тогда явно говорим об этом вместо тихого падения.
     */
    private static int askCompanion(CommandSourceStack source, CompanionRegistry registry, String text) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        registry.getOrCreate(player.getUUID()); // гарантируем, что у компаньона уже есть имя/стиль
        NagumoVoicechatPlugin voice = NagumoVoicechatPlugin.instance();
        if (voice == null) {
            source.sendFailure(Component.literal(
                    "Голосовой плагин (Simple Voice Chat) не инициализирован — brain недоступен."));
            return 0;
        }
        voice.askBrain(player.getUUID(), text);
        return 1;
    }

    /**
     * /nagumo speak — прямая озвучка текста, в обход brain (раздел "A/B-тест TTS",
     * ТЗ 15.09.2026) — та же причина null-check на {@code instance()}, что и
     * у {@link #askCompanion}: SVC-плагин может быть ещё не инициализирован.
     */
    private static int speakText(CommandSourceStack source, CompanionRegistry registry, String text) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        registry.getOrCreate(player.getUUID());
        NagumoVoicechatPlugin voice = NagumoVoicechatPlugin.instance();
        if (voice == null) {
            source.sendFailure(Component.literal(
                    "Голосовой плагин (Simple Voice Chat) не инициализирован — озвучка недоступна."));
            return 0;
        }
        voice.speakText(player.getUUID(), text);
        return 1;
    }

    private static int renameCompanion(CommandSourceStack source, CompanionRegistry registry, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String error = registry.rename(player.getUUID(), newName);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Теперь твоего компаньона зовут " + newName + "."), false);
        return 1;
    }

    private static int setStyle(CommandSourceStack source, CompanionRegistry registry, String preset) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        if (!CompanionRegistry.STYLE_PRESETS.contains(preset)) {
            source.sendFailure(Component.literal("Неизвестный пресет. Доступные: " + CompanionRegistry.STYLE_PRESETS));
            return 0;
        }
        CompanionData data = registry.getOrCreate(player.getUUID());
        data.setStyle(preset);
        source.sendSuccess(() -> Component.literal("Характер компаньона: " + preset + "."), false);
        return 1;
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Эту команду может выполнить только игрок."));
        }
        return player;
    }
}
