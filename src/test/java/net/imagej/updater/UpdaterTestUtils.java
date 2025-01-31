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

import static net.imagej.updater.FilesCollection.DEFAULT_UPDATE_SITE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.scijava.test.TestUtils.createTemporaryDirectory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.ParserConfigurationException;

import net.imagej.updater.FileObject.Action;
import net.imagej.updater.FileObject.Status;
import net.imagej.updater.util.Progress;
import net.imagej.updater.util.StderrProgress;
import net.imagej.updater.util.UpdaterUtil;

import org.scijava.log.LogService;
import org.scijava.util.ClassUtils;
import org.scijava.util.FileUtils;
import org.xml.sax.SAXException;

/**
 * A container of functions useful for testing the updater/uploader.
 * 
 * @author Johannes Schindelin
 */
public class UpdaterTestUtils {

	/**
	 * This is a hack, albeit not completely a dumb one. As long as you have
	 * swing-updater compiled and up-to-date, you can use this method to inspect
	 * the state at any given moment
	 * 
	 * @param files The collection of files, including the current update site and
	 *          IJ root.
	 */
	public static void show(final FilesCollection files) {
		try {
			String url = ClassUtils.getLocation(UpdaterTestUtils.class).toString();
			final String suffix = "/updater/target/test-classes/";
			assertTrue(url + " ends with " + suffix, url.endsWith(suffix));
			url =
				url.substring(0, url.length() - suffix.length()) +
					"/ui/swing/target/classes/";
			final ClassLoader loader =
				new java.net.URLClassLoader(
					new java.net.URL[] { new java.net.URL(url) });
			final Class<?> clazz =
				loader.loadClass("net.imagej.ui.swing.updater.UpdaterFrame");
			final java.lang.reflect.Constructor<?> ctor =
				clazz.getConstructor(LogService.class, UploaderService.class, FilesCollection.class);
			final Object updaterFrame = ctor.newInstance(UpdaterUtil.getLogService(), null, files);
			final java.lang.reflect.Method setVisible =
				clazz.getMethod("setVisible", boolean.class);
			setVisible.invoke(updaterFrame, true);
			final java.lang.reflect.Method isVisible = clazz.getMethod("isVisible");
			for (;;) {
				Thread.sleep(1000);
				if (isVisible.invoke(updaterFrame).equals(Boolean.FALSE)) break;
			}
		}
		catch (final Throwable t) {
			t.printStackTrace();
		}
	}

	//
	// Utility functions
	//

	public static FilesCollection main(final FilesCollection files, final String... args) throws ParserConfigurationException, SAXException {
		files.prefix(".checksums").delete();
		CommandLine.main(files.prefix(""), -1, progress, args);
		return readDb(files);
	}

	public static File addUpdateSite(final FilesCollection files, final String name) throws Exception {
		final File directory = createTemporaryDirectory("update-site-" + name);
		final String url = directory.toURI().toURL().toString().replace('\\', '/');
		final String sshHost = "file:localhost";
		final String uploadDirectory = directory.getAbsolutePath();
		final FilesUploader uploader = FilesUploader.initialUploader(null, url, sshHost, uploadDirectory, progress);
		assertTrue(uploader.login());
		uploader.upload(progress);
		CommandLine.main(files.prefix(""), -1, "add-update-site", name, url, sshHost, uploadDirectory);
		System.err.println("Initialized update site at " + url);
		return directory;
	}

	protected static File makeIJRoot(final File webRoot) throws IOException {
		final File ijRoot = createTemporaryDirectory("ij-root-");
		initDb(ijRoot, webRoot);
		return ijRoot;
	}

	protected static void initDb(final FilesCollection files) throws IOException {
		initDb(files.prefix(""), getWebRoot(files));
	}

	protected static void initDb(final File ijRoot, final File webRoot) throws IOException {
		writeGZippedFile(ijRoot, "db.xml.gz", "<pluginRecords><update-site name=\""
				+ FilesCollection.DEFAULT_UPDATE_SITE + "\" timestamp=\"0\" url=\""
				+ webRoot.toURI().toURL().toString() + "\" ssh-host=\"file:localhost\" "
				+ "upload-directory=\"" + webRoot.getAbsolutePath() + "\"/></pluginRecords>");
	}

	public static FilesCollection initialize(final String... fileNames) throws Exception {
		return initialize(null, null, fileNames);
	}

