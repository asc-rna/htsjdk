/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package htsjdk.samtools;


import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.*;
import htsjdk.samtools.util.zip.InflaterFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Class for reading and querying BAM files.
 */
public class BAMFileReader extends SamReader.ReaderImplementation {
    // True if reading from a File rather than an InputStream
    private boolean mIsSeekable = false;

    // For converting bytes into other primitive types
    private BinaryCodec mStream = null;

    // Underlying compressed data stream.
    private final BlockCompressedInputStream mCompressedInputStream;
    private SAMFileHeader mFileHeader = null;

    // One of these is populated if the file is seekable and an index exists
    private File mIndexFile = null;
    private SeekableStream mIndexStream = null;

    private BAMIndex mIndex = null;
    private long mFirstRecordPointer = 0;
    // If non-null, there is an unclosed iterator extant.
    private CloseableIterator<SAMRecord> mCurrentIterator = null;

    // If true, all SAMRecords are fully decoded as they are read.
    private boolean eagerDecode;

    // For error-checking.
    private ValidationStringency mValidationStringency;

    // For creating BAMRecords
    private SAMRecordFactory samRecordFactory;

    /**
     * Use the caching index reader implementation rather than the disk-hit-per-file model.
     */
    private boolean mEnableIndexCaching = false;

    /**
     * Use the traditional memory-mapped implementation for BAM file indexes rather than regular I/O.
     */
    private boolean mEnableIndexMemoryMapping = true;

    /**
     * Add information about the origin (reader and position) to SAM records.
     */
    private SamReader mReader = null;

    /**
     * Prepare to read BAM from a stream (not seekable)
     * @param stream source of bytes.
     * @param indexFile BAM index file
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @throws IOException
     */
    BAMFileReader(final InputStream stream,
                  final File indexFile,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory)
            throws IOException {
        this(stream, indexFile, eagerDecode, useAsynchronousIO, validationStringency, samRecordFactory,
             BlockGunzipper.getDefaultInflaterFactory());
    }

    /**
     * Prepare to read BAM from a stream (not seekable)
     * @param stream source of bytes.
     * @param indexFile BAM index file
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @param inflaterFactory InflaterFactory used by BlockCompressedInputStream
     * @throws IOException
     */
    BAMFileReader(final InputStream stream,
                  final File indexFile,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory,
                  final InflaterFactory inflaterFactory)
            throws IOException {
        mIndexFile = indexFile;
        mIsSeekable = false;
        mCompressedInputStream = useAsynchronousIO ? new AsyncBlockCompressedInputStream(stream, inflaterFactory) : new BlockCompressedInputStream(stream, inflaterFactory);
        mStream = new BinaryCodec(new DataInputStream(mCompressedInputStream));
        this.eagerDecode = eagerDecode;
        this.mValidationStringency = validationStringency;
        this.samRecordFactory = samRecordFactory;
        this.mFileHeader = readHeader(this.mStream, this.mValidationStringency, null);
    }

    /**
     * Prepare to read BAM from a file (seekable)
     * @param file source of bytes.
     * @param indexFile BAM index file
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @throws IOException
     */
    BAMFileReader(final File file,
                  final File indexFile,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory)
        throws IOException {
        this(file, indexFile, eagerDecode, useAsynchronousIO, validationStringency, samRecordFactory, BlockGunzipper.getDefaultInflaterFactory());
    }

    /**
     * Prepare to read BAM from a file (seekable)
     * @param file source of bytes.
     * @param indexFile BAM index file
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @param inflaterFactory InflaterFactory used by BlockCompressedInputStream
     * @throws IOException
     */
    BAMFileReader(final File file,
                  final File indexFile,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory,
                  final InflaterFactory inflaterFactory)
        throws IOException {
        this(useAsynchronousIO ? new AsyncBlockCompressedInputStream(file, inflaterFactory) : new BlockCompressedInputStream(file, inflaterFactory),
                indexFile!=null ? indexFile : SamFiles.findIndex(file), eagerDecode, useAsynchronousIO, file.getAbsolutePath(), validationStringency, samRecordFactory);

        if (mIndexFile != null && mIndexFile.lastModified() < file.lastModified() - 5000) {
            System.err.println("WARNING: BAM index file " + mIndexFile.getAbsolutePath() +
                    " is older than BAM " + file.getAbsolutePath());
        }

        // Provide better error message when there is an error reading.
        mStream.setInputFileName(file.getAbsolutePath());
    }

    /**
     * Prepare to read BAM from a stream (seekable)
     * @param strm source of bytes
     * @param indexFile BAM index file
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @throws IOException
     */
    BAMFileReader(final SeekableStream strm,
                  final File indexFile,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory)
        throws IOException {
        this(strm, indexFile, eagerDecode, useAsynchronousIO, validationStringency, samRecordFactory, BlockGunzipper.getDefaultInflaterFactory());
    }

