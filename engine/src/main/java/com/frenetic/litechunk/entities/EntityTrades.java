package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Writes the trade list of a villager or a wandering trader.
 * <p>
 * Until now the entity pass only wrote {@code TradeTier} and {@code TradeExperience} and let Bedrock build its own
 * offers from the profession, which means a player's own trades - the ones they paid for, restocked and levelled up -
 * were replaced by an unrelated, freshly generated set. Bedrock does accept an explicit list: it is the {@code Offers}
 * compound, whose {@code Recipes} entries are the same items the behaviour pack's trade tables are compiled from.
 * <p>
 * <strong>When it is written.</strong> Bedrock's {@code Offers} is a replacement, not a merge: once the tag is there the
 * game shows exactly those recipes and no longer generates anything from the profession. So a villager who has never
 * been traded with is left alone (the game keeps doing a better job than a copy of Java's list could), while a villager
 * with even one used recipe carries its <em>whole</em> list - writing only the used entries would hand the player a
 * villager that lost the rest of its trades, which is worse than the current behaviour.
 * <p>
 * <strong>Where the key names come from.</strong> {@code buyA}, {@code buyB}, {@code traderExp}, {@code rewardExp},
 * {@code priceMultiplierA}, {@code maxUses} and {@code TierExpRequirements} all appear in the string table of
 * {@code libminecraftpe.so} (1.26.60.4), and the vanilla trade tables inside the same APK use the matching vocabulary
 * ({@code wants}/{@code gives}/{@code trader_exp}/{@code max_uses}/{@code price_mulitplier}). Java's {@code demand}
 * and {@code specialPrice} have no counterpart there and are dropped.
 */
final class EntityTrades {

    private static final String OFFERS = "Offers";
    private static final String RECIPES = "Recipes";

    /**
     * Java keeps no tier on a recipe; the list is ordered by the level it unlocks at and vanilla hands out two per
     * level, so the position is what the tier has to be recovered from.
     */
    private static final int RECIPES_PER_TIER = 2;

    /** Bedrock's trade UI has five tiers, novice to master. */
    private static final int MAX_TIER = 4;

    private EntityTrades() {
    }

    /**
     * Write the trade list, if this entity has one worth carrying over.
     *
     * @param actor       the actor tag being built, modified in place.
     * @param identifier  the Bedrock identifier.
     * @param javaEntity  the Java entity NBT.
     * @param source      resolvers of the Java version that was read, may be null.
     * @param target      resolvers of the Bedrock version being written, may be null.
     */
    static void apply(CompoundTag actor, String identifier, CompoundTag javaEntity,
                      @Nullable JavaResolvers source, @Nullable BedrockResolvers target) {
        // Recipes hold items, and an item needs both ends of the version pair to be translated.
        if (source == null || target == null) {
            return;
        }
        apply(actor, identifier, javaEntity, item -> ItemStacks.convert(item, source, target));
    }

    /**
     * The same conversion with the item translator supplied by the caller, so the recipe mapping can be checked
     * without a version pair.
     *
     * @param actor      the actor tag being built, modified in place.
     * @param identifier the Bedrock identifier.
     * @param javaEntity the Java entity NBT.
     * @param items      translates one Java item stack, returning null when it cannot be represented.
     */
    static void apply(CompoundTag actor, String identifier, CompoundTag javaEntity, Items items) {
        CompoundTag offers = javaEntity.getCompound(OFFERS, null);
        if (offers == null) {
            return;
        }
        ListTag<CompoundTag, Map<String, Tag<?>>> recipes = offers.getList(RECIPES, CompoundTag.class, null);
        if (recipes == null || recipes.size() == 0) {
            return;
        }

        // Never traded with: leave the tag out and let the game generate the profession's own list.
        if (!wasTraded(recipes)) {
            return;
        }

        // A wandering trader has one flat list with no levels; only a villager's recipes are tiered.
        boolean tiered = !"minecraft:wandering_trader".equals(identifier);

        ListTag<CompoundTag, Map<String, Tag<?>>> output = new ListTag<>(TagType.COMPOUND, recipes.size());
        int index = 0;
        for (CompoundTag javaRecipe : recipes) {
            // The tier comes from the position in the original list, so it is taken before a dropped recipe can shift
            // everything after it up.
            int tier = tiered ? Math.min(index / RECIPES_PER_TIER, MAX_TIER) : 0;
            index++;
            CompoundTag recipe = convert(javaRecipe, tier, items);
            if (recipe != null) {
                output.add(recipe);
            }
        }

        if (output.size() == 0) {
            return;
        }

        CompoundTag written = new CompoundTag(1);
        written.put(RECIPES, output);
        actor.put(OFFERS, written);
    }

    /**
     * @param recipes the Java recipe list.
     * @return true when the player has used at least one of them.
     */
    private static boolean wasTraded(ListTag<CompoundTag, Map<String, Tag<?>>> recipes) {
        for (CompoundTag recipe : recipes) {
            if (intValue(recipe, "uses", 0) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Translate one recipe.
     *
     * @param javaRecipe the Java {@code Recipes} entry.
     * @param tier       the zero based tier to write.
     * @param items      the item translator.
     * @return the Bedrock entry, or null when either side of the trade cannot be represented.
     */
    @Nullable
    private static CompoundTag convert(CompoundTag javaRecipe, int tier, Items items) {
        CompoundTag buy = items.convert(javaRecipe.getCompound("buy", null));
        CompoundTag sell = items.convert(javaRecipe.getCompound("sell", null));
        if (buy == null || sell == null) {
            // Half a trade is not a trade: Bedrock has no way to express a recipe with a missing side, and keeping one
            // would shift the tier of everything after it.
            return null;
        }

        CompoundTag recipe = new CompoundTag(12);
        recipe.put("buyA", buy);

        // The second input is optional; the field is left out rather than written as an empty stack.
        CompoundTag buyB = items.convert(javaRecipe.getCompound("buyB", null));
        if (buyB != null) {
            recipe.put("buyB", buyB);
        }

        recipe.put("sell", sell);
        recipe.put("maxUses", intValue(javaRecipe, "maxUses", 4));
        recipe.put("uses", intValue(javaRecipe, "uses", 0));

        // Java calls the experience the villager earns `xp`, and stores the "give the player experience" flag next to
        // it; Bedrock keeps the same two under different names.
        recipe.put("traderExp", intValue(javaRecipe, "xp", 0));
        recipe.put("rewardExp", (byte) (flag(javaRecipe, "rewardExp", true) ? 1 : 0));

        // Java has a single price multiplier for the whole recipe, which is the A side of Bedrock's pair.
        recipe.put("priceMultiplierA", floatValue(javaRecipe, "priceMultiplier", 0F));
        recipe.put("tier", tier);
        return recipe;
    }

    /**
     * Java stores counters as bytes on some entities and ints on others, so they are read as numbers.
     */
    private static int intValue(CompoundTag recipe, String name, int defaultValue) {
        Object value = recipe.contains(name) ? recipe.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static float floatValue(CompoundTag recipe, String name, float defaultValue) {
        Object value = recipe.contains(name) ? recipe.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.floatValue() : defaultValue;
    }

    private static boolean flag(CompoundTag recipe, String name, boolean defaultValue) {
        Object value = recipe.contains(name) ? recipe.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.byteValue() != 0 : defaultValue;
    }

    /**
     * Translates one item stack of a recipe. In the conversion this is the engine's own item resolver, which is what
     * keeps enchantments, custom names and components on the trade; a test can supply a simpler one.
     */
    interface Items {
        /**
         * @param javaItem the Java item tag, may be null.
         * @return the Bedrock item tag, or null when the stack cannot be represented.
         */
        @Nullable
        CompoundTag convert(@Nullable CompoundTag javaItem);
    }
}