	public static FilesCollection initialize(File ijRoot, File webRoot, final String... fileNames)
			throws Exception
		{
		if (ijRoot == null) ijRoot = createTemporaryDirectory("ij-root-");
		if (webRoot == null) webRoot = createTemporaryDirectory("ij-web-");

		final File localDb = new File(ijRoot, "db.xml.gz");
		final File remoteDb = new File(webRoot, "db.xml.gz");

		// Initialize update site

		final String url = webRoot.toURI().toURL().toString() + "/";
		final String sshHost = "file:localhost";
		final String uploadDirectory = webRoot.getAbsolutePath() + "/";

		assertFalse(localDb.exists());
		assertFalse(remoteDb.exists());

		FilesUploader uploader =
			FilesUploader.initialUploader(null, url, sshHost, uploadDirectory, progress);
		assertTrue(uploader.login());
		uploader.upload(progress);

		assertFalse(localDb.exists());
		assertTrue(remoteDb.exists());
		final long remoteDbSize = remoteDb.length();

		// Write initial db.xml.gz

		FilesCollection files = new FilesCollection(ijRoot);
		useWebRoot(files, webRoot);

		if (fileNames.length == 0) {
			files.write();
			assertTrue(localDb.exists());
		} else {
			// Write files

			final List<String> list = new ArrayList<>();
			for (final String name : fileNames) {
				writeFile(new File(ijRoot, name), name);
				list.add(name);
			}

			// Initialize db.xml.gz

			if (localDb.exists()) {
				files.read(localDb);
			}
			new XMLFileReader(files).read(FilesCollection.DEFAULT_UPDATE_SITE);

			assertEquals(0, files.size());

			files.write();
			assertTrue(localDb.exists());

			final Checksummer czechsummer = new Checksummer(files, progress);
			czechsummer.updateFromLocal(list);

			for (final String name : fileNames) {
				final FileObject file = files.get(name);
				assertNotNull(name, file);
				file.stageForUpload(files, FilesCollection.DEFAULT_UPDATE_SITE);
			}
			uploader = new FilesUploader(null, files, FilesCollection.DEFAULT_UPDATE_SITE, progress);
			assertTrue(uploader.login());
			uploader.upload(progress);
			assertTrue(remoteDb.exists());
			assertNotEqual(remoteDb.length(), remoteDbSize);
		}

		return files;
	}

	public static boolean cleanup(final FilesCollection files) {
		final File ijRoot = files.prefix("");
		if (ijRoot.isDirectory() && !deleteRecursivelyAtLeastOnExit(ijRoot)) {
			System.err.println("Warning: Deleting " + ijRoot
					+ " deferred to exit");
		}
		for (String updateSite : files.getUpdateSiteNames(false)) {
			final File webRoot = getWebRoot(files, updateSite);
			if (webRoot != null && webRoot.isDirectory()
					&& !deleteRecursivelyAtLeastOnExit(webRoot)) {
				System.err.println("Warning: Deleting " + webRoot
						+ " deferred to exit");
			}
		}
		return true;
	}

	/**
	 * Deletes a directory recursively, falling back to deleteOnExit().
	 * 
	 * Thanks to Windows' incredibly sophisticated and intelligent file
	 * locking, we cannot delete files that are in use, even if they are
	 * "in use" by, say, a ClassLoader that is about to be garbage
	 * collected.
	 * 
	 * For single files, Java's API has the File#deleteOnExit method, but
	 * it does not perform what you'd think it should do on directories.
	 * 
	 * To be able to clean up directories reliably, we introduce this
	 * function which tries to delete all files and directories directly,
	 * falling back to deleteOnExit.
	 * 
	 * @param directory the directory to delete recursively
	 * @return whether the directory was deleted successfully
	 */
	public static boolean deleteRecursivelyAtLeastOnExit(final File directory) {
		boolean result = true;
		final File[] list = directory.listFiles();
		if (list != null) {
			for (final File file : list) {
				if (file.isDirectory()) {
					if (!deleteRecursivelyAtLeastOnExit(file)) {
						result = false;
					}
					continue;
				}
				if (!file.delete()) {
					file.deleteOnExit();
					result = false;
				}
			}
		}
		if (!result || !directory.delete()) {
			directory.deleteOnExit();
			result = false;
		}
		return result;
	}

	protected static FilesCollection readDb(FilesCollection files) throws ParserConfigurationException,
			SAXException {
		return readDb(files.prefix(""));
	}