    /**
     * Prepare to read BAM from a stream (seekable)
     * @param strm source of bytes
     * @param indexFile BAM index file
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @param inflaterFactory InflaterFactory used by BlockCompressedInputStream
     * @throws IOException
     */
    BAMFileReader(final SeekableStream strm,
                  final File indexFile,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory,
                  final InflaterFactory inflaterFactory)
        throws IOException {
        this(useAsynchronousIO ? new AsyncBlockCompressedInputStream(strm, inflaterFactory) : new BlockCompressedInputStream(strm, inflaterFactory),
                indexFile, eagerDecode, useAsynchronousIO, strm.getSource(), validationStringency, samRecordFactory);
    }

    /**
     * Prepare to read BAM from a stream (seekable)
     * @param strm source of bytes
     * @param indexStream BAM index stream
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @throws IOException
     */
    BAMFileReader(final SeekableStream strm,
                  final SeekableStream indexStream,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory)
        throws IOException {
        this(strm, indexStream, eagerDecode, useAsynchronousIO, validationStringency, samRecordFactory, BlockGunzipper.getDefaultInflaterFactory());
    }

    /**
     * Prepare to read BAM from a stream (seekable)
     * @param strm source of bytes
     * @param indexStream BAM index stream
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @param inflaterFactory InflaterFactory used by BlockCompressedInputStream
     * @throws IOException
     */
    BAMFileReader(final SeekableStream strm,
                  final SeekableStream indexStream,
                  final boolean eagerDecode,
                  final boolean useAsynchronousIO,
                  final ValidationStringency validationStringency,
                  final SAMRecordFactory samRecordFactory,
                  final InflaterFactory inflaterFactory)
        throws IOException {
        this(useAsynchronousIO ? new AsyncBlockCompressedInputStream(strm, inflaterFactory) : new BlockCompressedInputStream(strm, inflaterFactory),
                indexStream, eagerDecode, useAsynchronousIO, strm.getSource(), validationStringency, samRecordFactory);
    }

    /**
     * Prepare to read BAM from a compressed stream (seekable)
     * @param compressedInputStream source of bytes
     * @param indexFile BAM index file
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param source string used when reporting errors
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @throws IOException
     */
    private BAMFileReader(final BlockCompressedInputStream compressedInputStream,
                          final File indexFile,
                          final boolean eagerDecode,
                          final boolean useAsynchronousIO,
                          final String source,
                          final ValidationStringency validationStringency,
                          final SAMRecordFactory samRecordFactory)
        throws IOException {
        mIndexFile = indexFile;
        mIsSeekable = true;
        mCompressedInputStream = compressedInputStream;
        mStream = new BinaryCodec(new DataInputStream(mCompressedInputStream));
        this.eagerDecode = eagerDecode;
        this.mValidationStringency = validationStringency;
        this.samRecordFactory = samRecordFactory;
        this.mFileHeader = readHeader(this.mStream, this.mValidationStringency, source);
        mFirstRecordPointer = mCompressedInputStream.getFilePointer();
    }

    /**
     * Prepare to read BAM from a compressed stream (seekable)
     * @param compressedInputStream source of bytes
     * @param indexStream BAM index stream
     * @param eagerDecode if true, decode all BAM fields as reading rather than lazily.
     * @param useAsynchronousIO if true, use asynchronous I/O
     * @param source string used when reporting errors
     * @param validationStringency Controls how to handle invalidate reads or header lines.
     * @param samRecordFactory SAM record factory
     * @throws IOException
     */
    private BAMFileReader(final BlockCompressedInputStream compressedInputStream,
                          final SeekableStream indexStream,
                          final boolean eagerDecode,
                          final boolean useAsynchronousIO,
                          final String source,
                          final ValidationStringency validationStringency,
                          final SAMRecordFactory samRecordFactory)
        throws IOException {
        mIndexStream = indexStream;
        mIsSeekable = true;
        mCompressedInputStream = compressedInputStream;
        mStream = new BinaryCodec(new DataInputStream(mCompressedInputStream));
        this.eagerDecode = eagerDecode;
        this.mValidationStringency = validationStringency;
        this.samRecordFactory = samRecordFactory;
        this.mFileHeader = readHeader(this.mStream, this.mValidationStringency, source);
        mFirstRecordPointer = mCompressedInputStream.getFilePointer();
    }

