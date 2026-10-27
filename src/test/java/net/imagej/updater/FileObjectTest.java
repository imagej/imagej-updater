/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2026 Board of Regents of the University of
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.imagej.updater.FileObject.Action;
import net.imagej.updater.FileObject.Status;

import org.junit.Test;

/**
 * Tests {@link FileObject}.
 *
 * @author Curtis Rueden
 */
public class FileObjectTest {

	@Test
	public void testStageForUpdateSkipsInactivePlatformDependencies() {
		final FilesCollection files = new FilesCollection(null);
		files.setActivePlatforms("macos-arm64");

		final FileObject app = notInstalled("jars/app.jar");
		final FileObject macNative = notInstalled("jars/macos-arm64/native.jar");
		macNative.addPlatform("macos-arm64");
		final FileObject winNative = notInstalled("jars/win64/native.jar");
		winNative.addPlatform("win64");
		app.addDependency(macNative.filename, 1, false);
		app.addDependency(winNative.filename, 1, false);
		files.add(app);
		files.add(macNative);
		files.add(winNative);

		assertTrue(app.stageForUpdate(files, false));
		assertEquals(Action.INSTALL, app.getAction());
		assertEquals(Action.INSTALL, macNative.getAction());
		assertEquals(Action.NOT_INSTALLED, winNative.getAction());
	}

	private static FileObject notInstalled(final String filename) {
		return new FileObject("Test", filename, 1, "0123456789abcdef", 1,
			Status.NOT_INSTALLED);
	}
}
