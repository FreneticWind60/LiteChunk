package com.hivemc.chunker.conversion.encoding.java.base.reader;

import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.base.reader.WorldReader;
import com.hivemc.chunker.conversion.encoding.java.base.reader.util.MCAReader;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.scheduling.task.ProgressiveTask;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A reader for Java worlds.
 */
public class JavaWorldReader implements WorldReader {
    /**
     * Orders regions so that a batch covers a compact area, which limits the columns kept alive between batches for
     * neighbour pre-transform.
     */
    protected static final Comparator<RegionCoordPair> REGION_ORDER = Comparator
            .comparingInt(RegionCoordPair::regionX)
            .thenComparingInt(RegionCoordPair::regionZ);

    protected final Converter converter;
    protected final JavaResolvers resolvers;
    protected final File dimensionFolder;
    protected final Dimension dimension;

    /**
     * Create a new world reader.
     *
     * @param converter       the converter instance.
     * @param resolvers       the resolvers to use.
     * @param dimensionFolder the dimension folder to read data from.
     * @param dimension       the dimension being read.
     */
    public JavaWorldReader(Converter converter, JavaResolvers resolvers, File dimensionFolder, Dimension dimension) {
        this.converter = converter;
        this.resolvers = resolvers;
        this.dimensionFolder = dimensionFolder;
        this.dimension = dimension;
    }

    @Override
    public void readWorld(WorldConversionHandler worldConversionHandler) {
        Set<RegionCoordPair> regions = new ObjectOpenHashSet<>();
        ChunkerWorld chunkerWorld = new ChunkerWorld(
                dimension,
                regions
        );

        // Get each region file and loop through
        File[] folders = getMCAFolders();
        Set<String> knownRegionFiles = new ObjectOpenHashSet<>();

        for (File folder : folders) {
            File[] regionFiles = folder.listFiles((parent, fileName) -> fileName.endsWith(".mca"));
            if (regionFiles != null) {
                for (File regionFile : regionFiles) {
                    String[] parts = regionFile.getName().split("\\.");
                    if (parts.length != 4 || regionFile.length() < 4096) continue;

                    // Add to the known files
                    knownRegionFiles.add(regionFile.getAbsolutePath());

                    // Parse and add the region
                    try {
                        int x = Integer.parseInt(parts[1]);
                        int z = Integer.parseInt(parts[2]);

                        // Add to the list of regions
                        regions.add(new RegionCoordPair(x, z));
                    } catch (NumberFormatException e) {
                        // Ignore the region file
                    }
                }
            }
        }

        // Copy the regions for reading of the world
        HashSet<RegionCoordPair> regionsCopy = new HashSet<>(regions);

        // Submit world info (done before column reading)
        Task<ColumnConversionHandler> convertWorld = worldConversionHandler.convertWorld(chunkerWorld);

        // Handle the reading of the regions
        Task<Void> regionProcessing = convertWorld.thenConsume("Reading regions", TaskWeight.HIGHER, (columnConversionHandler) -> {
            if (columnConversionHandler == null) return; // This can be null if the columns aren't handled by the reader

            // Read the regions
            ProgressiveTask<Void> readingRegionFiles = Task.async("Reading region files", TaskWeight.HIGHER, () -> readRegionFiles(regionsCopy, knownRegionFiles, columnConversionHandler));

            // Call the flush task after all the region files have been read
            readingRegionFiles.then("Flushing columns", TaskWeight.MEDIUM, columnConversionHandler::flushColumns);
        });

        // When the region processing is done flush the world
        regionProcessing.then("Flushing world", TaskWeight.MEDIUM, () -> worldConversionHandler.flushWorld(chunkerWorld));
    }

