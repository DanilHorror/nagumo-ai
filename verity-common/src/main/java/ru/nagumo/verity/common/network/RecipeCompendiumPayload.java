package ru.nagumo.verity.common.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.nagumo.verity.common.VerityCommon;

import java.util.List;

/**
 * Сервер -> клиент: рецепты для базы знаний компаньона (обсуждение
 * 15.09.2026, см. {@code GameKnowledgeIndex}). Заменяет собой попытку клиента строить
 * список рецептов самостоятельно из {@code player.getRecipeBook()} на JOIN — на клиенте
 * в MC 26.2 после recipe-display rework нет полноценного {@code RecipeManager}
 * (см. javadoc {@code GameKnowledgeIndex}), а сама recipe-книга игрока в момент JOIN
 * почти всегда пуста (свежий вход/переподключение). Сервер же держит полный
 * {@code RecipeManager} не подвержен этой переработке — он просто собирает и шлёт
 * готовый список сюда.
 *
 * Список — ВСЕ рецепты игры, без фильтрации по прогрессу игрока. Раньше (15.09.2026)
 * здесь был фильтр "без спойлеров" (пересечение с {@code ServerRecipeBook} конкретного
 * игрока), но он ломал саму задачу базы знаний: {@code RecipeCompendiumSender} шлёт
 * компендиум синхронно на JOIN, а unlock-триггеры recipe-book (даже тривиальные,
 * срабатывающие без действий игрока — см. javadoc {@code RecipeCompendiumSender})
 * обрабатываются сервером чуть позже, рецепт физически не успевал попасть в список; и
 * рецепты, открытые ПОЗЖЕ в процессе игры, никогда не пересылались повторно. Убран
 * 16.09.2026 (обсуждение, расследование "верстак из цветов и кактуса",
 * ТЗ) — компаньон приватный (раздел 2 ТЗ), "спойлеры" здесь не защита от других
 * игроков, а просто содержимое системного промпта LLM.
 *
 * Каждая запись — сырые id (результат, количество, ингредиенты, категория), а не
 * готовый текст: текст (в т.ч. локализованные названия предметов) клиент и так умеет
 * строить сам из {@code BuiltInRegistries.ITEM} (см. {@code GameKnowledgeIndex.indexItems}) —
 * так пакет меньше и не дублирует то, что у клиента уже есть.
 */
public record RecipeCompendiumPayload(List<Entry> recipes) implements CustomPacketPayload {

    /**
     * Один резолвленный рецепт (раскладка {@code RecipeDisplayEntry} на клиент-совместимые
     * id, сделанная сервером через {@code RecipeManager.listDisplaysForRecipe} +
     * {@code RecipeDisplayEntry.resultItems}/{@code craftingRequirements} — тот же API,
     * которым раньше пользовался клиент, просто теперь вызывается на сервере, где
     * {@code RecipeManager} полноценный).
     *
     * По ингредиентам, как и в прежней клиентской реализации: берём только первый предмет
     * из каждого {@code Ingredient} (рецепты с тегами/альтернативами упрощаются до одного
     * примера) и рецепты без обычных предметных ингредиентов (кузнечные шаблоны и т.п.)
     * до клиента не доходят вовсе — отфильтрованы уже на сервере.
     */
    public record Entry(Identifier resultId, int resultCount, List<IngredientCount> ingredients, Identifier categoryId) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Entry::resultId,
                ByteBufCodecs.VAR_INT, Entry::resultCount,
                IngredientCount.CODEC.apply(ByteBufCodecs.list()), Entry::ingredients,
                Identifier.STREAM_CODEC, Entry::categoryId,
                Entry::new
        );
    }

    /**
     * Один ингредиент с количеством слотов, в которых он встречается в рецепте
     * (обсуждение 15.09.2026, "находка 2" — раньше {@code Ingredient}
     * на каждый слот схлопывался в {@code List<Identifier>} через {@code .distinct()}
     * ДО подсчёта, и для рецептов с несколькими слотами одного и того же предмета
     * (печь — 8 слотов булыжника) количество терялось совсем: факт в промпте LLM был
     * "из: Булыжник" без числа, откуда и часть проблемы с неверными ответами про
     * рецепты). {@code count} — сколько раз этот предмет встретился среди слотов
     * рецепта ДО дедупликации (считается на сервере, {@code RecipeCompendiumSender}).
     */
    public record IngredientCount(Identifier id, int count) {

        public static final StreamCodec<RegistryFriendlyByteBuf, IngredientCount> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, IngredientCount::id,
                ByteBufCodecs.VAR_INT, IngredientCount::count,
                IngredientCount::new
        );
    }

    public static final CustomPacketPayload.Type<RecipeCompendiumPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VerityCommon.MOD_ID, "recipe_compendium"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeCompendiumPayload> CODEC =
            Entry.CODEC.apply(ByteBufCodecs.list()).map(RecipeCompendiumPayload::new, RecipeCompendiumPayload::recipes);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
