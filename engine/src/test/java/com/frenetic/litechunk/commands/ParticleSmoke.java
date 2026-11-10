package com.frenetic.litechunk.commands;

/**
 * Desktop check for the particle command rewrite: the mappings and the argument reshaping, printed side by side.
 * Not a unit test - the project has no test framework wired up, and these are one-line assertions against the same
 * code the conversion runs.
 */
public final class ParticleSmoke {

    private ParticleSmoke() {
    }

    public static void main(String[] args) {
        int failures = 0;

        // Java -> Bedrock ------------------------------------------------------------------------------------
        failures += check("simple",
                "particle minecraft:flame ~ ~1 ~ 0.1 0.1 0.1 0.05 20",
                ParticleCommand.toBedrock("particle minecraft:flame ~ ~1 ~ 0.1 0.1 0.1 0.05 20"),
                "particle minecraft:basic_flame_particle ~ ~1 ~");

        failures += check("1.20.5 options",
                "particle minecraft:dust{color:[1.0,0.0,0.0],scale:1.0} ~ ~ ~ 0 0 0 0 1",
                ParticleCommand.toBedrock("particle minecraft:dust{color:[1.0,0.0,0.0],scale:1.0} ~ ~ ~ 0 0 0 0 1"),
                "particle minecraft:redstone_wire_dust_particle ~ ~ ~");

        failures += check("pre-1.20.5 options",
                "particle dust 1 0 0 1 ~ ~1 ~ 0 0 0 0 1",
                ParticleCommand.toBedrock("particle dust 1 0 0 1 ~ ~1 ~ 0 0 0 0 1"),
                "particle minecraft:redstone_wire_dust_particle ~ ~1 ~");

        failures += check("short form",
                "particle flame ~ ~ ~",
                ParticleCommand.toBedrock("particle flame ~ ~ ~"),
                "particle minecraft:basic_flame_particle ~ ~ ~");

        failures += check("leading slash",
                "/particle minecraft:totem_of_undying ~ ~ ~ 0 0 0 0 1",
                ParticleCommand.toBedrock("/particle minecraft:totem_of_undying ~ ~ ~ 0 0 0 0 1"),
                "particle minecraft:totem_particle ~ ~ ~");

        failures += check("no bedrock counterpart",
                "particle minecraft:ash ~ ~ ~ 0 0 0 0 1",
                ParticleCommand.toBedrock("particle minecraft:ash ~ ~ ~ 0 0 0 0 1"),
                null);

        failures += check("not a particle command",
                "setblock ~ ~ ~ stone",
                ParticleCommand.toBedrock("setblock ~ ~ ~ stone"),
                null);

        failures += check("execute wrapper",
                "execute at @e run particle flame ~ ~ ~ 0 0 0 0 1",
                ParticleCommand.toBedrock("execute at @e run particle flame ~ ~ ~ 0 0 0 0 1"),
                "execute at @e run particle minecraft:basic_flame_particle ~ ~ ~");

        failures += check("execute with untranslatable tail",
                "execute at @e run particle ash ~ ~ ~ 0 0 0 0 1",
                ParticleCommand.toBedrock("execute at @e run particle ash ~ ~ ~ 0 0 0 0 1"),
                null);

        // Bedrock -> Java ------------------------------------------------------------------------------------
        failures += check("reverse",
                "particle minecraft:basic_flame_particle ~ ~1 ~",
                ParticleCommand.toJava("particle minecraft:basic_flame_particle ~ ~1 ~"),
                "particle minecraft:flame ~ ~1 ~ 0 0 0 1 1");

        failures += check("reverse without position",
                "particle minecraft:totem_particle",
                ParticleCommand.toJava("particle minecraft:totem_particle"),
                "particle minecraft:totem_of_undying ~ ~ ~ 0 0 0 1 1");

        failures += check("reverse unknown",
                "particle minecraft:balloon_gas_particle ~ ~ ~",
                ParticleCommand.toJava("particle minecraft:balloon_gas_particle ~ ~ ~"),
                null);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
    }

    private static int check(String name, String input, String actual, String expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) {
            System.out.println("      in       " + input);
            System.out.println("      expected " + expected);
            System.out.println("      actual   " + actual);
            return 1;
        }
        return 0;
    }
}