    /** Reads through the header and sequence records to find the virtual file offset of the first record in the BAM file. */
    static long findVirtualOffsetOfFirstRecord(final File bam) throws IOException {
        final BAMFileReader reader = new BAMFileReader(bam, null, false, false, ValidationStringency.SILENT, new DefaultSAMRecordFactory());
        final long offset = reader.mFirstRecordPointer;
        reader.close();
        return offset;
    }

    /**
     * Reads through the header and sequence records to find the virtual file offset of the first record in the BAM file.
     * The caller is responsible for closing the stream.
     */
    static long findVirtualOffsetOfFirstRecord(final SeekableStream seekableStream) throws IOException {
        final BAMFileReader reader = new BAMFileReader(seekableStream, (SeekableStream) null, false, false, ValidationStringency.SILENT, new DefaultSAMRecordFactory());
        return reader.mFirstRecordPointer;
    }

    /**
     * If true, writes the source of every read into the source SAMRecords.
     * @param enabled true to write source information into each SAMRecord.
     */
    @Override
    void enableFileSource(final SamReader reader, final boolean enabled) {
        this.mReader = enabled ? reader : null;
    }

    /**
     * If true, uses the caching version of the index reader.
     * @param enabled true to use the caching version of the reader.
     */
    @Override
    protected void enableIndexCaching(final boolean enabled) {
        if(mIndex != null)
            throw new SAMException("Unable to turn on index caching; index file has already been loaded.");
        this.mEnableIndexCaching = enabled;
    }

    /**
     * If false, disable the use of memory mapping for accessing index files (default behavior is to use memory mapping).
     * This is slower but more scalable when accessing large numbers of BAM files sequentially.
     * @param enabled True to use memory mapping, false to use regular I/O.
     */
    @Override
    protected void enableIndexMemoryMapping(final boolean enabled) {
        if (mIndex != null) {
            throw new SAMException("Unable to change index memory mapping; index file has already been loaded.");
        }
        this.mEnableIndexMemoryMapping = enabled;
    }

    @Override void enableCrcChecking(final boolean enabled) {
        this.mCompressedInputStream.setCheckCrcs(enabled);
    }

    @Override void setSAMRecordFactory(final SAMRecordFactory samRecordFactory) { this.samRecordFactory = samRecordFactory; }

    @Override
    public SamReader.Type type() {
        if (mIndexFile != null && getIndexType().equals(SamIndexes.CSI)) {
            return SamReader.Type.BAM_CSI_TYPE;
        }
        return SamReader.Type.BAM_TYPE;
    }

    /**
     * @return true if ths is a BAM file, and has an index
     */
    @Override
    public boolean hasIndex() {
        return mIsSeekable && ((mIndexFile != null) || (mIndexStream != null));
    }

    /**
     * Retrieves the index for the given file type.  Ensure that the index is of the specified type.
     * @return An index of the given type.
     */
    @Override
    public BAMIndex getIndex() {
        if(!hasIndex()) {
            throw new SAMException("No index is available for this BAM file.");
        }
        if(mIndex == null) {
            final SamIndexes samIndexType = getIndexType();
            final SAMSequenceDictionary sequenceDictionary = getFileHeader().getSequenceDictionary();
            if(mIndexFile != null) {
                if (samIndexType.equals(SamIndexes.BAI)) {
                    mIndex = mEnableIndexCaching ? new CachingBAMFileIndex(mIndexFile, sequenceDictionary, mEnableIndexMemoryMapping)
                            : new DiskBasedBAMFileIndex(mIndexFile, sequenceDictionary, mEnableIndexMemoryMapping);
                } else if (samIndexType.equals(SamIndexes.CSI)) {
                    mIndex = new CSIIndex(mIndexFile, mEnableIndexMemoryMapping, sequenceDictionary);
                } else {
                    throw new SAMFormatException("Unsupported BAM index file format: " + mIndexFile.getName());
                }
            } else if(mIndexStream != null) {
                if (samIndexType.equals(SamIndexes.BAI)) {
                    mIndex = new CachingBAMFileIndex(mIndexStream, sequenceDictionary);
                } else if (samIndexType.equals(SamIndexes.CSI)) {
                    mIndex = new CSIIndex(mIndexStream,  sequenceDictionary);
                } else {
                    throw new SAMFormatException("Unsupported BAM index file format: " + mIndexStream.getSource());
                }
            }
        }

        return mIndex;
    }

