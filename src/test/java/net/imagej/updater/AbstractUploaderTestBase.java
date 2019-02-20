/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2017 Board of Regents of the University of
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

import net.imagej.updater.util.StderrProgress;
import net.imagej.updater.util.UpdaterUtil;
import org.apache.commons.lang.NotImplementedException;
import org.junit.After;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import static net.imagej.updater.UpdaterTestUtils.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

/**
 * An abstract base class for testing uploader backends.
 * 
 * See {@link LocalhostUploaderTest} for an example how to use it.
 * 
 * @author Johannes Schindelin
 */
public abstract class AbstractUploaderTestBase {
	protected final String propertyPrefix, updateSiteName;
	protected String url;
	protected FilesCollection files;

	public AbstractUploaderTestBase(final String propertyPrefix) {
		this.propertyPrefix = propertyPrefix;
		updateSiteName = propertyPrefix + "-test";
	}

	@After
	public void after() {
		if (files != null) {
			files.removeUpdateSite(updateSiteName);
			cleanup(files);
		}
	}

	public void test(final Deleter deleter, final String host, final String uploadDirectory) throws Exception {
		getURL();
		files = initialize();

		File ijRoot = files.prefix("");
		CommandLine.main(ijRoot, -1, "add-update-site",
				updateSiteName, url, host, uploadDirectory);

		if (!isUpdateSiteEmpty()) {
			assertTrue(deleter.login());
			deleter.delete(UpdaterUtil.XML_COMPRESSED);
			deleter.delete("plugins/");
			assertTrue(deleter.isDeleted(UpdaterUtil.XML_COMPRESSED));
			assertTrue(deleter.isDeleted("plugins/"));
			deleter.logout();
		}

		final String path = "plugins/Say_Hello.bsh";
		final String contents = "print(\"Hello, world!\");";
		final File file = new File(ijRoot, path);
		writeFile(file, contents);

		final String path2 = "macros/Has (Spaces).ijm";
		final String contents2 = "print(\"Space. The final frontier.\");";
		final File file2 = new File(ijRoot, path2);
		writeFile(file2, contents2);

		CommandLine.main(ijRoot, -1, "upload", "--update-site", updateSiteName, path, path2);

		assertFalse(isUpdateSiteEmpty());

		files.read();
		files.clear();
		files.downloadIndexAndChecksum(new StderrProgress());
		final long timestamp = files.get(path).current.timestamp;
		final long minimalTimestamp = 20130322000000l;
		assertTrue("" + timestamp + " >= " + minimalTimestamp,
				timestamp >= minimalTimestamp);

		assertTrue(file.delete());
		assertTrue(file2.delete());
		CommandLine.main(ijRoot, -1, "update", path, path2);
		assertTrue(file.exists());
		assertTrue(file2.exists());
	}

	public String getURL() {
		return url = getDirectoryProperty("url");
	}

	public String getDirectoryProperty(final String key) {
		final String directory = getProperty(key);
		return directory.endsWith("/") ? directory : directory + "/";
	}

	public String getProperty(final String key) {
		final String result = System.getProperty(propertyPrefix + ".test." + key);
		assumeNotNull(result);
		return result;
	}

	public boolean isUpdateSiteEmpty() throws MalformedURLException, IOException {
		if (url.startsWith("file:")) {
			final File[] list = new File(url.substring(5)).listFiles();
			return list == null || list.length == 0;
		}
		HttpURLConnection connection = (HttpURLConnection) new URL(url + UpdaterUtil.XML_COMPRESSED).openConnection();
		return 404 == connection.getResponseCode();
	}

	public interface Deleter {
		boolean login();
		void delete(final String path) throws IOException;
		void logout();
		default boolean isDeleted(String path) throws IOException {
			throw new NotImplementedException();
		}
	}
}