    /**
     * Read all the region files and submit the columns.
     *
     * @param regions                 the region co-ordinates to read.
     * @param knownRegionFiles        a set of files which can be valid .mca files (exist and are bigger than 4096 bytes)
     * @param columnConversionHandler the handler to submit the columns to.
     */
    protected void readRegionFiles(Set<RegionCoordPair> regions, Set<String> knownRegionFiles, ColumnConversionHandler columnConversionHandler) {
        // Reading every region at once is what makes large Java worlds need a desktop sized heap: each region holds
        // up to 1024 columns and nothing is released until its flush task runs, so a world with a few hundred region
        // files keeps all of them resident simultaneously. Process them in bounded batches instead, the same way the
        // Bedrock reader does. Sort first so a batch covers a compact area, which keeps the columns retained across a
        // batch boundary (neighbour pre-transform) to a minimum.
        List<RegionCoordPair> ordered = new ArrayList<>();
        for (RegionCoordPair region : regions) {
            if (converter.shouldProcessRegion(dimension, region)) {
                ordered.add(region);
            }
        }
        ordered.sort(REGION_ORDER);
        converter.setRegionProgressTotal(ordered.size());
        scheduleRegionBatch(ordered.iterator(), knownRegionFiles, columnConversionHandler);
    }

    /**
     * Submit up to {@link Converter#getInFlightRegions()} regions, then schedule the next batch once they have all been
     * read and flushed. This bounds the number of full columns alive at any moment.
     *
     * @param orderedRegions          the remaining regions, ordered for spatial locality.
     * @param knownRegionFiles        the set of files that may be valid .mca files.
     * @param columnConversionHandler the handler to submit the read columns to.
     */
    protected void scheduleRegionBatch(Iterator<RegionCoordPair> orderedRegions, Set<String> knownRegionFiles,
                                       ColumnConversionHandler columnConversionHandler) {
        int limit = Math.max(1, converter.getInFlightRegions());
        List<Task<Void>> batch = new ArrayList<>(limit);
        while (orderedRegions.hasNext() && batch.size() < limit) {
            RegionCoordPair region = orderedRegions.next();
            if (!converter.shouldProcessRegion(dimension, region)) {
                continue;
            }
            File[] regionFiles = getRegionFiles(region, knownRegionFiles);

            // Read the region file then close the readers after it is done
            batch.add(Task.async("Reading region file", TaskWeight.NORMAL, () -> readRegion(regionFiles, region, columnConversionHandler))
                    .thenConsume("Closing region readers", TaskWeight.NONE, this::closeReaders)
                    .then("Region - Flushing", TaskWeight.MEDIUM, () -> {
                        columnConversionHandler.flushRegion(region);
                        converter.regionProcessed();
                    }));
        }

        if (orderedRegions.hasNext()) {
            // The weight of waiting is what keeps progress reporting honest across batches.
            Task.join(batch).then("Scheduling next region batch", TaskWeight.LOW,
                    () -> scheduleRegionBatch(orderedRegions, knownRegionFiles, columnConversionHandler));
        }
    }

    /**
     * Close all the MCAReaders in an array.
     *
     * @param mcaReaders the readers.
     */
    protected void closeReaders(MCAReader[] mcaReaders) {
        // Loop through all the readers and close them
        for (MCAReader mcaReader : mcaReaders) {
            if (mcaReader == null) continue;
            try {
                mcaReader.close();
            } catch (IOException e) {
                converter.logNonFatalException(e);
            }
        }
    }

    /**
     * Get all the folders which contain MCA files for this dimension.
     *
     * @return an array of all the folders (to be combined).
     */
    protected File[] getMCAFolders() {
        // Base version only uses region folder
        return new File[]{resolvers.javaLevelDirectoryResolver().getDimensionRegionDirectory(dimensionFolder)};
    }

