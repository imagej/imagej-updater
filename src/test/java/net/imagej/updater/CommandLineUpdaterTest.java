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

import static net.imagej.updater.FilesCollection.DEFAULT_UPDATE_SITE;
import static net.imagej.updater.UpdaterTestUtils.addUpdateSite;
import static net.imagej.updater.UpdaterTestUtils.assertStatus;
import static net.imagej.updater.UpdaterTestUtils.cleanup;
import static net.imagej.updater.UpdaterTestUtils.getWebRoot;
import static net.imagej.updater.UpdaterTestUtils.initialize;
import static net.imagej.updater.UpdaterTestUtils.main;
import static net.imagej.updater.UpdaterTestUtils.readFile;
import static net.imagej.updater.UpdaterTestUtils.upload;
import static net.imagej.updater.UpdaterTestUtils.writeFile;
import static net.imagej.updater.UpdaterTestUtils.writeGZippedFile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.scijava.test.TestUtils.createTemporaryDirectory;
import static org.scijava.util.FileUtils.deleteRecursively;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.imagej.updater.FileObject.Status;
import net.imagej.updater.action.Upload;
import net.imagej.updater.util.StderrProgress;
import net.imagej.updater.util.UpdaterUtil;

import org.junit.After;
import org.junit.Test;

/**
 * Tests the command-line updater.
 * 
 * @author Johannes Schindelin
 */
public class CommandLineUpdaterTest {
	protected FilesCollection files;
	protected StderrProgress progress = new StderrProgress();

	@After
	public void after() {
		if (files != null) cleanup(files);
	}

	@Test
	public void testUploadCompleteSite() throws Exception {
		final String to_remove = "macros/to_remove.ijm";
		final String modified = "macros/modified.ijm";
		final String installed = "macros/installed.ijm";
		final String new_file = "macros/new_file.ijm";
		files = initialize(to_remove, modified, installed);

		File ijRoot = files.prefix("");
		writeFile(new File(ijRoot, modified), "Zing! Zing a zong!");
		writeFile(new File(ijRoot, new_file), "Aitch!");
		assertTrue(new File(ijRoot, to_remove).delete());

		files = main(files, "upload-complete-site", FilesCollection.DEFAULT_UPDATE_SITE);

		assertStatus(Status.OBSOLETE_UNINSTALLED, files, to_remove);
		assertStatus(Status.INSTALLED, files, modified);
		assertStatus(Status.INSTALLED, files, installed);
		assertStatus(Status.INSTALLED, files, new_file);
	}

	@Test
	public void testUpload() throws Exception {
		files = initialize();

		final String path = "macros/test.ijm";
		final File file = files.prefix(path);
		writeFile(file, "// test");
		files = main(files, "upload", "--update-site", DEFAULT_UPDATE_SITE, path);

		assertStatus(Status.INSTALLED, files, path);

		assertTrue(file.delete());
		files = main(files, "upload", path);

		assertStatus(Status.OBSOLETE_UNINSTALLED, files, path);
	}

	@Test
	public void testUploadCompleteSiteWithShadow() throws Exception {
		final String path = "macros/test.ijm";
		final String obsolete = "macros/obsolete.ijm";
		files = initialize(path, obsolete);

		assertTrue(files.prefix(obsolete).delete());
		files = main(files, "upload", obsolete);

		final File tmp = addUpdateSite(files, "second");
		writeFile(files.prefix(path), "// shadowing");
		writeFile(files.prefix(obsolete), obsolete);
		files = main(files, "upload-complete-site", "--force-shadow", "second");

		assertStatus(Status.INSTALLED, files, path);
		assertStatus(Status.INSTALLED, files, obsolete);
		files = main(files, "remove-update-site", "second");

		assertStatus(Status.MODIFIED, files, path);
		assertStatus(Status.OBSOLETE, files, obsolete);

		assertTrue(deleteRecursively(tmp));
	}

	@Test
	public void testForcedShadow() throws Exception {
		final String path = "macros/test.ijm";
		files = initialize(path);

		final File tmp = addUpdateSite(files, "second");
		files = main(files, "upload", "--update-site", "second", "--force-shadow", path);

		final File onSecondSite = new File(tmp, path + "-" + files.get(path).current.timestamp);
		assertTrue("File exists: " + onSecondSite, onSecondSite.exists());
	}