    /**
     * Return the type of the BAM index, BAI or CSI.
     * @return one of {@link SamIndexes#BAI} or {@link SamIndexes#CSI} or null
     */
    public SamIndexes getIndexType() {
        if (mIndexFile != null) {
            if (mIndexFile.getName().toLowerCase().endsWith(FileExtensions.BAI_INDEX)) {
                return SamIndexes.BAI;
            } else if (mIndexFile.getName().toLowerCase().endsWith(FileExtensions.CSI)) {
                return SamIndexes.CSI;
            }
            throw new SAMFormatException("Unknown BAM index file type: " + mIndexFile.getName());
        } else if (mIndexStream != null) {
            final SamIndexes samIndexesType = SamIndexes.getSAMIndexTypeFromStream(mIndexStream);
            if (samIndexesType == SamIndexes.BAI || samIndexesType == SamIndexes.CSI) {
                return samIndexesType;
            }
            throw new SAMFormatException(String.format("Unknown BAM index file type: %s in %s", samIndexesType, mIndexStream.getSource()));
        }

        return null;
    }

    public void setEagerDecode(final boolean desired) { this.eagerDecode = desired; }

    @Override
    public void close() {
        if (mCompressedInputStream != null) {
            try {
                mCompressedInputStream.close();
            } catch (IOException e) {
                throw new RuntimeIOException("Exception closing compressed input stream.", e);
            }
        }
        if (mStream != null) {
            mStream.close();
        }
        if (mIndex != null) {
            mIndex.close();
        }
        mStream = null;
        mFileHeader = null;
        mIndex = null;
    }

    @Override
    public SAMFileHeader getFileHeader() {
        return mFileHeader;
    }

    /**
     * Set error-checking level for subsequent SAMRecord reads.
     */
    @Override
    void setValidationStringency(final ValidationStringency validationStringency) {
        this.mValidationStringency = validationStringency;
    }

    @Override
    public ValidationStringency getValidationStringency() {
        return this.mValidationStringency;
    }

    /**
     * Prepare to iterate through the SAMRecords in file order.
     * Only a single iterator on a BAM file can be extant at a time.  If getIterator() or a query method has been called once,
     * that iterator must be closed before getIterator() can be called again.
     * A somewhat peculiar aspect of this method is that if the file is not seekable, a second call to
     * getIterator() begins its iteration where the last one left off.  That is the best that can be
     * done in that situation.
     */
    @Override
    public CloseableIterator<SAMRecord> getIterator() {
        if (mStream == null) {
            throw new IllegalStateException("File reader is closed");
        }
        if (mCurrentIterator != null) {
            throw new IllegalStateException("Iteration in progress");
        }
        if (mIsSeekable) {
            try {
                mCompressedInputStream.seek(mFirstRecordPointer);
            } catch (final IOException exc) {
                throw new RuntimeIOException(exc.getMessage(), exc);
            }
        }
        BAMFileIterator underLayingCurrentIterator = new BAMFileIterator();
        mCurrentIterator = new AsyncBufferedIterator<SAMRecord>(underLayingCurrentIterator, Defaults.BUFFER_SIZE);

        return mCurrentIterator;
    }

    @Override
    public CloseableIterator<SAMRecord> getIterator(final SAMFileSpan chunks) {
        if (mStream == null) {
            throw new IllegalStateException("File reader is closed");
        }
        if (mCurrentIterator != null) {
            throw new IllegalStateException("Iteration in progress");
        }
        if (!(chunks instanceof BAMFileSpan)) {
            throw new IllegalStateException("BAMFileReader cannot handle this type of file span.");
        }

        // Create an iterator over the given chunk boundaries.
        mCurrentIterator = new BAMFileIndexIterator(((BAMFileSpan)chunks).toCoordinateArray());
        return mCurrentIterator;
    }

    /**
     * Gets an unbounded pointer to the first record in the BAM file.  Because the reader doesn't necessarily know
     * when the file ends, the rightmost bound of the file pointer will not end exactly where the file ends.  However,
     * the rightmost bound is guaranteed to be after the last read in the file.
     * @return An unbounded pointer to the first record in the BAM file.
     */
    @Override
    public SAMFileSpan getFilePointerSpanningReads() {
        return new BAMFileSpan(new Chunk(mFirstRecordPointer,Long.MAX_VALUE));
    }