	protected static FilesCollection readDb(final File ijRoot) throws ParserConfigurationException, SAXException {
		final FilesCollection files = new FilesCollection(ijRoot);

		// We're too fast, cannot trust the cached checksums
		files.prefix(".checksums").delete();
		files.downloadIndexAndChecksum(progress);
		return files;
	}

	public static void useWebRoot(final FilesCollection files, final File webRoot) throws MalformedURLException {
		final UpdateSite updateSite = files.getUpdateSite(FilesCollection.DEFAULT_UPDATE_SITE, false);
		assertNotNull(updateSite);

		updateSite.setURL(webRoot.toURI().toURL().toString());
		updateSite.setHost("file:localhost");
		updateSite.setUploadDirectory(webRoot.getAbsolutePath());
	}

	public static File getWebRoot(final FilesCollection files) {
		return getWebRoot(files, DEFAULT_UPDATE_SITE);
	}

	public static File getWebRoot(final FilesCollection files, final String updateSite) {
		final UpdateSite site = files.getUpdateSite(updateSite, false);
		assertNotNull(site);
		if (!DEFAULT_UPDATE_SITE.equals(updateSite)
				&& (site.getHost() == null || site.getHost().startsWith("file:"))) {
			return null;
		}
		assertTrue("file:localhost".equals(site.getHost()));
		return new File(site.getUploadDirectory());
	}

	protected static void update(final FilesCollection files) throws IOException {
		final File ijRoot = files.prefix(".");
		final Installer installer = new Installer(files, progress);
		installer.start();
		assertTrue(new File(ijRoot, "update").isDirectory());
		installer.moveUpdatedIntoPlace();
		assertFalse(new File(ijRoot, "update").exists());
	}

	protected static void upload(final FilesCollection files) throws Exception {
		upload(files, DEFAULT_UPDATE_SITE);
	}

	protected static void upload(final FilesCollection files, final String updateSite) throws Exception {
		for (final FileObject file : files.toUpload())
			assertEquals(updateSite, file.updateSite);
		final FilesUploader uploader =
			new FilesUploader(null, files, updateSite, progress);
		assertTrue(uploader.login());
		uploader.upload(progress);
		files.write();
	}

	protected static FileObject[] makeList(final FilesCollection files) {
		final List<FileObject> list = new ArrayList<>();
		for (final FileObject object : files)
			list.add(object);
		return list.toArray(new FileObject[list.size()]);
	}

	protected static void assertStatus(final Status status,
		final FilesCollection files, final String filename)
	{
		final FileObject file = files.get(filename);
		assertStatus(status, file);
	}

	protected static void
		assertStatus(final Status status, final FileObject file)
	{
		assertNotNull("Object " + file.getFilename(), file);
		assertEquals("Status of " + file.getFilename(), status, file.getStatus());
	}

	protected static void assertAction(final Action action,
		final FilesCollection files, final String filename)
	{
		assertAction(action, files.get(filename));
	}

	protected static void assertAction(final Action action,
		final FileObject file)
	{
		assertNotNull("Object " + file, file);
		assertEquals("Action of " + file.filename, action, file.getAction());
	}

	protected static void assertNotEqual(final Object object1,
		final Object object2)
	{
		if (object1 == null) {
			assertNotNull(object2);
		}
		else {
			assertFalse(object1.equals(object2));
		}
	}

	protected static void assertNotEqual(final long long1, final long long2) {
		assertTrue(long1 != long2);
	}

	protected static void
		assertCount(final int count, final Iterable<?> iterable)
	{
		assertEquals(count, count(iterable));
	}

	protected static int count(final Iterable<?> iterable) {
		int count = 0;
		for (@SuppressWarnings("unused")
		final Object object : iterable)
		{
			count++;
		}
		return count;
	}

	protected static void print(final Iterable<?> iterable) {
		System.err.println("{");
		int count = 0;
		for (final Object object : iterable) {
			System.err.println("\t" +
				++count +
				": " +
				object +
				(object instanceof FileObject ? " = " +
					((FileObject) object).getStatus() + "/" +
					((FileObject) object).getAction() : ""));
		}
		System.err.println("}");
	}

	/**
	 * Change the mtime of a file
	 * 
	 * @param file the file to touch
	 * @param timestamp the mtime as pseudo-long (YYYYMMDDhhmmss)
	 */
	protected static void touch(final File file, final long timestamp) {
		final long millis = UpdaterUtil.timestamp2millis(timestamp);
		file.setLastModified(millis);
	}