	@Test
	public void testUploadCompleteSiteWithPlatforms() throws Exception {
		final String macro = "macros/macro.ijm";
		final String linux32 = "lib/linux32/libtest.so";
		final String linux32_jar = "jars/linux32/test.jar";
		files = initialize(macro, linux32, linux32_jar);

		assertPlatforms(files.get(linux32), "linux32");
		assertPlatforms(files.get(linux32_jar), "linux32");

		File ijRoot = files.prefix("");
		final String win64 = "lib/win64/test.dll";
		final String win64_jar = "jars/win64/test.jar";
		assertTrue(new File(ijRoot, linux32).delete());
		assertTrue(new File(ijRoot, linux32_jar).delete());
		writeFile(new File(ijRoot, win64), "Dummy");
		writeFile(new File(ijRoot, win64_jar), "Dummy jar");
		writeFile(new File(ijRoot, macro), "New version");
		files = main(files, "upload-complete-site", "--platforms", "win64", FilesCollection.DEFAULT_UPDATE_SITE);

		assertStatus(Status.NOT_INSTALLED, files, linux32);
		assertStatus(Status.NOT_INSTALLED, files, linux32_jar);
		assertStatus(Status.INSTALLED, files, win64);
		assertStatus(Status.INSTALLED, files, win64_jar);
		assertStatus(Status.INSTALLED, files, macro);

		files = main(files, "upload-complete-site", "--platforms", "all", FilesCollection.DEFAULT_UPDATE_SITE);
		assertStatus(Status.OBSOLETE_UNINSTALLED, files, linux32);
		assertStatus(Status.OBSOLETE_UNINSTALLED, files, linux32_jar);
	}

	@Test
	public void testRemoveFile() throws Exception {
		final String macro = "macros/macro.ijm";
		files = initialize(macro);

		assertTrue(files.prefix(files.get(macro)).delete());
		files = main(files, "upload", macro);

		assertStatus(Status.OBSOLETE_UNINSTALLED, files, macro);
	}

	private void assertPlatforms(final FileObject file, final String... platforms) {
		final Set<String> filePlatforms = new HashSet<>();
		for (final String platform : file.getPlatforms()) filePlatforms.add(platform);
		assertEquals(platforms.length, filePlatforms.size());
		for (final String platform : platforms) {
			assertTrue(file.getFilename(true) + "'s platforms should contain " + platform
				+ " (" + filePlatforms + ")", filePlatforms.contains(platform));
		}
	}

	@Test
	public void testCircularDependenciesInOtherSite() throws Exception {
		files = initialize();

		final File second = addUpdateSite(files, "second");
		final File third = addUpdateSite(files, "third");

		// fake circular dependencies
		writeGZippedFile(second, "db.xml.gz", "<pluginRecords>"
				+ "<plugin filename=\"jars/a.jar\">"
				+ "<version checksum=\"1\" timestamp=\"2\" filesize=\"3\" />"
				+ "<dependency filename=\"jars/b.jar\" timestamp=\"2\" />"
				+ "</plugin>"
				+ "<plugin filename=\"jars/b.jar\">"
				+ "<version checksum=\"4\" timestamp=\"2\" filesize=\"5\" />"
				+ "<dependency filename=\"jars/a.jar\" timestamp=\"2\" />"
				+ "</plugin>"
				+ "</pluginRecords>");

		writeFile(files, "macros/a.ijm");
		files = main(files, "upload", "--update-site", "third", "macros/a.ijm");
		final File uploaded = new File(third, "macros/a.ijm-" + files.get("macros/a.ijm").current.timestamp);
		assertTrue(uploaded.exists());

		// make sure that circular dependencies are still reported when uploading to the site
		writeFile(files, "macros/b.ijm");
		try {
			files = main(files, "upload", "--update-site", "second", "macros/b.ijm");
			assertTrue("Circular dependency not reported!", false);
		} catch (RuntimeException e) {
			assertEquals("Circular dependency detected: jars/b.jar -> jars/a.jar -> jars/b.jar\n",
					e.getMessage());
		}
	}

	@Test
	public void testUploadUnchangedNewVersion() throws Exception {
		final String path1 = "jars/test-1.0.0.jar";
		final String path2 = "jars/test-1.0.1.jar";
		files = initialize(path1);

		files = main(files, "upload", "--update-site", DEFAULT_UPDATE_SITE, path1);

		assertStatus(Status.INSTALLED, files, path1);

		final File file1 = files.prefix(path1);
		final File file2 = files.prefix(path2);
		assertTrue(file1.renameTo(file2));
		files = main(files, "upload", path2);

		assertFalse(file1.exists());
		assertTrue(file2.delete());
		files = main(files, "update-force-pristine");

		assertEquals(path2, files.get(path2).filename);
		assertStatus(Status.INSTALLED, files, path2);
		assertFalse(file1.exists());
		assertTrue(file2.exists());
	}