    /**
     * Prepare to iterate through the SAMRecords that match the given interval.
     * Only a single iterator on a BAMFile can be extant at a time.  The previous one must be closed
     * before calling any of the methods that return an iterator.
     *
     * Note that an unmapped SAMRecord may still have a reference name and an alignment start for sorting
     * purposes (typically this is the coordinate of its mate), and will be found by this method if the coordinate
     * matches the specified interval.
     *
     * Note that this method is not necessarily efficient in terms of disk I/O.  The index does not have perfect
     * resolution, so some SAMRecords may be read and then discarded because they do not match the specified interval.
     *
     * @param sequence Reference sequence sought.
     * @param start Desired SAMRecords must overlap or be contained in the interval specified by start and end.
     * A value of zero implies the start of the reference sequence.
     * @param end A value of zero implies the end of the reference sequence.
     * @param contained If true, the alignments for the SAMRecords must be completely contained in the interval
     * specified by start and end.  If false, the SAMRecords need only overlap the interval.
     * @return Iterator for the matching SAMRecords
     */
    CloseableIterator<SAMRecord> query(final String sequence, final int start, final int end, final boolean contained) {
        if (mStream == null) {
            throw new IllegalStateException("File reader is closed");
        }
        if (mCurrentIterator != null) {
            throw new IllegalStateException("Iteration in progress");
        }
        if (!mIsSeekable) {
            throw new UnsupportedOperationException("Cannot query stream-based BAM file");
        }
        final int referenceIndex = mFileHeader.getSequenceIndex(sequence);
        if (referenceIndex == -1) {
            mCurrentIterator = new EmptyBamIterator();
        } else {
            final QueryInterval[] queryIntervals = {new QueryInterval(referenceIndex, start, end)};
            mCurrentIterator = createIndexIterator(queryIntervals, contained);
        }
        return mCurrentIterator;
    }

    /**
     * Prepare to iterate through the SAMRecords that match any of the given intervals.
     * Only a single iterator on a BAMFile can be extant at a time.  The previous one must be closed
     * before calling any of the methods that return an iterator.
     *
     * Note that an unmapped SAMRecord may still have a reference name and an alignment start for sorting
     * purposes (typically this is the coordinate of its mate), and will be found by this method if the coordinate
     * matches the specified interval.
     *
     * Note that this method is not necessarily efficient in terms of disk I/O.  The index does not have perfect
     * resolution, so some SAMRecords may be read and then discarded because they do not match the specified interval.
     *
     * @param intervals list of intervals to be queried.  Must be optimized.
     * @param contained If true, the alignments for the SAMRecords must be completely contained in the interval
     * specified by start and end.  If false, the SAMRecords need only overlap the interval.
     * @return Iterator for the matching SAMRecords
     * @see QueryInterval#optimizeIntervals(QueryInterval[])
     */
    @Override
    public CloseableIterator<SAMRecord> query(final QueryInterval[] intervals, final boolean contained) {
        if (mStream == null) {
            throw new IllegalStateException("File reader is closed");
        }
        if (mCurrentIterator != null) {
            throw new IllegalStateException("Iteration in progress");
        }
        if (!mIsSeekable) {
            throw new UnsupportedOperationException("Cannot query stream-based BAM file");
        }
        mCurrentIterator = createIndexIterator(intervals, contained);
        return mCurrentIterator;
    }

    /**
     * Prepare to iterate through the SAMRecords with the given alignment start.
     * Only a single iterator on a BAMFile can be extant at a time.  The previous one must be closed
     * before calling any of the methods that return an iterator.
     *
     * Note that an unmapped SAMRecord may still have a reference name and an alignment start for sorting
     * purposes (typically this is the coordinate of its mate), and will be found by this method if the coordinate
     * matches the specified interval.
     *
     * Note that this method is not necessarily efficient in terms of disk I/O.  The index does not have perfect
     * resolution, so some SAMRecords may be read and then discarded because they do not match the specified interval.
     *
     * @param sequence Reference sequence sought.
     * @param start Alignment start sought.
     * @return Iterator for the matching SAMRecords.
     */
    @Override
    public CloseableIterator<SAMRecord> queryAlignmentStart(final String sequence, final int start) {
        if (mStream == null) {
            throw new IllegalStateException("File reader is closed");
        }
        if (mCurrentIterator != null) {
            throw new IllegalStateException("Iteration in progress");
        }
        if (!mIsSeekable) {
            throw new UnsupportedOperationException("Cannot query stream-based BAM file");
        }
        final int referenceIndex = mFileHeader.getSequenceIndex(sequence);
        if (referenceIndex == -1) {
            mCurrentIterator = new EmptyBamIterator();
        } else {
            mCurrentIterator = createStartingAtIndexIterator(referenceIndex, start);
        }
        return mCurrentIterator;
    }

    /**
     * Prepare to iterate through the SAMRecords that are unmapped and do not have a reference name or alignment start.
     * Only a single iterator on a BAMFile can be extant at a time.  The previous one must be closed
     * before calling any of the methods that return an iterator.
     *
     * @return Iterator for the matching SAMRecords.
     */
    @Override
    public CloseableIterator<SAMRecord> queryUnmapped() {
        if (mStream == null) {
            throw new IllegalStateException("File reader is closed");
        }
        if (mCurrentIterator != null) {
            throw new IllegalStateException("Iteration in progress");
        }
        if (!mIsSeekable) {
            throw new UnsupportedOperationException("Cannot query stream-based BAM file");
        }
        try {
            final long startOfLastLinearBin = getIndex().getStartOfLastLinearBin();
            if (startOfLastLinearBin != -1) {
                mCompressedInputStream.seek(startOfLastLinearBin);
            } else {
                // No mapped reads in file, just start at the first read in file.
                mCompressedInputStream.seek(mFirstRecordPointer);
            }
            mCurrentIterator = new BAMFileIndexUnmappedIterator();
            return mCurrentIterator;
        } catch (final IOException e) {
            throw new RuntimeIOException("IOException seeking to unmapped reads", e);
        }
    }