	/**
	 * Write a .jar file
	 * 
	 * @param files the files collection
	 * @param path the path of the .jar file
	 * @return the File object for the .jar file
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected static File writeJar(final FilesCollection files, final String path) throws FileNotFoundException, IOException
	{
		return writeJar(files, path, path, path);
	}

	/**
	 * Write a .jar file
	 * 
	 * @param files the files collection
	 * @param path the path of the .jar file
	 * @param args a list of entry name / contents pairs
	 * @return the File object for the .jar file
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected static File writeJar(final FilesCollection files, final String path,
		final String... args) throws FileNotFoundException, IOException
	{
		return writeJar(files.prefix(path), args);
	}

	/**
	 * Write a .jar file
	 * 
	 * @param jarFile which .jar file to write into
	 * @param args a list of entry name / contents pairs
	 * @return the File object for the .jar file
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected static File writeJar(final File jarFile,
		final String... args) throws FileNotFoundException, IOException
	{
		assertTrue((args.length % 2) == 0);
		jarFile.getParentFile().mkdirs();
		final JarOutputStream jar = new JarOutputStream(new FileOutputStream(jarFile));
		for (int i = 0; i + 1 < args.length; i += 2) {
			final JarEntry entry = new JarEntry(args[i]);
			jar.putNextEntry(entry);
			jar.write(args[i + 1].getBytes());
			jar.closeEntry();
		}
		jar.close();
		return jarFile;
	}

	/**
	 * Write a .jar file
	 * 
	 * @param files the files collection
	 * @param path the path of the .jar file
	 * @param classes a list of classes whose files to write
	 * @return the File object for the .jar file
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected static File writeJar(final FilesCollection files,
			final String path, final Class<?>... classes)
			throws FileNotFoundException, IOException {
		return writeJar(files.prefix(path), classes);
	}

	/**
	 * Write a .jar file
	 * 
	 * @param file which directory to write into
	 * @param classes a list of classes whose files to write
	 * @return the File object for the .jar file
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected static File writeJar(final File file, final Class<?>... classes) throws FileNotFoundException, IOException {
		file.getParentFile().mkdirs();

		final Map<String, URL> extra = new LinkedHashMap<>();

		final byte[] buffer = new byte[32768];
		final JarOutputStream jar = new JarOutputStream(new FileOutputStream(file));
		for (int i = 0; i < classes.length; i++) {
			final String path = classes[i].getName().replace('.', '/') + ".class";
			final JarEntry entry = new JarEntry(path);
			jar.putNextEntry(entry);
			final InputStream in = classes[i].getResourceAsStream("/" + path);
			for (;;) {
				int count = in.read(buffer);
				if (count < 0)
					break;
				jar.write(buffer, 0, count);
			}
			in.close();
			jar.closeEntry();

			final String url = classes[i].getResource("/" + path).toString();
			final String baseURL = url.substring(0, url.length() - path.length());
			final URL metaInf = new URL(baseURL + "META-INF/");
			for (final URL url2 : FileUtils.listContents(metaInf)) {
				final String path2 = url2.toString().substring(baseURL.length());
				if (!extra.containsKey(path2)) extra.put(path2, url2);
				else {
					final URL url3 = extra.get(path2);
					if (!url2.equals(url3)) {
						System.err.println("Warning: skipping duplicate " + path2 + "\n(" + url2 + " vs " + url3);
					}
				}
			}
		}

		for (Entry<String, URL> entry2 : extra.entrySet()) {
			final String path = entry2.getKey();
			final JarEntry entry = new JarEntry(path);
			jar.putNextEntry(entry);
			final InputStream in = entry2.getValue().openStream();
			for (;;) {
				int count = in.read(buffer);
				if (count < 0)
					break;
				jar.write(buffer, 0, count);
			}
			in.close();
			jar.closeEntry();
		}

		jar.close();
		return file;
	}

	/**
	 * Write a .gz file
	 * 
	 * @param dir The directory into which to write
	 * @param name The file name
	 * @param content The contents to write
	 * @return the File object for the file that was written to
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	protected static File writeGZippedFile(final File dir, final String name,
		final String content) throws FileNotFoundException, IOException
	{
		final File file = new File(dir, name);
		file.getParentFile().mkdirs();
		writeStream(new GZIPOutputStream(new FileOutputStream(file)), content, true);
		return file;
	}

	/**
	 * Write a text file
	 * 
	 * @param files The files collection
	 * @param path the path of the file into which to write
	 * @param content The contents to write
	 * @return the File object for the file that was written to
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	public static File writeFile(final FilesCollection files, final String path, final String content)
		throws FileNotFoundException, IOException
	{
		return writeFile(files.prefix(path), content);
	}

	/**
	 * Write a text file
	 * 
	 * @param files The files collection
	 * @param path the path of the file into which to write
	 * @return the File object for the file that was written to
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	public static File writeFile(final FilesCollection files, final String path)
		throws FileNotFoundException, IOException
	{
		return writeFile(files.prefix(path), path);
	}

	/**
	 * Write a text file
	 * 
	 * @param file The file into which to write
	 * @param content The contents to write
	 * @return the File object for the file that was written to
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	public static File writeFile(final File file, final String content)
		throws FileNotFoundException, IOException
	{
		final File dir = file.getParentFile();
		if (!dir.isDirectory()) dir.mkdirs();
		final String name = file.getName();
		if (name.endsWith(".jar")) return writeJar(file, content, content);

		writeStream(new FileOutputStream(file), content, true);
		return file;
	}

	/**
	 * Writes a string to a stream.
	 * 
	 * @param out where to write to
	 * @param content what to write
	 * @param close whether to close the stream
	 */
	protected static void writeStream(final OutputStream out, final String content,
		final boolean close)
	{
		final PrintWriter writer = new PrintWriter(out);
		writer.println(content);
		if (close) {
			writer.close();
		}
	}

