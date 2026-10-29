package com.frenetic.litechunk.engine;

import com.frenetic.litechunk.api.LevelField;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.LevelNote;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockDataVersion;
import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and rewrites the parts of {@code level.dat} that are safe to touch.
 * <p>
 * Why this exists: a large share of "my world will not open" requests are not conversions at all, they are one wrong
 * number in a 2 KB header. Sending those through the full pipeline re-encodes every chunk, which takes minutes and
 * silently replaces identifiers the engine cannot represent. Editing the header alone takes milliseconds and leaves
 * the chunks untouched, so it is both faster and strictly less lossy.
 * <p>
 * The allow list is the whole safety story. Writing an arbitrary NBT node from a phone invites a byte/int mismatch or
 * a list where the game expects a compound, and the resulting world is indistinguishable from a corrupt conversion.
 * Every editable field below is therefore type-checked and, where the value space is small, restricted to an enum.
 * Fields that look editable but cannot be fixed by editing the header ({@code DataVersion}, {@code StorageVersion})
 * are shown read-only with an explanation, because raising them does not upgrade the chunk data behind them.
 */
final class LevelEditor {

    private static final String LEVEL_DAT = "level.dat";

    private final File directory;
    private final File levelDat;
    private final boolean bedrock;
    private final CompoundTag data;
    private final int storageVersion;

    private LevelEditor(File directory, File levelDat, boolean bedrock, CompoundTag data, int storageVersion) {
        this.directory = directory;
        this.levelDat = levelDat;
        this.bedrock = bedrock;
        this.data = data;
        this.storageVersion = storageVersion;
    }

    // ---------------------------------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------------------------------

