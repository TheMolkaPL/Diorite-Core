package org.diorite.impl.world.generator;

import java.util.Map;
import java.util.Random;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import org.diorite.impl.pipelines.ChunkGeneratePipelineImpl;
import org.diorite.material.Material;
import org.diorite.material.blocks.stony.StoneMat;
import org.diorite.material.blocks.wooden.WoodTypeMat;
import org.diorite.utils.math.DioriteRandomUtils;
import org.diorite.utils.math.noise.NoiseGenerator;
import org.diorite.utils.math.noise.SimplexNoiseGenerator;
import org.diorite.utils.math.noise.SimplexOctaveGenerator;
import org.diorite.world.World;
import org.diorite.world.chunk.Chunk;
import org.diorite.world.chunk.ChunkPos;
import org.diorite.world.generator.ChunkBuilder;
import org.diorite.world.generator.WorldGenerator;
import org.diorite.world.generator.WorldGeneratorInitializer;
import org.diorite.world.generator.structures.Structure;

public class TestWorldGeneratorImpl extends WorldGenerator
{
    private final NoiseGenerator generator;

    public TestWorldGeneratorImpl(final World world, final String name, final Map<String, Object> options)
    {
        super(world, name, options);
        this.generator = new SimplexNoiseGenerator(world);
    }

    org.diorite.impl.world.generator.FlatWorldGeneratorImpl flat = new org.diorite.impl.world.generator.FlatWorldGeneratorImpl(this.world, this.name, this.options);

    @Override
    @SuppressWarnings("MagicNumber")
    public ChunkBuilder generate(final ChunkBuilder builder, final ChunkPos pos)
    {
        ChunkGeneratePipelineImpl.addGen(pos);
//        if (true)
//        {
//            return this.flat.generate(builder, pos);
//        }
        // Main.debug("Generating: " + pos + ", (" + this.world.getName() + ")");
        final SimplexOctaveGenerator overhangs = new SimplexOctaveGenerator(this.world, 8);
        final SimplexOctaveGenerator bottoms = new SimplexOctaveGenerator(this.world, 8);

        overhangs.setScale(1 / 64.0);
        bottoms.setScale(1 / 128.0);

        final int overhangsMagnitude = 16; //used when we generate the noise for the tops of the overhangs
        final int bottomsMagnitude = 32;

        for (int x = 0; x < 16; x++)
        {
            for (int z = 0; z < 16; z++)
            {
                final int realX = x + (pos.getX() << 4);
                final int realZ = z + (pos.getZ() << 4);

                final int bottomHeight = (int) ((bottoms.noise(realX, realZ, 0.5, 0.5) * bottomsMagnitude) + 64);
                final int maxHeight = ((int) overhangs.noise(realX, realZ, 0.5, 0.5) * overhangsMagnitude) + bottomHeight + 32;
                final double threshold = 0.3;

                //make the terrain
                for (int y = 0; y < maxHeight; y++)
                {
                    if (y > bottomHeight)
                    { //part where we do the overhangs
                        final double density = overhangs.noise(realX, y, realZ, 0.5, 0.5);

                        if (density > threshold)
                        {
                            builder.setBlock(x, y, z, StoneMat.getByID(DioriteRandomUtils.getRandInt(3, 4)));
                        }

                    }
                    else
                    {
                        builder.setBlock(x, y, z, StoneMat.getByID(DioriteRandomUtils.getRandInt(3, 4)));
                    }
                }

                //turn the tops into grass

                builder.setBlock(x, bottomHeight, z, Material.GRASS);
                builder.setBlock(x, bottomHeight - 1, z, Material.DIRT);
                builder.setBlock(x, bottomHeight - 2, z, Material.DIRT);

                for (int y = bottomHeight + 1; (y > bottomHeight) && (y < maxHeight); y++)
                { //the overhang
                    final int thisblock = builder.getBlockType(x, y, z).getId();
                    final int blockabove = builder.getBlockType(x, y + 1, z).getId();

                    if ((thisblock != Material.AIR.getId()) && (blockabove == Material.AIR.getId()))
                    {
                        builder.setBlock(x, y, z, Material.GRASS);
                        if (builder.getBlockType(x, y - 1, z).getType() != Material.AIR.getId())
                        {
                            builder.setBlock(x, y - 1, z, Material.DIRT);
                        }
                        if (builder.getBlockType(x, y - 2, z).getType() != Material.AIR.getId())
                        {
                            builder.setBlock(x, y - 2, z, Material.DIRT);
                        }
                    }
                }

            }
        }
        return builder;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).appendSuper(super.toString()).append("generator", this.generator).toString();
    }

    public static WorldGeneratorInitializer<TestWorldGeneratorImpl> createInitializer()
    {
        return new WorldGeneratorInitializer<TestWorldGeneratorImpl>("diorite:default")
        {
            @Override
            public TestWorldGeneratorImpl baseInit(final World world, final Map<String, Object> options)
            {
                return new TestWorldGeneratorImpl(world, this.name, options);
            }

            {
                this.populators.add(chunk -> {
                    final World world = chunk.getWorld();
                    final Structure s = new org.diorite.impl.world.generator.structures.tree.SmallTreeStructure(WoodTypeMat.OAK);
                    final Random random = new Random(chunk.getPos().asLong() + world.getSeed());
//                    s.generate(chunk.getPos(), random, (chunk.getX() * 16), chunk.getHighestBlockY(5, 5) + 1, (chunk.getZ() * 16));
                    for (int i = 0; i < 4; i++)
                    {
                        int rx = random.nextInt(Chunk.CHUNK_SIZE);
                        int rz = random.nextInt(Chunk.CHUNK_SIZE);
                        s.generate(chunk.getPos(), random, (chunk.getX() << 4) + rx, chunk.getHighestBlockY(rx, rz), (chunk.getZ() << 4) + rz);
                    }

                });
//                this.populators.add(chunk -> {
//                    //TODO: this is only test code
//                    int r = 15;
//                    int rr = 0;
//                    IntStream.rangeClosed(0, 15).forEach(x -> {
//
//                        if ((x == r) || (x == rr))
//                        {
//                            IntStream.rangeClosed(rr, r).forEach(z -> chunk.setBlock(x, chunk.getHighestBlock(x, z).getY(), z, WoolMat.getByID(DioriteRandomUtils.getRandInt(0, 15))));
//                            return;
//                        }
//                        chunk.setBlock(x, chunk.getHighestBlock(x, r).getY(), r, WoolMat.getByID(DioriteRandomUtils.getRandInt(0, 15)));
//                        chunk.setBlock(x, chunk.getHighestBlock(x, rr).getY(), rr, WoolMat.getByID(DioriteRandomUtils.getRandInt(0, 15)));
//                    });
//                });
            }
        };
    }
}
