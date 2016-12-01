/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2016 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package net.imagej.updater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;

import org.scijava.util.LongArray;

/**
 * Analyses {@code .dll} files whether they are <i>really</i> different.
 * <p>
 * Microsoft decided that {@code .dll} files should contain timestamps. While
 * arguably valuable metadata, it is unfortunate that a simply byte-wise
 * comparison of two {@code .dll} files bears no meaning as to whether the
 * file's <i>function</i> is different, because time stamps are engrained in the
 * {@code .dll} files.
 * </p>
 * <p>
 * This class knows about the details of the {@code .dll} file format and uses
 * that knowledge to determine whether two {@code .dll} files are <i>really</i>
 * different.
 * </p>
 * 
 * @author Johannes Schindelin
 */
public class DllFile {

	public static void main(String... args) throws MalformedURLException {
		File a = new File(System.getProperty("user.home") + "/ij2/updater/a1.dll");
		File b = new File(System.getProperty("user.home") + "/ij2/updater/c2.dll");
		System.err.println("equal: " + DllFile.equals(a, b.toURI().toURL()));
	}

	/**
	 * Compares two {@code .dll} files, skipping time stamp entries, checksums and the likes.
	 * 
	 * @param a the first {@code .dll} file
	 * @param b the second {@code .dll} file
	 * @return true if the two files are functionally identical
	 */
	public static boolean equals(final File a, final URL b) {
		try {
			final DllFile dllA = new DllFile(a);
			try {
				return dllA.equals(b.openConnection());
			}
			finally {
				dllA.close();
			}
		}
		catch (IOException e) {
			return false;
		}
	}

	private final RandomAccessFile ra;
	private long[] rvaMap;
	private long dataDirectoryOffset;
	private LongArray toSkip;

	/**
	 * Constructs a {@code .dll} file parser.
	 * 
	 * @param file the path to the {@code .dll} file
	 * @throws IOException
	 */
	public DllFile(final File file) throws IOException {
		ra = new RandomAccessFile(file, "r");

		ra.seek(0x3c);
		final int peOffset = read32();

		ra.seek(peOffset);

		final int signature = read32();
		if (signature != ('P' | ('E' << 8))) {
			ra.close();
			throw new IllegalArgumentException("Not a DLL!!!" + String.format("0x%08x", signature ));
		}
	}

	/**
	 * Closes the underlying {@link RandomAccessFile}.
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException {
		ra.close();
	}

	/**
	 * Determines whether two {@code .dll} files are functionally equal.
	 * 
	 * @param o the second {@code .dll} file
	 * @return true if the two files differ only in timestamps, checksums and
	 *         volatile GUIDs
	 * @throws IOException
	 */
	public boolean equals(final URLConnection o) throws IOException {
		if (ra.length() != o.getContentLength()) return false;
		initialize();
		long[] toSkip = this.toSkip.copyArray();
		Arrays.sort(toSkip);
		final InputStream in = o.getInputStream();
		try {
			for (int i = 0; i + 1 < toSkip.length; i += 2) {
				ra.seek(toSkip[i]);
				long skipCount = toSkip[i] - (i > 0 ? toSkip[i - 1] : 0);
				if (skipCount != 0) {
					in.skip(skipCount);
				}
				if (!equals(in, toSkip[i + 1] - toSkip[i])) {
					return false;
				}
			}
		}
		finally {
			in.close();
		}
		return true;
	}

	// -- private methods --

	private void addSkip(long offset, long length) {
		toSkip.add(offset);
		toSkip.add(offset + length);
	}

	private long rva2offset(long rva) {
		for (int i = 0; i + 2 < rvaMap.length; i += 3) {
			long base = rvaMap[i];
			if (rva >= base && rva < base + rvaMap[i + 1]) {
				return rva + rvaMap[i + 2] - base;
			}
		}
		return -1;
	}

	private int read16() throws IOException {
		return (ra.readByte() & 0xff) | ((ra.readByte() & 0xff) << 8);
	}

	private int read32() throws IOException {
		return (ra.readByte() & 0xff) | ((ra.readByte() & 0xff) << 8) |
		 ((ra.readByte() & 0xff) << 16) | ((ra.readByte() & 0xff) << 24);
	}