	@Test
	public void testMark3rdPartyPluginForUpload() throws Exception {
		final String upstream = "jars/upstream.jar";
		final String upstream2 = "jars/upstream-1.0.1.jar";
		final String thirdparty = "jars/thirdparty.jar";

		files = initialize(upstream);

		files =
			main(files, "upload", "--update-site", DEFAULT_UPDATE_SITE, upstream);

		// rename the file
		assertTrue(files.prefix(upstream).renameTo(files.prefix(upstream2)));

		// now revoke the upload permission
		final URL webRoot = getWebRoot(files, DEFAULT_UPDATE_SITE).toURI().toURL();
		files =
			main(files, "edit-update-site", DEFAULT_UPDATE_SITE, webRoot.toString());

		// write the file
		writeFile(files.prefix(thirdparty), "This file wants to be uploaded!");
		files = main(files, "list");
		assertFalse("Can upload!", isUploadable(files, thirdparty));

		// add a personal update site
		addUpdateSite(files, "personal");
		files = main(files, "list-update-sites");
		assertTrue("Cannot upload!", isUploadable(files, thirdparty));

		// add the upload permission again, to facilitate cleanup
		files =
			main(files, "edit-update-site", DEFAULT_UPDATE_SITE, webRoot.toString(),
				"file:localhost", webRoot.getPath());
	}

	private static boolean isUploadable(FilesCollection files, String filename) {
		Set<GroupAction> valid =
			files.getValidActions(Collections.singleton(files.get(filename)));
		for (final GroupAction action : valid) {
			if (action instanceof Upload) return true;
		}
		return false;
	}

	@Test
	public void testRemoveWithObsoleteDependencies() throws Exception {
		final String upstream = "jars/upstream.jar";
		final String downstream = "jars/downstream.jar";

		files = initialize(upstream);

		files =
			main(files, "upload", "--update-site", DEFAULT_UPDATE_SITE, upstream);

		// make a second ImageJ.app and add a personal update site
		final File tmp = createTemporaryDirectory("ij-too-");
		final String webRoot = files.getUpdateSite(DEFAULT_UPDATE_SITE, false).getURL();
		CommandLine.main(tmp, 80, "edit-update-site", DEFAULT_UPDATE_SITE, webRoot);
		FilesCollection files2 = new FilesCollection(tmp);
		files2 = main(files2, "update");
		addUpdateSite(files2, "personal");

		// upload downstream to the personal update site
		writeFile(files2.prefix(downstream), "This file wants to be uploaded!");
		files2 = main(files2, "list");
		final FileObject down = files2.get(downstream);
		down.addDependency(files2, files2.get(upstream));
		down.stageForUpload(files2, "personal");
		upload(files2, "personal");

		// now mark the old file as obsolete
		assertTrue(files.prefix(upstream).delete());
		files = main(files, "upload", upstream);

		// now mark downstream obsolete
		files2 = main(files2, "update");
		assertTrue(files2.prefix(downstream).delete());
		files2 = main(files2, "upload", downstream);
	}

	@Test
	public void testDowngrade() throws Exception {
		final String macro = "macro/test.ijm";
		files = initialize(macro);
		final long timestamp = UpdaterUtil.getTimestamp(files.prefix(macro));

		Thread.sleep(1000);
		writeFile(files, macro, "new version");
		files = main(files, "list");
		files.get(macro).stageForUpload(files, DEFAULT_UPDATE_SITE);
		upload(files);

		files = main(files, "list");
		final long newTimestamp = UpdaterUtil.getTimestamp(files.prefix(macro));
		assertTrue("Equal: " + timestamp + ", " + newTimestamp, timestamp != newTimestamp);

		files = main(files, "downgrade", "" + timestamp);
		final String contents = readFile(files.prefix(macro));
		assertEquals(macro, contents.trim());
	}

	@Test
	public void testEditUpdateSite() throws Exception {
		files = initialize();
		final File tmp = createTemporaryDirectory("auto-add-");
		writeGZippedFile(tmp, "db.xml.gz", "<pluginRecords />");
		final String siteName = "must-be-auto-added";
		files = main(files, "edit-update-site", siteName, tmp.toURI().toString());
		assertNotNull(files.getUpdateSite(siteName, false));
		files = main(files, "remove-update-site", siteName);
		assertNull(files.getUpdateSite(siteName, false));
	}
}