    static LevelEditor open(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            throw new IOException("目录不存在：" + directory);
        }
        File levelDat = new File(directory, LEVEL_DAT);
        if (!levelDat.isFile()) {
            throw new IOException("目录中找不到 level.dat");
        }
        boolean bedrock = looksBedrock(directory, levelDat);
        int storageVersion = bedrock ? readStorageVersion(levelDat) : 0;
        CompoundTag root = read(bedrock, levelDat);
        if (root == null) {
            throw new IOException("level.dat 无法解析（文件为空或格式不完整）");
        }
        return new LevelEditor(directory, levelDat, bedrock, root, storageVersion);
    }

    /**
     * Guess the edition from the surrounding folder, falling back to the file magic.
     * <p>
     * The folder names are far more reliable than the bytes: a Bedrock {@code level.dat} starts with a little endian
     * storage version, while Java's starts with the gzip magic or a bare {@code TAG_Compound}, and an uncompressed
     * Java file is indistinguishable from a Bedrock header by size alone.
     */
    private static boolean looksBedrock(File directory, File levelDat) {
        if (new File(directory, "db").isDirectory()) {
            return true;
        }
        if (new File(directory, "region").isDirectory()
                || new File(directory, "DIM-1").isDirectory()
                || new File(directory, "DIM1").isDirectory()
                || new File(directory, "data").isDirectory()) {
            return false;
        }
        try (InputStream in = new FileInputStream(levelDat)) {
            int first = in.read();
            int second = in.read();
            return !(first == 0x1f && second == 0x8b);
        } catch (IOException exception) {
            return false;
        }
    }

    /** Bedrock stores the storage version as a little endian int in the 8 byte header. */
    private static int readStorageVersion(File file) throws IOException {
        byte[] header = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(header) != header.length) {
                return 0;
            }
        }
        return (header[0] & 0xFF) | (header[1] & 0xFF) << 8 | (header[2] & 0xFF) << 16 | (header[3] & 0xFF) << 24;
    }

    private static CompoundTag read(boolean bedrock, File file) throws IOException {
        // Java's helper strips the outer "Data" wrapper; the writer puts it back, see write().
        return bedrock ? Tag.readBedrockNBT(file) : Tag.readPossibleGZipJavaNBT(file);
    }

    // ---------------------------------------------------------------------------------------------
    // Tolerant readers
    //
    // NBT is explicitly typed and the same key is not always the same width: Java stores Difficulty and
    // allowCommands as bytes while Bedrock stores Difficulty as an int. CompoundTag.getInt() casts, so a
    // narrow tag throws ClassCastException instead of widening. Every read therefore goes through the raw
    // tag and the Number interface, which reads any width without caring which one the save used.
    // ---------------------------------------------------------------------------------------------

    private Object raw(String id) {
        return raw(data, id);
    }

    private static Object raw(CompoundTag tag, String id) {
        if (!tag.contains(id)) {
            return null;
        }
        Tag<?> value = tag.get(id);
        return value == null ? null : value.getBoxedValue();
    }

    private long number(String id, long fallback) {
        Object value = raw(id);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private boolean truthy(String id) {
        Object value = raw(id);
        return value instanceof Number && ((Number) value).intValue() != 0;
    }

    /** Any scalar as text; arrays and lists are summarised rather than dumped, they are not editable anyway. */
    private String textOf(String id) {
        Object value = raw(id);
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return ((byte[]) value).length + " bytes";
        }
        if (value instanceof int[]) {
            return ((int[]) value).length + " ints";
        }
        if (value instanceof long[]) {
            return ((long[]) value).length + " longs";
        }
        if (value instanceof java.util.List) {
            return ((java.util.List<?>) value).size() + " entries";
        }
        if (value instanceof CompoundTag) {
            return "{...}";
        }
        return String.valueOf(value);
    }

    // ---------------------------------------------------------------------------------------------
    // Inspect
    // ---------------------------------------------------------------------------------------------

    LevelInfo inspect() {
        List<LevelField> fields = bedrock ? bedrockFields() : javaFields();
        List<LevelNote> notes = new ArrayList<>();

        if (bedrock) {
            describeExperiments(notes);
            if (storageVersion <= 0) {
                notes.add(new LevelNote(LevelNote.SEVERITY_WARN,
                        "文件头里的存储版本无效（" + storageVersion + "），游戏可能拒绝载入。",
                        "The storage version in the header is invalid (" + storageVersion
                                + "); the game may refuse to load the world."));
            }
        } else {
            int dataVersion = (int) number("DataVersion", -1);
            if (dataVersion <= 0) {
                notes.add(new LevelNote(LevelNote.SEVERITY_WARN,
                        "缺少 DataVersion，Java 版会按最旧格式尝试读取，通常直接失败。",
                        "DataVersion is missing; Java will assume the oldest format, which usually fails."));
            }
            if (truthy("hardcore")) {
                notes.add(new LevelNote(LevelNote.SEVERITY_INFO,
                        "这是极限模式存档，死亡后无法重生。",
                        "This is a hardcore world: death is permanent."));
            }
        }

        String name = textOf("LevelName");
        if (name.trim().isEmpty()) {
            notes.add(new LevelNote(LevelNote.SEVERITY_WARN,
                    "存档名称为空，游戏里会显示为 Unknown。",
                    "The world name is empty and will show as Unknown in game."));
        }

        if (bedrock && !data.contains("SpawnY")) {
            notes.add(new LevelNote(LevelNote.SEVERITY_INFO,
                    "没有记录出生点高度，进入世界时可能被放在地表随机位置。",
                    "No spawn height is recorded, so the game may place the player somewhere random."));
        }

        // Most severe first, so the screen can show the important line without scrolling.
        notes.sort((left, right) -> Integer.compare(right.severity, left.severity));

        return new LevelInfo(bedrock ? "BEDROCK" : "JAVA", bedrock ? "基岩版" : "Java 版",
                resolveVersion(), fields, notes);
    }

    /**
     * Bedrock keys every enabled experiment as a byte flag, and a missing flag is why newly added blocks come out as
     * air after a conversion. Showing them turns an invisible failure into something the user can check.
     */
    private void describeExperiments(List<LevelNote> notes) {
        CompoundTag experiments = data.getCompound("experiments");
        if (experiments == null) {
            return;
        }
        List<String> enabled = new ArrayList<>();
        for (Map.Entry<String, Tag<?>> entry : experiments.getBoxedValue().entrySet()) {
            Object flag = entry.getValue().getBoxedValue();
            if (flag instanceof Number && ((Number) flag).intValue() == 1) {
                enabled.add(entry.getKey());
            }
        }
        if (enabled.isEmpty()) {
            return;
        }
        String joined = String.join(", ", enabled);
        notes.add(new LevelNote(LevelNote.SEVERITY_INFO,
                "已开启实验性玩法：" + joined + "。关掉会造成新方块变成空气。",
                "Experiments enabled: " + joined + ". Turning them off makes newer blocks turn into air."));
    }

    /** Map the data version back to a release name, so the screen can say "1.21.6" instead of "3953". */
    private String resolveVersion() {
        try {
            if (bedrock) {
                int protocol = (int) number("NetworkVersion", -1);
                if (protocol <= 0) {
                    return null;
                }
                return BedrockDataVersion.getNearestProtocol(protocol).getVersion().toString();
            }
            int dataVersion = (int) number("DataVersion", -1);
            if (dataVersion <= 0) {
                return null;
            }
            return JavaDataVersion.getNearestVersion(dataVersion).getVersion().toString();
        } catch (RuntimeException ignored) {
            // A world saved by a newer game than this build knows about has no entry in the table.
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Field tables
    // ---------------------------------------------------------------------------------------------

    private List<LevelField> javaFields() {
        List<LevelField> fields = new ArrayList<>();
        fields.add(text("LevelName", "存档名称", "Level name / LevelName"));
        fields.add(choice("GameType", "游戏模式", "Game mode / GameType",
                new String[]{"0", "1", "2", "3"},
                new String[]{"生存", "创造", "冒险", "旁观"},
                new String[]{"Survival", "Creative", "Adventure", "Spectator"}, null, null));
        fields.add(choice("Difficulty", "难度", "Difficulty",
                new String[]{"0", "1", "2", "3"},
                new String[]{"和平", "简单", "普通", "困难"},
                new String[]{"Peaceful", "Easy", "Normal", "Hard"}, null, null));
        fields.add(flag("allowCommands", "允许作弊", "Allow commands"));
        fields.add(flag("hardcore", "极限模式", "Hardcore"));
        fields.add(new LevelField("RandomSeed", "世界种子", "Seed",
                textOf("RandomSeed"), LevelField.KIND_LONG, null, null, null,
                "1.16 起种子实际存放在 WorldGenSettings 里；而且种子只影响尚未生成的区块。",
                "Since 1.16 the seed lives under WorldGenSettings, and either way it only affects chunks that "
                        + "have not been generated yet."));
        fields.add(whole("SpawnX", "出生点 X", "Spawn X", LevelField.KIND_INT));
        fields.add(whole("SpawnY", "出生点 Y", "Spawn Y", LevelField.KIND_INT));
        fields.add(whole("SpawnZ", "出生点 Z", "Spawn Z", LevelField.KIND_INT));
        fields.add(whole("DayTime", "世界时间", "Time of day", LevelField.KIND_LONG));
        fields.add(new LevelField("DataVersion", "数据版本", "Data version / DataVersion",
                Integer.toString((int) number("DataVersion", -1)), LevelField.KIND_READ_ONLY, null, null, null,
                "只改这个数字不会升级区块数据。降级打开旧存档仍需完整转换。",
                "Changing this number does not upgrade the chunk data. Opening an older save still needs a full conversion."));
        return fields;
    }

    private List<LevelField> bedrockFields() {
        List<LevelField> fields = new ArrayList<>();
        fields.add(text("LevelName", "存档名称", "Level name / LevelName"));
        fields.add(choice("GameType", "游戏模式", "Game mode / GameType",
                new String[]{"0", "1", "2", "6"},
                new String[]{"生存", "创造", "冒险", "旁观"},
                new String[]{"Survival", "Creative", "Adventure", "Spectator"}, null, null));
        fields.add(choice("Difficulty", "难度", "Difficulty",
                new String[]{"0", "1", "2", "3"},
                new String[]{"和平", "简单", "普通", "困难"},
                new String[]{"Peaceful", "Easy", "Normal", "Hard"}, null, null));
        fields.add(flag("commandsEnabled", "允许作弊", "Allow cheats"));
        fields.add(choice("Generator", "世界类型", "World type / Generator",
                new String[]{"0", "1", "2", "3"},
                new String[]{"旧世界", "无限", "平坦", "虚空"},
                new String[]{"Legacy", "Infinite", "Flat", "Void"},
                "只是声明，改它不会重生地形。转换沿用源存档的类型：超平坦转出来仍是平坦，普通世界仍是无限。",
                "A declaration only: changing it does not regenerate terrain. Conversion keeps the source type, "
                        + "so a superflat save stays flat and a normal one stays infinite."));
        fields.add(whole("RandomSeed", "世界种子", "Seed", LevelField.KIND_LONG));
        fields.add(whole("SpawnX", "出生点 X", "Spawn X", LevelField.KIND_INT));
        fields.add(whole("SpawnY", "出生点 Y", "Spawn Y", LevelField.KIND_INT));
        fields.add(whole("SpawnZ", "出生点 Z", "Spawn Z", LevelField.KIND_INT));
        fields.add(whole("Time", "世界时间", "Time of day", LevelField.KIND_LONG));
        fields.add(new LevelField("StorageVersion", "存储版本（文件头）", "Storage version / header",
                Integer.toString(storageVersion), LevelField.KIND_READ_ONLY, null, null, null,
                "改高不会升级区块格式，反而可能让游戏判定存档损坏。",
                "Raising it does not upgrade the chunk format and can make the game reject the world."));
        fields.add(new LevelField("NetworkVersion", "协议版本", "Protocol version / NetworkVersion",
                Integer.toString((int) number("NetworkVersion", -1)), LevelField.KIND_READ_ONLY, null, null, null,
                "游戏用它判断当前版本是否过旧，与区块格式无关。",
                "The game uses this to decide whether it is too old; it is unrelated to the chunk format."));
        return fields;
    }

    private LevelField text(String id, String zh, String en) {
        return new LevelField(id, zh, en, textOf(id), LevelField.KIND_TEXT);
    }

    private LevelField whole(String id, String zh, String en, int kind) {
        boolean present = data.contains(id);
        String value = present ? Long.toString(number(id, 0L)) : "";
        // A missing key is still worth showing: it is exactly the case a user comes here to fix.
        return new LevelField(id, zh, en, value, kind, null, null, null,
                present ? null : "存档中没有这个字段，保存时才会写入。",
                present ? null : "This key is missing from the save and will be created on save.");
    }

    private LevelField flag(String id, String zh, String en) {
        return new LevelField(id, zh, en, truthy(id) ? "true" : "false", LevelField.KIND_BOOL);
    }

    private LevelField choice(String id, String zh, String en, String[] ids, String[] zhOptions,
                              String[] enOptions, String hintZh, String hintEn) {
        String current = Integer.toString((int) number(id, Integer.parseInt(ids[0])));
        return new LevelField(id, zh, en, current, LevelField.KIND_ENUM, ids, zhOptions, enOptions, hintZh, hintEn);
    }

    // ---------------------------------------------------------------------------------------------
    // Apply
    // ---------------------------------------------------------------------------------------------

    /**
     * Write the changes back, or leave the world untouched.
     * <p>
     * The new content goes to a temporary file first and is read back before it replaces the original: a truncated
     * write on a phone that ran out of storage would otherwise be indistinguishable from a corrupt save.
     * <p>
     * No dated backup is kept next to {@code level.dat}. There used to be one, and its cost was real: the copies
     * piled up inside the world directory, the app had to prune them, and a user who saved three times ended up
     * with three near-identical 2 KB files whose relationship to the current state was impossible to explain. The
     * safety net now sits one level up, where the user can reason about it: the details screen writes into an
     * archive through a temporary file, and offers "save as" for anyone who wants the original kept. What remains
     * here is the guarantee that matters -- a failed write never leaves a half-written header behind.
     *
     * @throws IOException when a value is rejected or the written file does not read back as expected. The
     *                     original {@code level.dat} is untouched in that case.
     */
    void apply(Map<String, String> changes) throws IOException {
        List<String> applied = new ArrayList<>();

        for (Map.Entry<String, String> change : changes.entrySet()) {
            String id = change.getKey();
            String value = change.getValue();
            if (id == null || value == null || !isAllowed(id)) {
                continue;
            }
            try {
                writeValue(id, value);
                applied.add(id);
            } catch (NumberFormatException malformed) {
                throw new IOException("「" + id + "」的值不是有效数字：" + value);
            }
        }

        if (applied.isEmpty()) {
            return;
        }

        File temporary = new File(directory, LEVEL_DAT + ".tmp");
        try {
            write(bedrock, temporary, storageVersion, data);
            verify(temporary, changes);
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw failure;
        }

        if (!temporary.renameTo(levelDat)) {
            //noinspection ResultOfMethodCallIgnored
            levelDat.delete();
            if (!temporary.renameTo(levelDat)) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
                throw new IOException("无法写入 level.dat");
            }
        }
    }

    private void write(boolean bedrock, File file, int storageVersion, CompoundTag data) throws IOException {
        if (bedrock) {
            Tag.writeBedrockNBT(file, storageVersion, data);
            return;
        }
        // The Java helper strips "Data" on read; the game expects it back under an unnamed root.
        CompoundTag wrapper = new CompoundTag(1);
        wrapper.put("Data", data);
        Tag.writeGZipJavaNBT(file, wrapper);
    }

    /**
     * Read the file back and confirm every changed field survived the round trip.
     * <p>
     * This catches the failures that matter: a wrong tag type that the writer silently coerced, or a file that was
     * truncated. Anything less would mean reporting success for a save the game may then refuse.
     */
    private void verify(File file, Map<String, String> changes) throws IOException {
        CompoundTag written = read(bedrock, file);
        if (written == null) {
            throw new IOException("写入后无法读回 level.dat");
        }
        for (String id : changes.keySet()) {
            if (!isAllowed(id)) {
                continue;
            }
            String expected = normalise(id, changes.get(id));
            String actual = normalise(id, readBack(written, id));
            if (expected != null && !expected.equals(actual)) {
                throw new IOException("写入校验失败：「" + id + "」应为 " + expected + "，实际为 " + actual);
            }
        }
    }

    private String readBack(CompoundTag root, String id) {
        Object value = raw(root, id);
        if (value == null) {
            return "";
        }
        if (kindOf(id) == LevelField.KIND_TEXT) {
            return String.valueOf(value);
        }
        if (kindOf(id) == LevelField.KIND_BOOL) {
            return Boolean.toString(value instanceof Number && ((Number) value).intValue() != 0);
        }
        return value instanceof Number ? Long.toString(((Number) value).longValue()) : String.valueOf(value);
    }

    /** Compare on the writer's terms: {@code "2"} and {@code "02"} both mean the same byte. */
    private String normalise(String id, String value) {
        if (value == null) {
            return null;
        }
        int kind = kindOf(id);
        try {
            if (kind == LevelField.KIND_TEXT) {
                return value;
            }
            if (kind == LevelField.KIND_BOOL) {
                return Boolean.toString(parseFlag(value));
            }
            return Long.toString(Long.parseLong(value.trim()));
        } catch (NumberFormatException exception) {
            return value;
        }
    }

    private void writeValue(String id, String value) {
        switch (id) {
            case "LevelName":
                data.put("LevelName", value);
                return;
            case "GameType":
                data.put("GameType", (int) parseNumber(value, 0, 6));
                return;
            case "Difficulty":
                // Java stores this as a byte, Bedrock as an int; writing the wrong width makes the game read 0.
                if (bedrock) {
                    data.put("Difficulty", (int) parseNumber(value, 0, 3));
                } else {
                    data.put("Difficulty", (byte) parseNumber(value, 0, 3));
                }
                return;
            case "commandsEnabled":
                data.put("commandsEnabled", (byte) (parseFlag(value) ? 1 : 0));
                return;
            case "allowCommands":
                data.put("allowCommands", (byte) (parseFlag(value) ? 1 : 0));
                return;
            case "hardcore":
                data.put("hardcore", (byte) (parseFlag(value) ? 1 : 0));
                return;
            case "Generator":
                data.put("Generator", (int) parseNumber(value, 0, 3));
                return;
            case "RandomSeed":
                data.put("RandomSeed", Long.parseLong(value.trim()));
                return;
            case "SpawnX":
                data.put("SpawnX", (int) parseNumber(value, Integer.MIN_VALUE, Integer.MAX_VALUE));
                return;
            case "SpawnY":
                data.put("SpawnY", (int) parseNumber(value, Integer.MIN_VALUE, Integer.MAX_VALUE));
                return;
            case "SpawnZ":
                data.put("SpawnZ", (int) parseNumber(value, Integer.MIN_VALUE, Integer.MAX_VALUE));
                return;
            case "Time":
                data.put("Time", Long.parseLong(value.trim()));
                return;
            case "DayTime":
                data.put("DayTime", Long.parseLong(value.trim()));
                return;
            default:
                throw new IllegalArgumentException("不允许修改的字段：" + id);
        }
    }

    private long parseNumber(String value, long minimum, long maximum) {
        long parsed = Long.parseLong(value.trim());
        if (parsed < minimum || parsed > maximum) {
            throw new NumberFormatException("超出范围：" + value);
        }
        return parsed;
    }

    private static boolean parseFlag(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(lower) || "1".equals(lower);
    }

    /** The allow list, in one place, so a typo cannot silently add a field the writer does not handle. */
    private boolean isAllowed(String id) {
        switch (id) {
            case "LevelName":
            case "GameType":
            case "Difficulty":
            case "RandomSeed":
            case "SpawnX":
            case "SpawnY":
            case "SpawnZ":
                return true;
            case "commandsEnabled":
            case "Generator":
            case "Time":
                return bedrock;
            case "allowCommands":
            case "hardcore":
            case "DayTime":
                return !bedrock;
            default:
                return false;
        }
    }

    private int kindOf(String id) {
        switch (id) {
            case "LevelName":
                return LevelField.KIND_TEXT;
            case "RandomSeed":
            case "Time":
            case "DayTime":
                return LevelField.KIND_LONG;
            case "commandsEnabled":
            case "allowCommands":
            case "hardcore":
                return LevelField.KIND_BOOL;
            default:
                return LevelField.KIND_INT;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Housekeeping
    // ---------------------------------------------------------------------------------------------

}
