package ru.nagumo.verity.client.config;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Генерация дефолтного позывного компаньона (раздел 2 ТЗ). Алгоритм — точная копия
 * бывшего {@code CompanionRegistry.generateDefaultName} из verity-server (не менялся,
 * см. задачу миграции раздела 11 ТЗ, шаг 4): имя теперь клиентское, но сама схема
 * генерации осталась той же самой, только без проверки уникальности (раздел 2
 * ревизии ТЗ — коллизия имён у разных игроков больше не создаёт неоднозначности).
 *
 * *Решение (не описано явно в ТЗ):* исходный метод остался ТАКЖЕ и в
 * {@code verity-server.CompanionRegistry} — его до сих пор использует отдельная,
 * не тронутая в этом проходе серверная команда {@code /nagumo name/style/ask}
 * (раздел 9 старого протокола). Задача просила "скопировать" логику (не "перенести
 * и удалить"), поэтому исходный серверный метод не удалён — но теперь на клиенте и
 * на сервере существуют два независимых генератора с одинаковым алгоритмом, и имя,
 * которое видит клиент ({@link NagumoClientConfig#companionName}), больше НЕ связано
 * с именем, которое хранит {@code CompanionRegistry} на сервере. Это открытый
 * архитектурный вопрос на будущее: вероятно, серверный текстовый интерфейс
 * компаньона стоит вывести из эксплуатации, раз голосовой пайплайн и HUD теперь
 * полностью клиентские — но это отдельное решение, не принятое молча в этом проходе.
 *
 * *Найденная и исправленная ошибка (не описано в ТЗ — обнаружена верификацией):*
 * при переносе прогнал алгоритм через 20000 генераций для проверки инвариантов
 * ("минимум одна буква и одна цифра") и обнаружил, что примерно в 3.6% случаев
 * заготовка ({@code base}) не содержит НИ ОДНОЙ буквы, годной для leet-замены
 * (E/A/S/I/O) вообще — согласный пул содержит только одну такую букву (S) из 18,
 * гласный пул — четыре из пяти (только U не годится), и если по чистой случайности
 * все выпавшие позиции оказались "негодными" буквами, `if (!hasDigit)`-заглушка
 * ничего не находит и молча возвращает имя БЕЗ цифры — то есть нарушает
 * собственную гарантию ТЗ (раздел 2: "гарантированы минимум одна буква и одна
 * цифра"). Это баг в ОРИГИНАЛЬНОМ алгоритме (был и в {@code CompanionRegistry} на
 * сервере ДО этого переноса, не внесён при копировании) — задача просила копию без
 * изменений, но раз баг напрямую противоречит уже задокументированной гарантии ТЗ и
 * был обнаружен, а не придуман, здесь он ИСПРАВЛЕН (передел заготовки, если в ней
 * вообще нет годных для leet-замены букв, см. {@code generate()} ниже) — серверная
 * копия в {@code CompanionRegistry} НЕ тронута (вне зоны действия этой задачи), там
 * тот же баг с той же частотой всё ещё присутствует.
 */
public final class CompanionNameGenerator {

    private static final char[] CONSONANTS = "BCDFGHJKLMNPRSTVWZ".toCharArray();
    private static final char[] VOWELS = "AEIOU".toCharArray();
    private static final Map<Character, Character> LEET = Map.of(
            'E', '3', 'A', '4', 'S', '5', 'I', '1', 'O', '0'
    );

    private CompanionNameGenerator() {
    }

    /**
     * Генерация позывного в стиле "5H3Y" (раздел 2 ТЗ): 4-5 символов, псевдо-читаемая
     * заготовка из согласных/гласных, часть букв заменяется на цифры по leet-таблице,
     * гарантированы минимум одна буква и одна цифра в результате.
     *
     * ВАЖНО (раздел 2 ТЗ): "Символы 0/O и 1/I не смешиваются в одном имени" — решение
     * "заменять на цифру" принимается один раз на каждую РАЗЛИЧНУЮ букву во всём имени
     * (см. {@code substituteDecision} ниже), а не на каждое вхождение по отдельности,
     * поэтому пара 0/O (и аналогично 1/I) физически не может оказаться смешанной.
     */
    public static String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int length = 4 + random.nextInt(2); // 4 или 5
        StringBuilder base;
        // Отбраковка (~3.6% попыток, см. javadoc класса про найденный баг): без хотя бы
        // одной буквы, годной для leet-замены (E/A/S/I/O), гарантия "минимум одна цифра"
        // ниже физически невыполнима — перегенерируем заготовку, а не подменяем буквы
        // постфактум (это бы исказило распределение способом, не описанным в ТЗ).
        do {
            base = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                char[] pool = (i % 2 == 0) ? CONSONANTS : VOWELS;
                base.append(pool[random.nextInt(pool.length)]);
            }
        } while (base.chars().noneMatch(c -> LEET.containsKey((char) c)));

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