    /**
     * Reads the header of a BAM file from a stream
     * @param stream A BinaryCodec to read the header from
     * @param validationStringency Determines how stringent to be when validating the sam
     * @param source Note that this is used only for reporting errors.
     */
    protected static SAMFileHeader readHeader(final BinaryCodec stream, final ValidationStringency validationStringency, final String source)
        throws IOException {

        final byte[] buffer = new byte[4];
        stream.readBytes(buffer);
        if (!Arrays.equals(buffer, BAMFileConstants.BAM_MAGIC)) {
            throw new IOException("Invalid BAM file header");
        }

        final int headerTextLength = stream.readInt();
        final String textHeader = stream.readString(headerTextLength);
        final SAMTextHeaderCodec headerCodec = new SAMTextHeaderCodec();
        headerCodec.setValidationStringency(validationStringency);
        final SAMFileHeader samFileHeader = headerCodec.decode(BufferedLineReader.fromString(textHeader),
                source);

        final int sequenceCount = stream.readInt();
        if (!samFileHeader.getSequenceDictionary().isEmpty()) {
            // It is allowed to have binary sequences but no text sequences, so only validate if both are present
            if (sequenceCount != samFileHeader.getSequenceDictionary().size()) {
                throw new SAMFormatException("Number of sequences in text header (" +
                        samFileHeader.getSequenceDictionary().size() +
                        ") != number of sequences in binary header (" + sequenceCount + ") for file " + source);
            }
            for (int i = 0; i < sequenceCount; i++) {
                final SAMSequenceRecord binarySequenceRecord = readSequenceRecord(stream, source);
                final SAMSequenceRecord sequenceRecord = samFileHeader.getSequence(i);
                if (!sequenceRecord.getSequenceName().equals(binarySequenceRecord.getSequenceName())) {
                    throw new SAMFormatException("For sequence " + i + ", text and binary have different names in file " +
                            source);
                }
                if (sequenceRecord.getSequenceLength() != binarySequenceRecord.getSequenceLength()) {
                    throw new SAMFormatException("For sequence " + i + ", text and binary have different lengths in file " +
                            source);
                }
            }
        } else {
            // If only binary sequences are present, copy them into samFileHeader
            final List<SAMSequenceRecord> sequences = new ArrayList<SAMSequenceRecord>(sequenceCount);
            for (int i = 0; i < sequenceCount; i++) {
                sequences.add(readSequenceRecord(stream, source));
            }
            samFileHeader.setSequenceDictionary(new SAMSequenceDictionary(sequences));
        }

        return samFileHeader;
    }

    /**
     * Reads a single binary sequence record from the file or stream
     * @param source Note that this is used only for reporting errors.
     */
    private static SAMSequenceRecord readSequenceRecord(final BinaryCodec stream, final String source) {
        final int nameLength = stream.readInt();
        if (nameLength <= 1) {
            throw new SAMFormatException("Invalid BAM file header: missing sequence name in file " + source);
        }
        final String sequenceName = stream.readString(nameLength - 1);
        // Skip the null terminator
        stream.readByte();
        final int sequenceLength = stream.readInt();
        return new SAMSequenceRecord(SAMSequenceRecord.truncateSequenceName(sequenceName), sequenceLength);
    }

    /**
     * Encapsulates the restriction that only one iterator may be open at a time.
     */
    private abstract class AbstractBamIterator implements CloseableIterator<SAMRecord> {

        private boolean isClosed = false;

        @Override
        public void close() {
            if (!isClosed) {
                if (mCurrentIterator != null && this != mCurrentIterator) {
                    throw new IllegalStateException("Attempt to close non-current iterator");
                }
                mCurrentIterator = null;
                isClosed = true;
            }
        }

        protected void assertOpen() {
            if (isClosed) throw new AssertionError("Iterator has been closed");
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Not supported: remove");
        }

    }

    private class EmptyBamIterator extends AbstractBamIterator {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public SAMRecord next() {
            throw new NoSuchElementException("next called on empty iterator");
        }
    }

