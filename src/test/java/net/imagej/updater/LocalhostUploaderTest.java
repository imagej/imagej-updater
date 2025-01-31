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

import static org.scijava.util.FileUtils.deleteRecursively;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.After;
import org.junit.Test;
import org.scijava.test.TestUtils;

/**
 * A conditional JUnit test for uploading via file: protocol.
 * 
 * This verifies that the file: protocol (required for Dropbox, or for scripts running
 * on the same machine as the webserver) works alright.
 * 
 * @author Johannes Schindelin
 */
public class LocalhostUploaderTest extends AbstractUploaderTestBase {
	public LocalhostUploaderTest() {
		super("localhost");
	}

	private File tmp;

	@After
	public void cleanup() {
		if (tmp != null)
			deleteRecursively(tmp);
	}

	@Test
	public void testLocalhostUpload() throws Exception {
		tmp = TestUtils.createTemporaryDirectory("localhost-upload");
		test(new FileDeleter(), "file:localhost", tmp.getAbsolutePath());
	}

	@Override
	public String getURL() {
		try {
			return url = tmp.toURI().toURL().toString();
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}

	private class FileDeleter implements AbstractUploaderTestBase.Deleter {
		@Override
		public boolean login() {
			return true;
		}

		@Override
		public void logout() {}

		@Override
		public void delete(final String path) throws IOException {
			final File file = files.prefix(path);
			if (!file.delete()) throw new IOException("Could not delete " + file);
		}
	}
}
