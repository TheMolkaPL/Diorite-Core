package org.diorite.impl.world.io.anvil;

/*
 ** 2011 January 5
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 **/

/*
 * 2011 February 16
 *
 * This source code is based on the work of Scaevolus (see notice above).
 * It has been slightly modified by Mojang AB (constants instead of magic
 * numbers, a chunk timestamp header, and auto-formatted according to our
 * formatter template).
 *
 */

/*
 * Later changes made by the Glowstone and Diorite project.
 */

// Interfaces with region files on the disk

/*

 Region File Format

 Concept: The minimum unit of storage on hard drives is 4KB. 90% of Minecraft
 chunks are smaller than 4KB. 99% are smaller than 8KB. Write a simple
 container to store chunks in single files in runs of 4KB sectors.

 Each region file represents a 32x32 group of chunks. The conversion from
 chunk number to region number is floor(coord / 32): a chunk at (30, -3)
 would be in region (0, -1), and one at (70, -30) would be at (3, -1).
 Region files are named "r.x.z.data", where x and z are the region coordinates.

 A region file begins with a 4KB header that describes where chunks are stored
 in the file. A 4-byte big-endian integer represents sector offsets and sector
 counts. The chunk offset for a chunk (x, z) begins at byte 4*(x+z*32) in the
 file. The bottom byte of the chunk offset indicates the number of sectors the
 chunk takes up, and the top 3 bytes represent the sector number of the chunk.
 Given a chunk offset o, the chunk data begins at byte 4096*(o/256) and takes up
 at most 4096*(o%256) bytes. A chunk cannot exceed 1MB in size. If a chunk
 offset is 0, the corresponding chunk is not stored in the region file.

 Chunk data begins with a 4-byte big-endian integer representing the chunk data
 length in bytes, not counting the length field. The length must be smaller than
 4096 times the number of sectors. The next byte is a version field, to allow
 backwards-compatible updates to how chunks are encoded.

 A version of 1 represents a gzipped NBT file. The gzipped data is the chunk
 length - 1.

 A version of 2 represents a deflated (zlib compressed) NBT file. The deflated
 data is the chunk length - 1.

 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

@SuppressWarnings("MagicNumber")
public class RegionFile
{
    private static final int VERSION_GZIP    = 1;
    private static final int VERSION_DEFLATE = 2;

    private static final int SECTOR_BYTES = 4096;
    private static final int SECTOR_INTS  = SECTOR_BYTES / 4;

    private static final int    CHUNK_HEADER_SIZE = 5;
    private static final byte[] emptySector       = new byte[SECTOR_BYTES];
    private static final byte[] smallBytes        = new byte[2 << 5];

    private final RandomAccessFile   file;
    private final int[]              offsets;
    private final int[]              chunkTimestamps;
    private final ArrayList<Boolean> sectorFree;
    private       int                sizeDelta;
    private long lastModified = 0;

    public RegionFile(final File path) throws IOException
    {
        this.offsets = new int[SECTOR_INTS];
        this.chunkTimestamps = new int[SECTOR_INTS];

        this.sizeDelta = 0;

        if (path.exists())
        {
            this.lastModified = path.lastModified();
        }

        this.file = new RandomAccessFile(path, "rw");

        // seek to the end to prepare size checking
        this.file.seek(this.file.length());

        // if the file size is under 8KB, grow it (4K chunk offset table, 4K timestamp table)
        if (this.file.length() < (2 * SECTOR_BYTES))
        {
            this.sizeDelta += (2 * SECTOR_BYTES) - this.file.length();
            if (this.lastModified != 0)
            {
                // only give a warning if the region file existed beforehand
                System.err.println("[ChunkIO] Region \"" + path + "\" under 8K: " + this.file.length() + " increasing by " + (2 * SECTOR_BYTES - this.file.length()));
            }
            final long i = this.file.length();
            if (i < (2 * SECTOR_BYTES))
            {
                long j = (2 * SECTOR_BYTES) - i;
                while (j >= emptySector.length)
                {
                    this.file.write(emptySector);
                    j -= emptySector.length;
                }
                while (j >= smallBytes.length)
                {
                    this.file.write(smallBytes);
                    j -= smallBytes.length;
                }
                if (j > 0)
                {
                    this.file.write(new byte[(int) j]);
                }
            }
//            for (long i = this.file.length(); i < (2 * SECTOR_BYTES); ++ i)
//            {
//                this.file.write(0);
//            }
        }

        // if the file size is not a multiple of 4KB, grow it
        if ((this.file.length() & 0xfff) != 0)
        {
            this.sizeDelta += SECTOR_BYTES - (this.file.length() & 0xfff);
            System.err.println("[ChunkIO] Region \"" + path + "\" not aligned: " + this.file.length() + " increasing by " + (SECTOR_BYTES - (this.file.length() & 0xfff)));
            final long i = this.file.length() & 0xfff;

            if (i < SECTOR_BYTES)
            {
                long j = SECTOR_BYTES - i;
                while (j >= emptySector.length)
                {
                    this.file.write(emptySector);
                    j -= emptySector.length;
                }
                while (j >= smallBytes.length)
                {
                    this.file.write(smallBytes);
                    j -= smallBytes.length;
                }
                if (j > 0)
                {
                    this.file.write(new byte[(int) j]);
                }
            }
//            for (long i = this.file.length() & 0xfff; i < SECTOR_BYTES; ++ i)
//            {
//                this.file.write(0);
//            }
        }

        // set up the available sector map
        final int nSectors = (int) (this.file.length() / SECTOR_BYTES);
        this.sectorFree = new ArrayList<>(nSectors);
        for (int i = 0; i < nSectors; ++ i)
        {
            this.sectorFree.add(true);
        }

        this.sectorFree.set(0, false); // chunk offset table
        this.sectorFree.set(1, false); // for the last modified info

        // read offsets from offset table
        this.file.seek(0);
        for (int i = 0; i < SECTOR_INTS; ++ i)
        {
            final int offset = this.file.readInt();
            this.offsets[i] = offset;

            final int startSector = (offset >> 8);
            final int numSectors = (offset & 0xff);

            if ((offset != 0) && (startSector >= 0) && ((startSector + numSectors) <= this.sectorFree.size()))
            {
                for (int sectorNum = 0; sectorNum < numSectors; ++ sectorNum)
                {
                    this.sectorFree.set(startSector + sectorNum, false);
                }
            }
            else if (offset != 0)
            {
                System.err.println("[ChunkIO] Region \"" + path + "\": offsets[" + i + "] = " + offset + " -> " + startSector + "," + numSectors + " does not fit");
            }
        }
        // read timestamps from timestamp table
        for (int i = 0; i < SECTOR_INTS; ++ i)
        {
            this.chunkTimestamps[i] = this.file.readInt();
        }
    }

    /* the modification date of the region file when it was first opened */
    public long getLastModified()
    {
        return this.lastModified;
    }

    /* gets how much the region file has grown since it was last checked */
    public int getSizeDelta()
    {
        final int ret = this.sizeDelta;
        this.sizeDelta = 0;
        return ret;
    }

    /*
     * gets an (uncompressed) stream representing the chunk data returns null if
     * the chunk is not found or an error occurs
     */
    public DataInputStream getChunkDataInputStream(final int x, final int z) throws IOException
    {
        this.checkBounds(x, z);

        final int offset = this.getOffset(x, z);
        if (offset == 0)
        {
            // does not exist
            return null;
        }

        final int sectorNumber = offset >> 8;
        final int numSectors = offset & 0xFF;
        if ((sectorNumber + numSectors) > this.sectorFree.size())
        {
            throw new IOException("Invalid sector: " + sectorNumber + "+" + numSectors + " > " + this.sectorFree.size());
        }

        this.file.seek(sectorNumber * SECTOR_BYTES);
        final int length = this.file.readInt();
        if (length > (SECTOR_BYTES * numSectors))
        {
            throw new IOException("Invalid length: " + length + " > " + (SECTOR_BYTES * numSectors));
        }

        final byte version = this.file.readByte();
        if (version == VERSION_GZIP)
        {
            final byte[] data = new byte[length - 1];
            this.file.read(data);
            return new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)));
        }
        if (version == VERSION_DEFLATE)
        {
            final byte[] data = new byte[length - 1];
            this.file.read(data);
            return new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)));
        }

        throw new IOException("Unknown version: " + version);
    }

    public DataOutputStream getChunkDataOutputStream(final int x, final int z)
    {
        this.checkBounds(x, z);
        return new DataOutputStream(new DeflaterOutputStream(new ChunkBuffer(x, z), new Deflater(Deflater.BEST_SPEED)));
    }

    /*
     * lets chunk writing be multithreaded by not locking the whole file as a
     * chunk is serializing -- only writes when serialization is over
     */
    class ChunkBuffer extends ByteArrayOutputStream
    {
        private final int x, z;

        ChunkBuffer(final int x, final int z)
        {
            super(8096); // initialize to 8KB
            this.x = x;
            this.z = z;
        }

        @Override
        public void close() throws IOException
        {
            try
            {
                RegionFile.this.write(this.x, this.z, this.buf, this.count);
            } finally
            {
                super.close();
            }
        }

        @Override
        public String toString()
        {
            return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).appendSuper(super.toString()).append("x", this.x).append("z", this.z).toString();
        }
    }

    /* write a chunk at (x,z) with length bytes of data to disk */
    protected void write(final int x, final int z, final byte[] data, final int length) throws IOException
    {
        final int offset = this.getOffset(x, z);
        int sectorNumber = offset >> 8;
        final int sectorsAllocated = offset & 0xFF;
        final int sectorsNeeded = ((length + CHUNK_HEADER_SIZE) / SECTOR_BYTES) + 1;

        // maximum chunk size is 1MB
        if (sectorsNeeded >= 256)
        {
            return;
        }

        if ((sectorNumber != 0) && (sectorsAllocated == sectorsNeeded))
        {
            /* we can simply overwrite the old sectors */
            this.write(sectorNumber, data, length);
        }
        else
        {
            /* we need to allocate new sectors */

            /* mark the sectors previously used for this chunk as free */
            for (int i = 0; i < sectorsAllocated; ++ i)
            {
                this.sectorFree.set(sectorNumber + i, true);
            }

            /* scan for a free space large enough to store this chunk */
            int runStart = this.sectorFree.indexOf(true);
            int runLength = 0;
            if (runStart != - 1)
            {
                for (int i = runStart; i < this.sectorFree.size(); ++ i)
                {
                    if (runLength != 0)
                    {
                        if (this.sectorFree.get(i))
                        {
                            runLength++;
                        }
                        else
                        {
                            runLength = 0;
                        }
                    }
                    else if (this.sectorFree.get(i))
                    {
                        runStart = i;
                        runLength = 1;
                    }
                    if (runLength >= sectorsNeeded)
                    {
                        break;
                    }
                }
            }

            if (runLength >= sectorsNeeded)
            {
                /* we found a free space large enough */
                sectorNumber = runStart;
                this.setOffset(x, z, (sectorNumber << 8) | sectorsNeeded);
                for (int i = 0; i < sectorsNeeded; ++ i)
                {
                    this.sectorFree.set(sectorNumber + i, false);
                }
                this.write(sectorNumber, data, length);
            }
            else
            {
                /*
                 * no free space large enough found -- we need to grow the
                 * file
                 */
                this.file.seek(this.file.length());
                sectorNumber = this.sectorFree.size();
                for (int i = 0; i < sectorsNeeded; ++ i)
                {
                    this.file.write(emptySector);
                    this.sectorFree.add(false);
                }
                this.sizeDelta += SECTOR_BYTES * sectorsNeeded;

                this.write(sectorNumber, data, length);
                this.setOffset(x, z, (sectorNumber << 8) | sectorsNeeded);
            }
        }
        this.setTimestamp(x, z, (int) (System.currentTimeMillis() / 1000L));
        //file.getChannel().force(true);
    }

    /* write a chunk data to the region file at specified sector number */
    private void write(final int sectorNumber, final byte[] data, final int length) throws IOException
    {
        this.file.seek(sectorNumber * SECTOR_BYTES);
        this.file.writeInt(length + 1); // chunk length
        this.file.writeByte(VERSION_DEFLATE); // chunk version number
        this.file.write(data, 0, length); // chunk data
    }

    /* is this an invalid chunk coordinate? */
    private void checkBounds(final int x, final int z)
    {
        if ((x < 0) || (x >= 32) || (z < 0) || (z >= 32))
        {
            throw new IllegalArgumentException("Chunk out of bounds: (" + x + ", " + z + ")");
        }
    }

    private int getOffset(final int x, final int z)
    {
        return this.offsets[(x + (z << 5))];
    }

    public boolean hasChunk(final int x, final int z)
    {
        return this.getOffset(x, z) != 0;
    }

    private void setOffset(final int x, final int z, final int offset) throws IOException
    {
        this.offsets[(x + (z << 5))] = offset;
        this.file.seek((x + (z << 5)) >> 1);
        this.file.writeInt(offset);
    }

    private void setTimestamp(final int x, final int z, final int value) throws IOException
    {
        this.chunkTimestamps[(x + (z << 5))] = value;
        this.file.seek(SECTOR_BYTES + ((x + (z << 5)) << 2));
        this.file.writeInt(value);
    }

    public void close() throws IOException
    {
        this.file.getChannel().force(true);
        this.file.close();
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).appendSuper(super.toString()).append("file", this.file).append("sizeDelta", this.sizeDelta).append("lastModified", this.lastModified).toString();
    }
}