    /**

    /**
     * Iterator for non-indexed sequential iteration through all SAMRecords in file.
     * Starting point of iteration is wherever current file position is when the iterator is constructed.
     */
    private class BAMFileIterator extends AbstractBamIterator {
        private SAMRecord mNextRecord = null;
        private final BAMRecordCodec bamRecordCodec;
        private long samRecordIndex = 0; // Records at what position (counted in records) we are at in the file

        BAMFileIterator() {
            this(true);
        }

        /**
         * @param advance Trick to enable subclass to do more setup before advancing
         */
        BAMFileIterator(final boolean advance) {
            this.bamRecordCodec = new BAMRecordCodec(getFileHeader(), samRecordFactory);
            this.bamRecordCodec.setInputStream(BAMFileReader.this.mStream.getInputStream(),
                    BAMFileReader.this.mStream.getInputFileName());

            if (advance) {
                advance();
            }
        }

        @Override
        public boolean hasNext() {
            assertOpen();
            return (mNextRecord != null);
        }

        @Override
        public SAMRecord next() {
            assertOpen();
            final SAMRecord result = mNextRecord;
            advance();
            return result;
        }

        void advance() {
            try {
                mNextRecord = getNextRecord();

                if (mNextRecord != null) {
                    ++this.samRecordIndex;
                    // Because some decoding is done lazily, the record needs to remember the validation stringency.
                    mNextRecord.setValidationStringency(mValidationStringency);

                    if (mValidationStringency != ValidationStringency.SILENT) {
                        final List<SAMValidationError> validationErrors = mNextRecord.isValid(mValidationStringency == ValidationStringency.STRICT);
                        SAMUtils.processValidationErrors(validationErrors,
                                this.samRecordIndex, BAMFileReader.this.getValidationStringency());
                    }
                }
                if (eagerDecode && mNextRecord != null) {
                    mNextRecord.eagerDecode();
                }
            } catch (final IOException exc) {
                throw new RuntimeIOException(exc.getMessage(), exc);
            }
        }

        /**
         * Read the next record from the input stream.
         */
        SAMRecord getNextRecord() throws IOException {
            final long startCoordinate = mCompressedInputStream.getFilePointer();
            final SAMRecord next = bamRecordCodec.decode();
            final long stopCoordinate = mCompressedInputStream.getFilePointer();

            if(mReader != null && next != null)
                next.setFileSource(new SAMFileSource(mReader,new BAMFileSpan(new Chunk(startCoordinate,stopCoordinate))));

            return next;
        }

        /**
         * @return The record that will be return by the next call to next()
         */
        protected SAMRecord peek() {
            return mNextRecord;
        }
    }

    /**
     * Prepare to iterate through SAMRecords in the given reference that start exactly at the given start coordinate.
     * @param referenceIndex Desired reference sequence.
     * @param start 1-based alignment start.
     */
    private CloseableIterator<SAMRecord> createStartingAtIndexIterator(final int referenceIndex,
                                                                       final int start) {

        // Hit the index to determine the chunk boundaries for the required data.
        final BAMIndex fileIndex = getIndex();
        final BAMFileSpan fileSpan = fileIndex.getSpanOverlapping(referenceIndex, start, 0);
        final long[] filePointers = fileSpan != null ? fileSpan.toCoordinateArray() : null;

        // Create an iterator over the above chunk boundaries.
        final BAMFileIndexIterator iterator = new BAMFileIndexIterator(filePointers);

        // Add some preprocessing filters for edge-case reads that don't fit into this
        // query type.
        return new BAMQueryFilteringIterator(iterator,new BAMStartingAtIteratorFilter(referenceIndex,start));
    }

    /**
     * Use the index to determine the chunk boundaries for the required intervals.
     * @param intervals the intervals to restrict reads to
     * @param fileIndex the BAM index to use
     * @return file pointer pairs corresponding to chunk boundaries
     */
    public static BAMFileSpan getFileSpan(QueryInterval[] intervals, BAMIndex fileIndex) {
        final BAMFileSpan[] inputSpans = new BAMFileSpan[intervals.length];
        for (int i = 0; i < intervals.length; ++i) {
            final QueryInterval interval = intervals[i];
            final BAMFileSpan span = fileIndex.getSpanOverlapping(interval.referenceIndex, interval.start, interval.end);
            inputSpans[i] = span;
        }
        final BAMFileSpan span;
        if (inputSpans.length > 0) {
            span = BAMFileSpan.merge(inputSpans);
        } else {
            span = null;
        }
        return span;
    }

    private CloseableIterator<SAMRecord> createIndexIterator(final QueryInterval[] intervals,
                                                             final boolean contained) {

        QueryInterval.assertIntervalsOptimized(intervals);

        BAMFileSpan span = getFileSpan(intervals, getIndex());

        // Create an iterator over the above chunk boundaries.
        final BAMFileIndexIterator iterator = new BAMFileIndexIterator(span == null ? null : span.toCoordinateArray());

        // Add some preprocessing filters for edge-case reads that don't fit into this
        // query type.
        return new BAMQueryFilteringIterator(iterator, new BAMQueryMultipleIntervalsIteratorFilter(intervals, contained));
    }

