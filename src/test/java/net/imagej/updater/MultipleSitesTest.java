/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2015 Board of Regents of the University of
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

import static net.imagej.updater.UpdaterTestUtils.addUpdateSite;
import static net.imagej.updater.UpdaterTestUtils.assertAction;
import static net.imagej.updater.UpdaterTestUtils.assertCount;
import static net.imagej.updater.UpdaterTestUtils.assertStatus;
import static net.imagej.updater.UpdaterTestUtils.cleanup;
import static net.imagej.updater.UpdaterTestUtils.initialize;
import static net.imagej.updater.UpdaterTestUtils.readFile;
import static net.imagej.updater.UpdaterTestUtils.main;
import static net.imagej.updater.UpdaterTestUtils.update;
import static net.imagej.updater.UpdaterTestUtils.writeFile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Iterator;

import net.imagej.updater.FileObject.Action;
import net.imagej.updater.FileObject.Status;
import net.imagej.updater.FileObject.Version;
import net.imagej.updater.util.StderrProgress;

import org.junit.After;
import org.junit.Test;

/**
 * Verifies that multiple update sites are handled correctly.
 * 
 * @author Johannes Schindelin
 */
public class MultipleSitesTest {
	protected FilesCollection files;
	protected StderrProgress progress = new StderrProgress();

	@After
	public void after() {
		if (files != null) cleanup(files);
	}

	@Test
	public void testThreeSites() throws Exception {
		final String macro = "macros/macro.ijm";
		files = initialize(macro);

		final String check1 = files.get(macro).getChecksum();

		File ijRoot = files.prefix("");
		writeFile(new File(ijRoot, macro), "Egads!");
		addUpdateSite(files, "second");
		files = main(files, "upload", "--force-shadow", "--update-site", "second", macro);
		final String check2 = files.get(macro).getChecksum();

		writeFile(new File(ijRoot, macro), "Narf!");
		addUpdateSite(files, "third");
		files = main(files, "upload", "--force-shadow", "--update-site", "third", macro);
		final String check3 = files.get(macro).getChecksum();

		assertCount(1, files);
		assertEquals(check3, files.get(macro).getChecksum());

		files.removeUpdateSite("third");
		assertCount(1, files);
		assertEquals(check2, files.get(macro).getChecksum());
		final Iterator<Version> iterator = files.get(macro).previous.iterator();
		assertEquals(check1, iterator.next().checksum);
		assertFalse(iterator.hasNext());
		assertEquals(Action.UPDATE, files.get(macro).getAction());

		files.removeUpdateSite("second");
		assertCount(1, files);
		assertEquals(check1, files.get(macro).getChecksum());
		assertFalse(files.get(macro).previous.iterator().hasNext());
		assertEquals(Action.UPDATE, files.get(macro).getAction());

		files.removeUpdateSite(FilesCollection.DEFAULT_UPDATE_SITE);
		assertCount(1, files);
		assertTrue(files.get(macro).isObsolete());
		assertEquals(Status.OBSOLETE, files.get(macro).getStatus());
		assertEquals(Action.UNINSTALL, files.get(macro).getAction());
	}

	@Test
	public void testSiteActivation() throws Exception {
		final String installed = "macros/installed.ijm";
		final String locallyModified = "macros/locally-modified.ijm";
		final String localOnly = "macros/local-only.ijm";
		final String shadowed = "macros/shadowed.ijm";
		files = initialize(installed, locallyModified, shadowed);

		File ijRoot = files.prefix("");
		writeFile(new File(ijRoot, shadowed), "Narf!");
		final String onlyOnSecond = "macros/only-on-second.ijm";
		writeFile(new File(ijRoot, onlyOnSecond), "Egads!");
		writeFile(new File(ijRoot, locallyModified), "Magic!");
		writeFile(new File(ijRoot, localOnly), "Johnson!");
		addUpdateSite(files, "second");
		files = main(files, "upload", "--force-shadow", "--update-site", "second", shadowed, onlyOnSecond);

		assertCount(5, files);
		assertCount(4, files.installed());
		assertCount(2, files.forUpdateSite("second"));

		files.deactivateUpdateSite(files.getUpdateSite("second", false));
		assertAction(Action.INSTALLED, files.get(installed));
		assertAction(Action.UPDATE, files.get(shadowed));
		assertAction(Action.UNINSTALL, files.get(onlyOnSecond));
		assertStatus(Status.LOCAL_ONLY, files.get(localOnly));
		assertStatus(Status.MODIFIED, files.get(locallyModified));
		update(files);
		files.write();
		files = main(files, "list");
		assertCount(0, files.changes());

		files.activateUpdateSite(files.getUpdateSite("second", true), progress);
		assertAction(Action.INSTALLED, files.get(installed));
		assertAction(Action.UPDATE, files.get(shadowed));
		assertAction(Action.INSTALL, files.get(onlyOnSecond));
		assertStatus(Status.LOCAL_ONLY, files.get(localOnly));
		assertStatus(Status.MODIFIED, files.get(locallyModified));
		update(files);
		files.write();
		files = main(files, "list");
		assertCount(0, files.changes());

		files.removeUpdateSite("second");
		assertCount(2, files.updateable(false));
		assertStatus(Status.LOCAL_ONLY, files.get(localOnly));
		assertStatus(Status.MODIFIED, files.get(locallyModified));
		// Thank you, Windows. Thank you so much for all that time with you.
		final String lf = System.getProperty("line.separator");
		assertEquals("Magic!" + lf, readFile(new File(ijRoot, locallyModified)));
		assertEquals("Johnson!" + lf, readFile(new File(ijRoot, localOnly)));
	}
}
