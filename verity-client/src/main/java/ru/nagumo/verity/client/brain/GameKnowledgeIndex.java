package ru.nagumo.verity.client.brain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.world.item.Item;
import ru.nagumo.verity.common.network.RecipeCompendiumPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "База знаний" компаньона по предметам и рецептам игры (раздел 6 ТЗ, п. "слой
 * прямого поиска" — изначально задумывался как серверный поверх ReportInstalledModsPayload,
 * но раз brain теперь клиентский (ревизия 10.09.2026), строить индекс тоже логичнее
 * прямо здесь: клиент и так держит в памяти реестр предметов после входа в мир — и
 * ВАНИЛЬНЫЕ, И МОДОВЫЕ сразу, без отдельного разбора: Fabric-моды регистрируют своё
 * содержимое в те же реестры, что и ваниль (см. обсуждение 15.09.2026).
 * Различить происхождение записи, если понадобится, можно по namespace в id
 * ("minecraft:..." против "modid:...").
 *
 * Синхронный по построению (предметы индексируются один раз на вход в мир, не на
 * каждый вопрос), поиск — простое пересечение слов запроса с текстом записи (индекс —
 * тысячи, не миллионы записей, полноценная векторная база не нужна на этом масштабе).
 *
 * ИСПРАВЛЕНО (15.09.2026, см. отчёт "recipe-book почти пуст на JOIN"): попытка строить
 * рецепты на клиенте из {@code player.getRecipeBook()} (тип {@code ClientRecipeBook},
 * {@link net.minecraft.world.item.crafting.display.RecipeDisplayEntry}) оказалась не
 * просто проблемой тайминга — recipe-книга игрока это ЛИЧНЫЙ ПРОГРЕСС ("какие рецепты
 * игрок уже открыл"), а не полная база рецептов игры, и у клиента в MC 26.2 после
 * recipe-display rework вообще нет полноценного {@code RecipeManager} со всеми
 * рецептами (см. архив обсуждения). Рецепты теперь приходят готовым списком от сервера
 * ({@link RecipeCompendiumPayload}, см. {@code RecipeCompendiumSender} на сервере) — ВСЕ
 * рецепты игры, без фильтрации по прогрессу игрока (фильтр "без спойлеров" убран
 * 16.09.2026, см. javadoc {@code RecipeCompendiumSender} — ломал саму задачу базы
 * знаний из-за гонки по времени на JOIN и отсутствия дорассылки для рецептов,
 * открытых позже), клиент только раскладывает id в текст индекса, как раньше делал
 * для предметов.
 *
 * Предметы и рецепты индексируются и обновляются независимо (предметы синхронно на
 * JOIN, рецепты по приходу сетевого пакета чуть позже) — {@link #entries} каждый раз
 * пересобирается из обеих частей в {@link #recombine()}, чтобы {@link #search} не мог
 * увидеть частично обновлённое состояние.
 *
 * ИСПРАВЛЕНО (15.09.2026, "находка 1" из отчёта о компендиуме рецептов): имена
 * предметов/рецептов раньше резолвились через {@code new ItemStack(item).getHoverName()},
 * который читает {@link Language#getInstance()} — АКТИВНЫЙ язык клиента ({@code lang}
 * в {@code options.txt}). GigaAM распознаёт и {@code brain} отвечает только по-русски
 * (раздел 6 ТЗ), а язык интерфейса у игрока может быть любым (частый случай — {@code
 * en_us}) — с ним индекс собирался целиком по-английски, и вопрос на русском не находил
 * вообще ничего (живой тест 15.09.2026, см. ТЗ). Чисто "всегда резолвить в
 * ru_ru" не подошло бы — у части модовых предметов ru_ru-перевода просто нет, только
 * en_us (решение пользователя 15.09.2026).
 *
 * Теперь имена грузятся ЯВНО на ru_ru И en_us независимо от активного языка клиента —
 * {@link #reloadLanguages()}, тем же путём, которым сам {@code LanguageManager} строит
 * активный {@link Language} при смене языка ({@code ClientLanguage.loadFrom}, сверено
 * javap-разбором {@code LanguageManager.onResourceManagerReload} в
 * {@code minecraft-merged.jar}), но без {@code Language.inject(...)} — активный язык
 * клиента не трогается. Список кодов для ru_ru — {@code ["en_us", "ru_ru"]}: коды
 * применяются по порядку, каждый следующий перекрывает предыдущий по тем же ключам,
 * так что для непереведённых на русский предметов автоматически остаётся английское
 * имя (тот самый нужный фоллбэк, без отдельного кода на него). {@code searchKey} у
 * записи включает токены ИЗ ОБОИХ языков — вопрос на русском находит рецепт/предмет,
 * даже если у конкретного игрока в настройках {@code en_us}, и наоборот. Текст,
 * уходящий в промпт LLM ({@code text}), — только на "русском" (т.е. результат
 * ru_ru-резолвинга, который сам уже фоллбэчится на en_us) — компаньон всё равно
 * отвечает только по-русски, дублировать оба языка в промпте незачем.
 */
public final class GameKnowledgeIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");
    private static final int MAX_RESULTS = 5;
    private static final int MIN_TOKEN_LENGTH = 3;

    private record Entry(String searchKey, String text) {
    }

    private record ScoredEntry(Entry entry, int score) {
    }

    private static volatile List<Entry> itemEntries = List.of();
    private static volatile List<Entry> recipeEntries = List.of();
    private static volatile List<Entry> entries = List.of();

    /**
     * ru_ru/en_us переводы, независимые от активного языка клиента — см. javadoc
     * класса и {@link #reloadLanguages()}. {@link Language#getInstance()} как значение
     * по умолчанию ДО первого {@link #build()} (например, если что-то по ошибке
     * дёрнет индекс вне мира) — лучше активный язык клиента, чем NPE.
     */
    private static volatile Language enLanguage = Language.getInstance();
    private static volatile Language ruLanguage = Language.getInstance();

    private GameKnowledgeIndex() {
    }

    /** Вызывать на вход в мир (см. VerityClient, ClientPlayConnectionEvents.JOIN). */
    public static void build() {
        reloadLanguages();
        List<Entry> built = new ArrayList<>();
        try {
            indexItems(built);
        } catch (Exception e) {
            // Не должно ронять остальной мод — компаньон просто отвечает без базы
            // знаний, если реестр оказался не таким, как ожидалось.
            LOGGER.error("[NAGUMO-KNOWLEDGE] не удалось построить индекс предметов: {}", e.getMessage(), e);
            built = new ArrayList<>();
        }
        itemEntries = List.copyOf(built);
        recombine();
        LOGGER.info("[NAGUMO-KNOWLEDGE] индекс предметов построен: {} записей", itemEntries.size());
    }

    /** См. javadoc класса — грузит ru_ru/en_us в {@link #enLanguage}/{@link #ruLanguage}. */
    private static void reloadLanguages() {
        var resourceManager = Minecraft.getInstance().getResourceManager();
        try {
            enLanguage = ClientLanguage.loadFrom(resourceManager, List.of("en_us"), false);
            ruLanguage = ClientLanguage.loadFrom(resourceManager, List.of("en_us", "ru_ru"), false);
        } catch (Exception e) {
            // Не должно ронять индексацию целиком — активный язык клиента как фоллбэк
            // хуже, чем задуманное поведение, но лучше, чем вообще без имён.
            LOGGER.error("[NAGUMO-KNOWLEDGE] не удалось загрузить ru_ru/en_us независимо от языка клиента: {}",
                    e.getMessage(), e);
            enLanguage = Language.getInstance();
            ruLanguage = Language.getInstance();
        }
    }

    /**
     * Вызывать по приёму {@link RecipeCompendiumPayload} от сервера (см. VerityClient,
     * ClientPlayNetworking.registerGlobalReceiver) — приходит чуть позже JOIN, поэтому
     * отдельный метод, а не часть {@link #build()}.
     */
    public static void applyRecipes(List<RecipeCompendiumPayload.Entry> networkEntries) {
        List<Entry> built = new ArrayList<>();
        try {
            for (RecipeCompendiumPayload.Entry netEntry : networkEntries) {
                try {
                    indexOneRecipe(netEntry, built);
                } catch (Exception e) {
                    // Один нестандартный рецепт не должен рушить весь индекс.
                    LOGGER.debug("[NAGUMO-KNOWLEDGE] пропущен рецепт {}: {}", netEntry.resultId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[NAGUMO-KNOWLEDGE] не удалось разобрать компендиум рецептов: {}", e.getMessage(), e);
            built = new ArrayList<>();
        }
        recipeEntries = List.copyOf(built);
        recombine();
        LOGGER.info("[NAGUMO-KNOWLEDGE] индекс рецептов обновлён: {} записей (от сервера получено {})",
                recipeEntries.size(), networkEntries.size());
    }

    /** Раздел 11 ТЗ, шаг 5, тот же принцип что и у BrainEngine.reset() — сброс при выходе. */
    public static void clear() {
        itemEntries = List.of();
        recipeEntries = List.of();
        entries = List.of();
    }

    private static void recombine() {
        List<Entry> combined = new ArrayList<>(itemEntries.size() + recipeEntries.size());
        combined.addAll(itemEntries);
        combined.addAll(recipeEntries);
        entries = List.copyOf(combined);
    }

    private static void indexItems(List<Entry> out) {
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString(); // "minecraft:oak_planks" / "modid:thing"
            String key = item.getDescriptionId();
            String en = enLanguage.getOrDefault(key, id);
            String ru = ruLanguage.getOrDefault(key, id);
            if (en.isBlank() && ru.isBlank()) {
                continue;
            }
            out.add(new Entry(normalize(ru + " " + en + " " + id), "Предмет: " + ru + " (" + id + ")"));
        }
    }

    private static void indexOneRecipe(RecipeCompendiumPayload.Entry netEntry, List<Entry> out) {
        Item resultItem = BuiltInRegistries.ITEM.getValue(netEntry.resultId());
        String resultKey = resultItem.getDescriptionId();
        String resultEn = enLanguage.getOrDefault(resultKey, netEntry.resultId().toString());
        String resultRu = ruLanguage.getOrDefault(resultKey, netEntry.resultId().toString());
        if (resultEn.isBlank() && resultRu.isBlank()) {
            return;
        }

        // Отдельно "чистые" имена (для searchKey — без количества, оно там лишний шум
        // токенов) и отдельно "имя xN" (для текста в промпт LLM — количество слотов,
        // в которых предмет встречается в рецепте, "находка 2": раньше терялось при
        // дедупликации на сервере до подсчёта, см. javadoc RecipeCompendiumPayload).
        List<String> namesEn = new ArrayList<>();
        List<String> namesRu = new ArrayList<>();
        List<String> textsRu = new ArrayList<>();
        for (RecipeCompendiumPayload.IngredientCount ingredient : netEntry.ingredients()) {
            Item ingredientItem = BuiltInRegistries.ITEM.getValue(ingredient.id());
            if (ingredientItem == null) {
                continue;
            }
            String ingredientKey = ingredientItem.getDescriptionId();
            String en = enLanguage.getOrDefault(ingredientKey, ingredient.id().toString());
            String ru = ruLanguage.getOrDefault(ingredientKey, ingredient.id().toString());
            namesEn.add(en);
            namesRu.add(ru);
            textsRu.add(ru + " x" + ingredient.count());
        }
        if (namesRu.isEmpty()) {
            return;
        }

        String text = "Рецепт: " + resultRu + " x" + netEntry.resultCount() + " из: "
                + String.join(" + ", textsRu) + " (" + netEntry.categoryId().getPath() + ")";
        String searchKey = normalize(resultRu + " " + resultEn + " "
                + String.join(" ", namesRu) + " " + String.join(" ", namesEn));
        out.add(new Entry(searchKey, text));
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Топ-N релевантных записей по простому пересечению слов с вопросом игрока.
     * Вызывается из BrainEngine перед каждым обращением к LLM.
     */
    public static List<String> search(String query) {
        List<Entry> snapshot = entries;
        if (snapshot.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(normalize(query));
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        return snapshot.stream()
                .map(e -> new ScoredEntry(e, overlapScore(queryTokens, e.searchKey())))
                .filter(se -> se.score() > 0)
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .limit(MAX_RESULTS)
                .map(se -> se.entry().text())
                .toList();
    }

    private static Set<String> tokenize(String s) {
        return new HashSet<>(Arrays.asList(s.split("[^\\p{L}\\p{Nd}]+")));
    }

    private static int overlapScore(Set<String> queryTokens, String searchKey) {
        int score = 0;
        for (String token : queryTokens) {
            if (token.length() < MIN_TOKEN_LENGTH) {
                continue; // отсекаем короткие предлоги/союзы ("из", "на" и т.п.)
            }
            if (searchKey.contains(token)) {
                score++;
            }
        }
        return score;
    }
}