    /**
     * Prepare to iterate through SAMRecords that match the given intervals.
     * @param intervals the intervals to restrict reads to
     * @param contained if <code>true</code>, return records that are strictly
     *                  contained in the intervals, otherwise return records that overlap
     * @param filePointers file pointer pairs corresponding to chunk boundaries for the
     *                     intervals
     */
    public CloseableIterator<SAMRecord> createIndexIterator(final QueryInterval[] intervals,
                                                            final boolean contained,
                                                            final long[] filePointers) {

        QueryInterval.assertIntervalsOptimized(intervals);

        // Create an iterator over the above chunk boundaries.
        final BAMFileIndexIterator iterator = new BAMFileIndexIterator(filePointers);

        // Add some preprocessing filters for edge-case reads that don't fit into this
        // query type.
        return new BAMQueryFilteringIterator(iterator, new BAMQueryMultipleIntervalsIteratorFilter(intervals, contained));
    }

    /**
     * @return a virtual file pointer for the underlying compressed stream.
     * @see BlockCompressedInputStream#getFilePointer()
     */
    public long getVirtualFilePointer() {
        return mCompressedInputStream.getFilePointer();
    }

    /**
     * Iterate over the SAMRecords defined by the sections of the file described in the ctor argument.
     */
    private class BAMFileIndexIterator extends BAMFileIterator {

        private long[] mFilePointers = null;
        private int mFilePointerIndex = 0;
        private long mFilePointerLimit = -1;

        /**
         * Prepare to iterate through SAMRecords stored in the specified compressed blocks at the given offset.
         * @param filePointers the block / offset combination, stored in chunk format.
         */
        BAMFileIndexIterator(final long[] filePointers) {
            super(false);  // delay advance() until after construction
            mFilePointers = filePointers;
            advance();
        }

        @Override
        SAMRecord getNextRecord()
            throws IOException {
            // Advance to next file block if necessary
            while (mCompressedInputStream.getFilePointer() >= mFilePointerLimit) {
                if (mFilePointers == null ||
                        mFilePointerIndex >= mFilePointers.length) {
                    return null;
                }
                final long startOffset = mFilePointers[mFilePointerIndex++];
                final long endOffset = mFilePointers[mFilePointerIndex++];
                mCompressedInputStream.seek(startOffset);
                mFilePointerLimit = endOffset;
            }
            // Pull next record from stream
            return super.getNextRecord();
        }
    }

    /**
     * Pull SAMRecords from a coordinate-sorted iterator, and filter out any that do not match the filter.
     */
    public class BAMQueryFilteringIterator extends AbstractBamIterator {
        /**
         * The wrapped iterator.
         */
        protected final CloseableIterator<SAMRecord> wrappedIterator;
        /**
         * The next record to be returned.  Will be null if no such record exists.
         */
        protected SAMRecord mNextRecord;
        private final BAMIteratorFilter iteratorFilter;

        public BAMQueryFilteringIterator(final CloseableIterator<SAMRecord> iterator,
                                         final BAMIteratorFilter iteratorFilter) {
            this.wrappedIterator = iterator;
            this.iteratorFilter = iteratorFilter;
            mNextRecord = advance();
        }

        /**
         * Returns true if a next element exists; false otherwise.
         */
        @Override
        public boolean hasNext() {
            assertOpen();
            return mNextRecord != null;
        }

        /**
         * Gets the next record from the given iterator.
         * @return The next SAM record in the iterator.
         */
        @Override
        public SAMRecord next() {
            if(!hasNext())
                throw new NoSuchElementException("BAMQueryFilteringIterator: no next element available");
            final SAMRecord currentRead = mNextRecord;
            mNextRecord = advance();
            return currentRead;
        }

        SAMRecord advance() {
            while (true) {
                // Pull next record from stream
                if(!wrappedIterator.hasNext())
                    return null;

                final SAMRecord record = wrappedIterator.next();
                switch (iteratorFilter.compareToFilter(record)) {
                    case MATCHES_FILTER: return record;
                    case STOP_ITERATION: return null;
                    case CONTINUE_ITERATION: break; // keep looping
                    default: throw new SAMException("Unexpected return from compareToFilter");
                }
            }
        }
    }

    private class BAMFileIndexUnmappedIterator extends BAMFileIterator  {
        private BAMFileIndexUnmappedIterator() {
            while (this.hasNext() && peek().getReferenceIndex() != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                advance();
            }
        }
    }
}
