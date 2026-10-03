package ru.nagumo.verity.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import ru.nagumo.verity.common.network.RecipeCompendiumPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сборка и отправка {@link RecipeCompendiumPayload} игроку на JOIN (см.
 * {@link VerityServer#onInitialize}). Раздел "база знаний компаньона" (обсуждение с
 * пользователем 15.09.2026) — до этого клиент пытался строить список рецептов сам из
 * {@code player.getRecipeBook()} (клиентский {@code ClientRecipeBook}), но на входе в
 * мир он почти всегда пуст (recipe-book пакеты ещё не долетели/только начинают
 * заполняться) — реальные данные есть только здесь, на сервере, у полноценного
 * {@link RecipeManager}.
 *
 * ИСПРАВЛЕНО (16.09.2026, расследование "верстак из цветов и кактуса", ТЗ):
 * раньше здесь был фильтр "без спойлеров" — в компендиум попадали только рецепты,
 * которые ИМЕННО ЭТОТ игрок уже открыл сам ({@code ServerRecipeBook.contains} по
 * каждому {@link RecipeHolder#id()}). Оказалось, что это ломает саму задачу базы
 * знаний ДВАЖДЫ: (1) `sendTo` вызывается синхронно один раз на `JOIN`, а
 * unlock-триггеры recipe-book (даже тривиальные типа {@code unlock_right_away} у
 * {@code crafting_table}, срабатывающие "сами" без каких-либо действий игрока)
 * обрабатываются сервером чуть позже — рецепт физически не успевал попасть в
 * компендиум к моменту отправки; (2) рецепты, открытые ПОЗЖЕ в процессе игры, никогда
 * не пересылались повторно — `sendTo` не событийный. Пересобирать на событийную
 * дорассылку (переслать компендиум заново при каждом новом анлоке) — отдельная,
 * более сложная задача; пользователь решил (16.09.2026) вместо неё просто убрать
 * фильтр целиком — компендиум = ВСЕ рецепты игры для ВСЕХ игроков без разбора,
 * открыты они или нет. "Спойлеры" тут не защита от игроков (компаньон приватный,
 * раздел 2 ТЗ), а просто содержимое системного промпта LLM — то, что игрок формально
 * не открыл, ему не показывается в интерфейсе игры, компаньон просто может ответить
 * на вопрос про это заранее.
 *
 * Раз компендиум больше не зависит от игрока (ни от recipe-book, ни от {@code Level}
 * конкретного игрока — {@link ContextMap} строится один раз от {@code overworld()})
 * — он одинаковый для всех, поэтому собирается ОДИН РАЗ при старте сервера
 * ({@link #buildOnce}, вызывается из {@code ServerLifecycleEvents.SERVER_STARTED} в
 * {@code VerityServer}), а не пересчитывается на каждый `JOIN`.
 *
 * Раскладка рецепта на id (результат/ингредиенты/категория) через
 * {@link RecipeManager#listDisplaysForRecipe} + {@link RecipeDisplayEntry#resultItems}/
 * {@link RecipeDisplayEntry#craftingRequirements} — тот же API и та же логика
 * "первый предмет из каждого Ingredient", что раньше была в клиентском
 * {@code GameKnowledgeIndex.indexOneRecipe}, просто выполняется здесь, где
 * {@code RecipeManager} полноценный (recipe-display rework задел только клиент).
 */
public final class RecipeCompendiumSender {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    /**
     * Кэш — один на весь сервер (см. javadoc класса, фильтр по игроку убран, компендиум
     * у всех одинаковый). {@code null} до первого {@link #buildOnce}; {@link #sendTo}
     * на этот случай сам вызывает {@link #buildOnce} лениво (safety net, если почему-то
     * {@code SERVER_STARTED} не отработал раньше первого `JOIN`).
     */
    private static volatile List<RecipeCompendiumPayload.Entry> cachedEntries;

    private RecipeCompendiumSender() {
    }

    /** Вызывать один раз при старте сервера ({@code ServerLifecycleEvents.SERVER_STARTED}). */
    public static void buildOnce(MinecraftServer server) {
        List<RecipeCompendiumPayload.Entry> entries = build(server);
        cachedEntries = entries;
        LOGGER.info("[NAGUMO-KNOWLEDGE] компендиум рецептов собран один раз при старте сервера: {} записей",
                entries.size());
    }

    public static void sendTo(ServerPlayer player, MinecraftServer server) {
        try {
            List<RecipeCompendiumPayload.Entry> entries = cachedEntries;
            if (entries == null) {
                // Ленивая сборка на случай, если buildOnce почему-то не вызвался раньше
                // (не должно происходить при нормальном порядке инициализации, но лучше
                // не оставлять игрока без базы знаний вообще).
                LOGGER.warn("[NAGUMO-KNOWLEDGE] компендиум не был собран заранее — собираю сейчас, на JOIN {}",
                        player.getName().getString());
                entries = build(server);
                cachedEntries = entries;
            }
            ServerPlayNetworking.send(player, new RecipeCompendiumPayload(entries));
            LOGGER.info("[NAGUMO-KNOWLEDGE] игроку {} отправлено {} рецептов",
                    player.getName().getString(), entries.size());
        } catch (Exception e) {
            // Не должно ронять вход игрока на сервер — компаньон просто останется без
            // базы знаний по рецептам в этой сессии, как и было до этого изменения.
            LOGGER.error("[NAGUMO-KNOWLEDGE] не удалось отправить компендиум рецептов для {}: {}",
                    player.getName().getString(), e.getMessage(), e);
        }
    }

    private static List<RecipeCompendiumPayload.Entry> build(MinecraftServer server) {
        RecipeManager recipeManager = server.getRecipeManager();
        ContextMap context = SlotDisplayContext.fromLevel(server.overworld());

        List<RecipeCompendiumPayload.Entry> out = new ArrayList<>();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            ResourceKey<Recipe<?>> key = holder.id();
            recipeManager.listDisplaysForRecipe(key, display -> {
                try {
                    RecipeCompendiumPayload.Entry entry = toEntry(display, context);
                    if (entry != null) {
                        out.add(entry);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[NAGUMO-KNOWLEDGE] пропущен рецепт {}: {}", display.id(), e.getMessage());
                }
            });
        }
        return out;
    }

    private static RecipeCompendiumPayload.Entry toEntry(RecipeDisplayEntry display, ContextMap context) {
        List<ItemStack> results = display.resultItems(context);
        if (results.isEmpty() || results.get(0).isEmpty()) {
            return null;
        }
        ItemStack result = results.get(0);
        Identifier resultId = BuiltInRegistries.ITEM.getKey(result.getItem());

        // Раньше здесь дедуплицировали id ДО подсчёта — для рецептов с несколькими
        // слотами одного и того же предмета (печь — 8 слотов булыжника) количество
        // терялось совсем (см. javadoc RecipeCompendiumPayload.IngredientCount,
        // "находка 2"). Считаем количество по каждому id СНАЧАЛА (один Ingredient —
        // один слот крафт-сетки), группировку/дедуп получаем от самого Map.
        List<Ingredient> ingredients = display.craftingRequirements().orElse(List.of());
        Map<Identifier, Integer> ingredientCounts = new LinkedHashMap<>();
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) {
                continue;
            }
            ing.items().findFirst()
                    .map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()))
                    .ifPresent(id -> ingredientCounts.merge(id, 1, Integer::sum));
        }
        if (ingredientCounts.isEmpty()) {
            return null; // рецепты без обычных предметных ингредиентов (кузнечные шаблоны и т.п.) пропускаем
        }
        List<RecipeCompendiumPayload.IngredientCount> ingredientList = ingredientCounts.entrySet().stream()
                .map(e -> new RecipeCompendiumPayload.IngredientCount(e.getKey(), e.getValue()))
                .toList();

        Identifier categoryId = BuiltInRegistries.RECIPE_BOOK_CATEGORY.getKey(display.category());
        return new RecipeCompendiumPayload.Entry(resultId, result.getCount(), ingredientList, categoryId);
    }
}
