/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2025 Board of Regents of the University of
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.zip.GZIPInputStream;

import net.imagej.updater.util.AbstractProgressable;
import net.imagej.updater.util.UpdaterUtil;

/**
 * Directly in charge of downloading and saving start-up files (i.e.: XML file
 * and related).
 * 
 * @author Johannes Schindelin
 */
public class XMLFileDownloader extends AbstractProgressable {

	private final FilesCollection files;
	private final Collection<String> updateSites;
	private StringBuilder warnings;

	public XMLFileDownloader(final FilesCollection files) {
		this(files, files.getUpdateSiteNames(false));
	}

	public XMLFileDownloader(final FilesCollection files,
		final Collection<String> updateSites)
	{
		this.files = files;
		this.updateSites = updateSites;
	}

	public void start() {
		start(true);
	}


	public void start(boolean closeProgressAtEnd) {
		if (updateSites == null || updateSites.isEmpty()) return;
		setTitle("Updating the index of available files");
		final XMLFileReader reader = new XMLFileReader(files);
		final int current = 0, total = updateSites.size();
		if (warnings == null) warnings = new StringBuilder();
		else warnings.setLength(0);
		for (final String name : updateSites) {
			final UpdateSite updateSite = files.getUpdateSite(name, true);
			final String title =
				"Updating from " + (name.isEmpty() ? "main" : name) + " site: " + updateSite.getURL();
			addItem(title);
			setCount(current, total);
			try {
				final URLConnection connection =
					UpdaterUtil.openConnection(new URL(updateSite.getURL() + UpdaterUtil.XML_COMPRESSED));
				final long lastModified = connection.getLastModified();
				final int fileSize = connection.getContentLength();
				final InputStream in =
					getInputStream(new GZIPInputStream(connection.getInputStream()),
						fileSize);
				reader.read(name, in, updateSite.getTimestamp());
				in.close();
				updateSite.setLastModified(lastModified);
			}
			catch (final Exception e) {
				if (e instanceof FileNotFoundException) {
					// it was deleted
					updateSite.setLastModified(0);
					files.log.debug(e);
				} else {
					files.log.error(e);
				}
				appendWarning("Could not update from site '" + name + "': " + e);
			}
			itemDone(title);
		}
		if (closeProgressAtEnd) {
			done();
		}
		appendWarning(reader.getWarnings());
	}

	public String getWarnings() {
		return warnings.toString();
	}

	public InputStream getInputStream(final InputStream in, final int fileSize) {
		return new InputStream() {

			int current = 0;

			@Override
			public int read() throws IOException {
				final int result = in.read();
				setItemCount(++current, fileSize);
				return result;
			}

			@Override
			public int read(final byte[] b) throws IOException {
				final int result = in.read(b);
				if (result > 0) {
					current += result;
					setItemCount(current, fileSize);
				}
				return result;
			}

			@Override
			public int read(final byte[] b, final int off, final int len)
				throws IOException
			{
				final int result = in.read(b, off, len);
				if (result > 0) {
					current += result;
					setItemCount(current, fileSize);
				}
				return result;
			}

			@Override
			public void close() throws IOException {
				in.close();
			}
		};
	}

	private void appendWarning(final String s) {
		if (s == null || s.isEmpty()) return;
		warnings.append(s);
		warnings.append(System.lineSeparator());
	}
}