    /**
     * Get a list of all the valid MCA files for a region.
     *
     * @param region     the region co-ordinates.
     * @param knownFiles a hashset of absolute paths to known files within the MCAFolders.
     * @return an array of all the matching .mca files, null entries are used when the file isn't present.
     */
    protected @Nullable File[] getRegionFiles(RegionCoordPair region, Set<String> knownFiles) {
        File[] folders = getMCAFolders();
        File[] files = new File[folders.length];

        // Create a path for each MCA folder
        File[] mcaFolders = getMCAFolders();
        for (int i = 0; i < mcaFolders.length; i++) {
            File folder = mcaFolders[i];
            File temp = new File(folder, "r." + region.regionX() + "." + region.regionZ() + ".mca");

            // Only add the file if it's known to exist
            if (knownFiles.contains(temp.getAbsolutePath())) {
                files[i] = temp;
            }
        }

        // Return the files
        return files;
    }

    /**
     * Read a region file.
     *
     * @param regionFiles             the region files which should be read for the region (e.g. entities in later
     *                                versions).
     * @param region                  the region co-ordinates.
     * @param columnConversionHandler the conversion handler to submit the read columns to.
     * @return the MCAReaders associated with the region.
     */
    @SuppressWarnings("resource")
    protected MCAReader[] readRegion(@Nullable File[] regionFiles, RegionCoordPair region, ColumnConversionHandler columnConversionHandler) {
        int regionFilesCount = regionFiles.length;
        MCAReader[] mcaReaders = new MCAReader[regionFilesCount];

        // Open all our required files
        boolean foundValidFile = false;
        for (int i = 0; i < regionFilesCount; i++) {
            File file = regionFiles[i];

            // Skip if the file doesn't exist / is invalid
            if (file == null) continue;

            // Otherwise open a random access file
            try {
                mcaReaders[i] = new MCAReader(converter, file);
                foundValidFile = true;
            } catch (FileNotFoundException e) {
                // Ignored, it'll be null if this happens
            }
        }

        // Don't continue if there's no files to read
        if (!foundValidFile) {
            converter.logNonFatalException(new Exception("Misnamed region file for " + dimension + ", " + region));
            return mcaReaders;
        }

        // Stage 1. Collect all known offsets
        Int2ObjectMap<int[]> positionsToOffsets = new Int2ObjectOpenHashMap<>();
        for (int regionFileIndex = 0; regionFileIndex < regionFilesCount; regionFileIndex++) {
            MCAReader mcaReader = mcaReaders[regionFileIndex];
            if (mcaReader == null) continue;

            try {
                // Read the header which contains the chunk offsets
                int[] offsets = mcaReader.readOffsetTable();
                for (int i = 0; i < offsets.length; i++) {
                    int offset = offsets[i];

                    // Only record the offset if it's more than 0, the first 4096 is the header, so it's invalid to be there
                    if (offset > 0) {
                        int[] mcaOffsets = positionsToOffsets.computeIfAbsent(i, (ignored) -> new int[regionFilesCount]);
                        mcaOffsets[regionFileIndex] = offset;
                    }
                }
            } catch (Exception e) {
                converter.logNonFatalException(e);
            }
        }

        // Stage 2. Iterate the found columns and combine the results, in bounded batches.
        //
        // Submitting every column of a region at once is what makes a large world need a desktop sized heap: each
        // "Processing column" task decompresses its NBT, builds a ChunkerColumn (a full 384 block height of block
        // states) and queues another task holding it, so a 1024 column region can put a thousand of them in the
        // executor queue before anything is written. Batching caps how many exist at any moment; the batches are
        // chained with then() rather than blocking, so no worker thread is ever parked waiting for the queue.
        scheduleColumnBatch(new ArrayList<>(positionsToOffsets.int2ObjectEntrySet()), 0, region, mcaReaders,
                regionFilesCount, columnConversionHandler);
        return mcaReaders;
    }