	/**
	 * Parses the {@code .dll} file and determines which sections to skip when comparing with another {@code .dll} file of the exact same size.
	 * 
	 * <p>
	 * The code is based on the analysis of Micro-Manager-dev's mmgr_dal_Scientifica.dll, employing the
	 * documentation from http://www.microsoft.com/whdc/system/platform/firmware/PECOFF.mspx and
	 * from http://www.godevtool.com/Other/pdb.htm. Unfortunately, http://support.microsoft.com/kb/164151 was not helpful at all.
	 * </p>
	 * <p>
	 * In practice, recompiling {@code .dll} files without any change in the source code will result in the following differences:
	 * <ul>
	 * <li>the time stamp in the global header</li>
	 * <li>the checksum in the global header</li>
	 * <li>the time stamp in the debug table header</li>
	 * <li>the GUID in the CODEVIEW ({@code .pdb}) section referenced in the the debug table header</li>
	 * <li>the time stamp in the export table header</li>
	 * </ul>
	 * As per the specification, the headers of the load config table and of the import table also contain time stamps. For good
	 * measure, we skip those entries when comparing two {@code .dll} files even if the time stamps seem to be set to 0 in all
	 * encountered examples.
	 * 
	 * @throws IOException
	 */
	private void initialize() throws IOException {
		if (toSkip != null) return;

		toSkip = new LongArray();
		toSkip.add(0l);

		ra.skipBytes(2); // skip machine
		final int sectionCount = read16();

		addSkip(ra.getFilePointer(), 4); // global time date stamp
		ra.skipBytes(12); // skip checksum, symbol table offset and symbol count
		final int optHeaderSize = read16();
		ra.skipBytes(2); // skip characteristics
		final long optHeaderOffset = ra.getFilePointer();

		addSkip(optHeaderOffset + 0x40, 4); // checksum

		final long firstSectionOffset = optHeaderOffset + optHeaderSize;
		rvaMap = new long[sectionCount * 3];
		for (int i = 0; i < sectionCount; i++) {
			ra.seek(firstSectionOffset + i * 0x28 + 0xc);
			for (int j = 0; j < 3; j++) {
				rvaMap[3 * i + j] = read32() & (long)-1;
			}
		}

		// Skip global checksum and global, debug table, export table, import table
		// and load configuration time stamp, and the GUID of the .pdb. 

		if (optHeaderSize == 0xe0 || optHeaderSize == 0xf0) {
			dataDirectoryOffset = optHeaderOffset + optHeaderSize - 0x80;
		} else {
			throw new IOException("Unknown optHeaderSize: " + String.format("0x%04x", new Object[] { optHeaderSize }));
		}

		// export table timestamp
		ra.seek(dataDirectoryOffset);
		int rva = read32();
		long offset = rva2offset(rva);
		if (offset > 0 && read32() >= 8) {
			addSkip(offset + 4, 4);
		}

		// import table timestamp
		ra.seek(dataDirectoryOffset + 8);
		rva = read32();
		offset = rva2offset(rva);
		if (offset > 0 && read32() >= 8) {
			addSkip(offset + 4, 4);
		}

		// debug table timestamp and .pdb GUID/path
		ra.seek(dataDirectoryOffset + 6 * 8);
		rva = read32();
		offset = rva2offset(rva);
		if (offset > 0 && read32() >= 0x1c) {
			addSkip(offset + 4, 4);
			/*
			 * The "CODEVIEW" debug directory contains .pdb file information in the following format:
			 *
			 *   +0h   dword        "RSDS" signature
			 *   +4h   GUID         16-byte Globally Unique Identifier
			 *   +14h  dword        "age"
			 *   +18h  byte string  zero terminated UTF8 path and file name
			 * 
			 * This debug directory is generated even for release mode!
			 */
			ra.seek(offset + 0x10);
			int size = read32();
			ra.skipBytes(4); // RVA
			int filePointer = read32();
			if (filePointer > 0) {
				addSkip(filePointer, size);
			}
		}

		toSkip.add(ra.length());
	}

	private boolean equals(final InputStream in, long length) throws IOException {
		byte[] buffer = new byte[65536], otherBuffer = new byte[65536];
		while (length > 0) {
			int count = buffer.length;
			if (count > length) {
				count = (int) length;
			}
			ra.readFully(buffer, 0, count);
			int offset = 0;
			while (offset < count) {
				int count2 = in.read(otherBuffer, offset, count - offset);
				if (count2 < 0) {
					throw new IOException("Short read!");
				}
				offset += count2;
			}
			for (int i = 0; i < count; i++) {
				if (buffer[i] != otherBuffer[i]) {
					return false;
				}
			}
			length -= count;
		}
		return true;
	}
}
