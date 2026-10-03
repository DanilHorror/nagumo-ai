package ru.nagumo.verity.server.companion;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Реестр компаньонов на сервере: хранение данных на игрока и генерация дефолтного
 * позывного (раздел 2 ТЗ, ревизия 10.09.2026). Чистая Java без зависимостей от
 * Minecraft API — низкий риск несовместимости с версией игры.
 *
 * *Было:* имя дополнительно проверялось на уникальность среди ВСЕХ компаньонов
 * сервера — было обязательным условием, пока к компаньону мог обратиться кто угодно.
 * *Стало:* общение приватное (владелец ↔ его компаньон), коллизия имён у разных
 * игроков не создаёт неоднозначности — проверка убрана вместе с {@code CompanionAddressing}.
 */
public final class CompanionRegistry {

    /** Валидные пресеты характера (раздел 8 ТЗ). */
    public static final Set<String> STYLE_PRESETS = Set.of("friendly", "ironic", "business", "reserved");

    private static final char[] CONSONANTS = "BCDFGHJKLMNPRSTVWZ".toCharArray();
    private static final char[] VOWELS = "AEIOU".toCharArray();
    private static final Map<Character, Character> LEET = Map.of(
            'E', '3', 'A', '4', 'S', '5', 'I', '1', 'O', '0'
    );

    private final Map<UUID, CompanionData> companions = new ConcurrentHashMap<>();

    /** Данные компаньона игрока, если они уже существуют, иначе {@code null} (без создания). */
    public CompanionData get(UUID playerId) {
        return companions.get(playerId);
    }

    /** Вернуть данные компаньона игрока, создав их (с дефолтным позывным) при первом обращении. */
    public CompanionData getOrCreate(UUID playerId) {
        return companions.computeIfAbsent(playerId, id -> new CompanionData(generateDefaultName()));
    }

    /**
     * Переименовать компаньона. Возвращает null при успехе, иначе — текст причины
     * отказа (имя некорректно), который можно сразу показать игроку.
     */
    public String rename(UUID playerId, String newName) {
        if (newName == null || newName.isBlank() || newName.length() > 16) {
            return "Имя должно быть от 1 до 16 символов.";
        }
        CompanionData data = companions.get(playerId);
        if (data == null) {
            companions.put(playerId, new CompanionData(newName));
        } else {
            data.setName(newName);
        }
        return null;
    }

    /**
     * Генерация позывного в стиле "5H3Y" (раздел 2 ТЗ): 4-5 символов, псевдо-читаемая
     * заготовка из согласных/гласных, часть букв заменяется на цифры по leet-таблице,
     * гарантированы минимум одна буква и одна цифра в результате.
     *
     * ВАЖНО (раздел 2 ТЗ): "Символы 0/O и 1/I не смешиваются в одном имени". Раньше
     * решение "заменять эту букву на цифру" принималось независимо на каждую отдельную
     * позицию — при повторении одной буквы в имени (например, две "O" в заготовке) это
     * могло дать одновременно и "O", и "0" в одном и том же результате. Теперь решение
     * принимается один раз на каждую РАЗЛИЧНУЮ букву во всём имени (см. {@code substituteDecision}
     * ниже) — все вхождения одной и той же буквы заменяются одинаково, поэтому пара
     * 0/O (и аналогично 1/I) физически не может оказаться смешанной.
     */
    private String generateDefaultName() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int length = 4 + random.nextInt(2); // 4 или 5
        StringBuilder base = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char[] pool = (i % 2 == 0) ? CONSONANTS : VOWELS;
            base.append(pool[random.nextInt(pool.length)]);
        }

        // Решение "заменять на цифру" — одно на каждую различную букву, а не на каждое
        // вхождение по отдельности (см. javadoc метода выше).
        Map<Character, Boolean> substituteDecision = new HashMap<>();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (LEET.containsKey(c)) {
                substituteDecision.computeIfAbsent(c, k -> random.nextBoolean());
            }
        }

        StringBuilder result = new StringBuilder(length);
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Boolean.TRUE.equals(substituteDecision.get(c))) {
                result.append(LEET.get(c));
                hasDigit = true;
            } else {
                result.append(c);
                hasLetter = true;
            }
        }

        if (!hasDigit) {
            // Ни одна буква не заменилась — форсируем замену для первой подходящей
            // буквы, и заодно для ВСЕХ её повторов в имени (та же согласованность).
            for (int i = 0; i < base.length(); i++) {
                char c = base.charAt(i);
                if (LEET.containsKey(c)) {
                    for (int j = 0; j < base.length(); j++) {
                        if (base.charAt(j) == c) {
                            result.setCharAt(j, LEET.get(c));
                        }
                    }
                    hasDigit = true;
                    break;
                }
            }
        }
        if (!hasLetter) {
            // Позиция 0 всегда согласная (см. CONSONANTS) — ни O, ни I там оказаться не
            // может, так что возврат её к букве не способен воссоздать смешение 0/O или 1/I.
            result.setCharAt(0, base.charAt(0));
        }

        return result.toString();
    }
}