	/**
	 * Reads a file's contents.
	 * 
	 * @param file the file
	 * @return the contents, as a String
	 * @throws IOException
	 */
	protected static String readFile(final File file) throws IOException {
		return readStream(new FileInputStream(file));
	}

	/**
	 * Read a gzip'ed stream and return what we got as a String
	 * 
	 * @param in the input stream as compressed by gzip
	 * @return the contents, as a Stringcl
	 * @throws IOException
	 */
	protected static String readGzippedStream(final InputStream in) throws IOException {
		return readStream(new GZIPInputStream(in));
	}

	/**
	 * Read a stream and return what we got as a String
	 * 
	 * @param in the input stream
	 * @return the contents, as a String
	 * @throws IOException
	 */
	protected static String readStream(final InputStream in) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buffer = new byte[16384];
		for (;;) {
			int count = in.read(buffer);
			if (count < 0) break;
			out.write(buffer, 0, count);
		}
		in.close();
		out.close();
		return out.toString();
	}

	/**
	 * A quieter version of the progress than {@link StderrProgress}.
	 */
	public final static Progress progress = new Progress() {
		final boolean verbose = false;
		final PrintStream err = System.err;
		private String prefix = "", item = "";

		@Override
		public void setTitle(String title) {
			prefix = title;
			item = "";
		}

		@Override
		public void setCount(int count, int total) {
			if (verbose) err.print(prefix + item + count + "/" + total + "\r");
		}

		@Override
		public void addItem(Object item) {
			this.item = "/" + item + ": ";
		}

		@Override
		public void setItemCount(int count, int total) {
			if (verbose) err.print(prefix + item + " [" + count + "/" + total + "]\r");
		}

		@Override
		public void itemDone(Object item) {
			if (verbose) err.print(prefix + item + "\n");
		}

		@Override
		public void done() {
			// this space intentionally left blank
		}

	};

	/**
	 * Writes a {@code .jar} file containing a single file with a specific timestamp.
	 * 
	 * @param files the collection
	 * @param jarFileName the path of the {@code .jar} file
	 * @param year the year of the timestamp
	 * @param month the month of the timestamp
	 * @param day the day of the month of the timestamp
	 * @param fileName the name of the file contained in the {@code .jar} file
	 * @param fileContents the contents of the file contained in the {@code .jar} file
	 * @return a reference to the written {@code .jar} file
	 * @throws IOException 
	 */
	public static File writeJarWithDatedFile(final FilesCollection files,
			final String jarFileName, final int year, final int month,
			final int day, final String fileName,
			final String fileContents) throws IOException {
		final File file = files.prefix(jarFileName);
		final File dir = file.getParentFile();
		if (dir != null && !dir.isDirectory()) assertTrue(dir.mkdirs());
		final JarOutputStream out = new JarOutputStream(new FileOutputStream(file));
		final JarEntry entry = new JarEntry(fileName);
		entry.setTime(new GregorianCalendar(year, month, day).getTimeInMillis());
		out.putNextEntry(entry);
		out.write(fileContents.getBytes());
		out.closeEntry();
		out.close();
		return file;
	}
}