    /**
     * Submit up to {@link Converter#getInFlightColumns()} columns, then schedule the next batch once they have been
     * read and written. Keeps the number of live {@code ChunkerColumn} instances bounded.
     *
     * @param columns                 the columns in this region, in reading order.
     * @param start                   the index of the first column not yet submitted.
     * @param region                  the region being read.
     * @param mcaReaders              the open readers for this region, kept open until the last batch completes.
     * @param regionFilesCount        the number of reader slots.
     * @param columnConversionHandler the handler to submit the converted columns to.
     */
    protected void scheduleColumnBatch(List<Int2ObjectMap.Entry<int[]>> columns, int start, RegionCoordPair region,
                                       MCAReader[] mcaReaders, int regionFilesCount,
                                       ColumnConversionHandler columnConversionHandler) {
        int limit = Math.max(1, converter.getInFlightColumns());
        List<Task<Void>> batch = new ArrayList<>(limit);

        int index = start;
        while (index < columns.size() && batch.size() < limit) {
            Int2ObjectMap.Entry<int[]> columnOffsets = columns.get(index++);
            ChunkCoordPair localCoords = new ChunkCoordPair(
                    columnOffsets.getIntKey() & 31,
                    columnOffsets.getIntKey() >> 5
            );
            ChunkCoordPair columnsCoords = region.getChunk(localCoords.chunkX(), localCoords.chunkZ());
            int[] columnFileOffsets = columnOffsets.getValue();

            // Ignore if this column shouldn't be processed
            if (!converter.shouldProcessColumn(dimension, columnsCoords)) continue;

            // Do the column work in its own task
            batch.add(Task.async("Processing column " + columnsCoords, TaskWeight.NORMAL, () -> {
                // Read each region file for this column, the array keeps the order predictable for combining
                CompoundTag[] compoundTags = new CompoundTag[regionFilesCount];

                for (int regionFileIndex = 0; regionFileIndex < regionFilesCount; regionFileIndex++) {
                    MCAReader mcaReader = mcaReaders[regionFileIndex];
                    if (mcaReader == null) continue;

                    // Get the column offset specific for this region file
                    int offset = columnFileOffsets[regionFileIndex];
                    if (offset <= 0) continue; // Skip if it's 0 or less as that indicates it's not used in this file

                    try {
                        // Safe to call from several workers at once: MCAReader reads with positional IO and has no
                        // shared file pointer, so this no longer has to be serialised with a lock. Reading and
                        // inflating happen inline here (rather than in a nested task) so the reader can reuse its
                        // per-thread buffers without handing them to another thread.
                        compoundTags[regionFileIndex] = mcaReader.readColumn(columnsCoords, offset);
                    } catch (Exception e) {
                        converter.logNonFatalException(e);
                    }
                }

                // Combine the regions into a single column and read it
                createColumnReader(columnsCoords, combineColumnCompounds(compoundTags))
                        .readColumn(columnConversionHandler);
            }));
        }

        if (index < columns.size()) {
            // Reserve the weight of everything still queued, otherwise the progress bar races to the end of this
            // region and then sits still while the remaining columns are actually converted.
            int remainingWeight = Math.toIntExact((long) (columns.size() - index)
                    * (TaskWeight.NORMAL.getWeight() + TaskWeight.LOW.getWeight()));
            final int next = index;
            Task.join(batch).then("Scheduling next column batch", new TaskWeight(remainingWeight),
                    () -> scheduleColumnBatch(columns, next, region, mcaReaders, regionFilesCount, columnConversionHandler));
        }
    }

    /**
     * Combine compoundTags fetched from multiple files.
     *
     * @param compoundTags the compound tags in the same order as the folders array with null when a tag is missing.
     * @return the combined compound tag.
     */
    protected CompoundTag combineColumnCompounds(CompoundTag[] compoundTags) {
        // By default, no combining is supported in older versions
        if (compoundTags.length > 1)
            throw new IllegalArgumentException("Combining compounds is unsupported at this version");
        return compoundTags[0];
    }

    /**
     * Create a new column reader.
     *
     * @param worldChunkCoords the co-ordinates of the column.
     * @param columnNBT        the NBT compound which was read for the column.
     * @return a newly created column reader.
     */
    public JavaColumnReader createColumnReader(ChunkCoordPair worldChunkCoords, CompoundTag columnNBT) {
        return new JavaColumnReader(converter, resolvers, dimension, worldChunkCoords, columnNBT);
    }
}
