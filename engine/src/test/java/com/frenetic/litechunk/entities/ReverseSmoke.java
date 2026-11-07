package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;

import java.util.Arrays;
import java.util.List;

/**
 * Desktop check for the reverse entity pieces that a world without the right actors cannot exercise: the leash
 * (no Bedrock save to hand has one) and the packed tropical fish variant.
 * <p>
 * Not a unit test: the project has no test framework wired up, and these are one-line assertions against the same
 * code the conversion runs.
 */
public final class ReverseSmoke {

    private ReverseSmoke() {
    }

    public static void main(String[] args) {
        Version version = Version.fromString("1.21.6");

        leash(version);
        tropicalFish();
        saddleFlag(version);
    }

    /**
     * A leash has to land on the same UUID the holder was written with, so the hash is checked twice and against a
     * different id.
     */
    private static void leash(Version version) {
        CompoundTag cow = new CompoundTag(3);
        cow.put("identifier", "minecraft:cow");
        cow.put("UniqueID", -42L);
        cow.put("LeasherID", 123456789L);

        CompoundTag out = new CompoundTag(2);
        JavaEntityLinks.apply(out, cow, version);
        CompoundTag leash = out.getCompound("leash", null);
        int[] first = leash == null ? null : leash.getIntArray("UUID", null);

        CompoundTag again = new CompoundTag(2);
        JavaEntityLinks.apply(again, cow, version);
        int[] second = again.getCompound("leash", null).getIntArray("UUID", null);

        System.out.println("leash present      = " + (first != null));
        System.out.println("leash uuid         = " + Arrays.toString(first));
        System.out.println("stable across runs = " + Arrays.equals(first, second));
        System.out.println("matches actor uuid = "
                + Arrays.equals(first, BedrockToJava.uuid(123456789L)));

        CompoundTag wild = new CompoundTag(1);
        wild.put("identifier", "minecraft:cow");
        CompoundTag untouched = new CompoundTag(1);
        JavaEntityLinks.apply(untouched, wild, version);
        System.out.println("no leash field     = " + (untouched.get("leash") == null));
    }

    private static void tropicalFish() {
        CompoundTag fish = new CompoundTag(4);
        fish.put("identifier", "minecraft:tropicalfish");
        fish.put("Color", (byte) 3);
        fish.put("Color2", (byte) 12);
        fish.put("MarkVariant", 4);

        CompoundTag out = new CompoundTag(2);
        JavaEntityVariants.apply(out, fish, "minecraft:tropicalfish", List.of());
        int packed = out.getInt("Variant", -1);
        int expected = (4 << 16) | (3 << 8) | 12;
        System.out.println("fish variant       = " + packed + " (expected " + expected + ")");
    }

    /**
     * A saddle is a flag on Bedrock and an item on Java; the item has to be the Java shape, which is written through
     * the resolver chain and therefore cannot run here. What can be checked is that the flag reaches the body slot.
     */
    private static void saddleFlag(Version version) {
        CompoundTag pig = new CompoundTag(3);
        pig.put("identifier", "minecraft:pig");
        pig.put("Saddled", (byte) 1);

        CompoundTag out = new CompoundTag(2);
        // No resolvers here, so the item conversion returns null and nothing is written - the point of the check is
        // that it does not throw and does not invent a half written saddle.
        JavaEntityStates.apply(out, pig, "minecraft:pig", version, null, null);
        System.out.println("saddle without item resolvers = " + (out.get("equipment") == null));
    }
}
