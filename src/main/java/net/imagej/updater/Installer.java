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

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.imagej.updater.Conflicts.Conflict;
import net.imagej.updater.FileObject.Action;
import net.imagej.updater.FileObject.Status;
import net.imagej.updater.util.Downloadable;
import net.imagej.updater.util.Downloader;
import net.imagej.updater.util.Progress;
import net.imagej.updater.util.UpdaterUserInterface;
import net.imagej.updater.util.UpdaterUtil;

import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;

/**
 * A class that updates local files from all available update sites.
 * <p>
 * An installer wraps a {@link FilesCollection} ({@code db.xml.gz}) and can be
 * used to stage potential changes to that collection.
 * </p>
 * <p>
 * The {@link #start()} method is the key to actually doing the work, downloading
 * new files as needed or creating empty files to signify deletion. These all go
 * in the {@code update} sub-directory, but the update can be finalized with
 * {@link #moveUpdatedIntoPlace()}.
 * </p>
 * <p>
 * NB: the installer will only operate on {@link FileObject}s in the collection
 * that have an appropriate {@link Status}. Thus the first step in updating is
 * typically to use {@link FileObject#stageForUpdate(FilesCollection, boolean)}
 * or {@link FileObject#stageForUninstall(FilesCollection)} as needed, with the
 * {@code Installer} used to activate these staged changes.
 * </p>
 * @author Johannes Schindelin
 */
public class Installer extends Downloader {

	private FilesCollection files;

	public Installer(final FilesCollection files, final Progress progress) {
		super(progress, files.util);
		this.files = files;
		if (progress != null)
			addProgress(progress);
		addProgress(new VerifyFiles());
	}

	class Download implements Downloadable {

		protected FileObject file;
		protected String url;
		protected File destination;

		Download(final FileObject file, final String url, final File destination) {
			this.file = file;
			this.url = url;
			this.destination = destination;
		}

		@Override
		public String toString() {
			return file.getFilename();
		}

		@Override
		public File getDestination() {
			return destination;
		}

		@Override
		public String getURL() {
			return url;
		}

		@Override
		public long getFilesize() {
			return file.filesize;
		}
	}

	public synchronized void start() throws IOException {
		final Iterable<Conflict> conflicts = new Conflicts(files).getConflicts(false);
		if (Conflicts.needsFeedback(conflicts)) {
			final StringBuilder builder = new StringBuilder();
			builder.append("Unresolved conflicts:\n");
			for (Conflict conflict : conflicts) {
				builder.append(conflict.getFilename()).append(": ").append(conflict.getConflict());
			}
			throw new RuntimeException(builder.toString());
		}

		// mark for removal
		final FilesCollection uninstalled = files.clone(files.toUninstall());
		for (final FileObject file : uninstalled)
			try {
				file.stageForUninstall(files);
			}
			catch (final IOException e) {
				files.log.error(e);
				throw new RuntimeException("Could not mark '" + file + "' for removal");
			}

		final List<Downloadable> list = new ArrayList<>();

		// Special care must be taken when updating files in the Mac bundle (Fiji.app)
		// These files are all signed together as one unit, so individual files can not be modified.
		// First we scan the update list to see if there's anything in Fiji.app to update
		boolean updateMacApp = false;
		for (final FileObject file : files.toInstallOrUpdate()) {
			if (file.filename.contains("Fiji.app")) {
				updateMacApp = true;
				break;
			}
		}
		// If there is a Mac app update, we need to flag ALL installed files in Fiji.app for update
		if (updateMacApp) {
			Path macBundlePath = files.prefix("Fiji.app").toPath();
			File backupAppFile = files.prefix("Fiji.old.app");
			Path backupAppPath = backupAppFile.toPath();

			// If there's no Fiji.app folder to back up we can just skip this step
			if (Files.exists(macBundlePath)) {
				for (FileObject installed : files.installed()) {
					if (installed.filename.contains("Fiji.app")) {
						installed.stageForUpdate(files, true);
					}
				}
				// Remove the existing Fiji.old.app if it exists
				if (backupAppFile.exists()) {
					deleteDirectory(backupAppPath);
				}
				// Create a new Fiji.old.app backup
				copyDirectory(macBundlePath, backupAppPath);
			}
		}

		for (final FileObject file : files.toInstallOrUpdate()) {
			final String name = file.filename;
			File saveTo = files.prefixUpdate(name);
			if (file.localFilename != null && !file.localFilename.equals(file.filename)) {
				// if the name changed, remove the file with the old name
				FileObject.touch(files.prefixUpdate(file.localFilename));
			}
			// Files necessary for launch are handled differently - we do not want to
			// put them in the update subdir to migrate over as they must be in place
			// for the launch itself. So we rename the old file to a backup (.old) and
			// set up the download of the new file directly to replace it.
			//
			// However - in the case of Fiji.app files, we already handled the backup process atomically above,
			// so all we need to do is update the saveTo to download directly and not to the update directory
			if (name.contains("Fiji.app")) {
				saveTo = files.prefix(name);
			} else if (file.executable ||
					saveTo.getAbsolutePath().contains("config" + File.separator + "jaunch")) {
				saveTo = files.prefix(name);
				String oldName = saveTo.getAbsolutePath() + ".old";
				if (oldName.endsWith(".exe.old")) oldName =
					oldName.substring(0, oldName.length() - 8) + ".old.exe";
				final File old = new File(oldName);
				if (old.exists()) old.delete();
				saveTo.renameTo(old);
				if (name.equals(UpdaterUtil.macPrefix + "ImageJ-tiger")) try {
					UpdaterUtil.patchInfoPList(files.prefix("Contents/Info.plist"), "ImageJ-tiger");
				}
				catch (final IOException e) {
					UpdaterUserInterface.get().error("Could not patch Info.plist");
				}
			}

			final String url = files.getURL(file);
			final Download download = new Download(file, url, saveTo);
			list.add(download);
		}

		start(list);

		for (final FileObject file : uninstalled)
			if (file.isLocalOnly()) files.remove(file);
			else file.setStatus(file.isObsolete() ? Status.OBSOLETE_UNINSTALLED
				: Status.NOT_INSTALLED);
	}

	protected final static String UPDATER_JAR_NAME = "jars/imagej-updater.jar";

	public static Set<FileObject> getUpdaterFiles(final FilesCollection files, final CommandService commandService, final boolean onlyUpdateable) {
		final Set<FileObject> result = new HashSet<>();
		final FileObject updater = files.get(UPDATER_JAR_NAME);
		if (updater == null) return result;
		final Set<FileObject> topLevel = new HashSet<>();
		topLevel.add(updater);
		if (commandService == null) {
			final String hardcoded = "jars/imagej-ui-swing.jar";
			final FileObject file = files.get(hardcoded);
			if (file != null) topLevel.add(file);
		} else {
			for (final CommandInfo info : commandService
				.getCommandsOfType(UpdaterUI.class))
			{
				final FileObject file = getFileObject(files, info.getClassName());
				if (file != null) {
					topLevel.add(file);
				}
			}
		}
		for (final FileObject file : topLevel) {
			for (final FileObject file2 : file.getFileDependencies(files, true)) {
				if (!onlyUpdateable) {
					result.add(file2);
				} else switch (file2.getStatus()) { // CTR START HERE
				case NEW: case NOT_INSTALLED: case UPDATEABLE:
					result.add(file2);
					break;
				default:
				}
			}
		}
		return result;
	}

	/**
	 * Gets the file object for the .jar file containing the given class.
	 * <p>
	 * To deal with version skew properly, we avoid using SciJava common's
	 * {@link org.scijava.util.FileUtils} here.
	 * </p>
	 * 
	 * @param files
	 *            the database of available files
	 * @param className
	 *            the name of the class we seek the .jar file for
	 * @return the file object, or null if the class could not be found in any
	 *         file of the collection
	 */
	private static FileObject getFileObject(final FilesCollection files, final String className) {
		try {
			final String path = "/" + className.replace('.', '/') + ".class";
			String jar = Installer.class.getClassLoader().loadClass(className).getResource(path).toString();
			if (!jar.endsWith(".jar!" + path)) return null;
			jar = jar.substring(0, jar.length() - path.length() - 1);
			String prefix = "jar:file:" + System.getProperty("ij.dir");
			if (!prefix.endsWith("/")) prefix += "/";
			if (!jar.startsWith(prefix)) return null;
			jar = jar.substring(prefix.length());
			return files.get(jar);
		} catch (ClassNotFoundException e) { /* ignore */ }
		return null;
	}

	public static boolean isTheUpdaterUpdateable(final FilesCollection files) {
		return isTheUpdaterUpdateable(files, null);
	}

	public static boolean isTheUpdaterUpdateable(final FilesCollection files, final CommandService commandService) {
		return getUpdaterFiles(files, commandService, true).size() > 0;
	}

	public static void updateTheUpdater(final FilesCollection files, final Progress progress) throws IOException {
		updateTheUpdater(files, progress, null);
	}

	public static void updateTheUpdater(final FilesCollection files, final Progress progress, final CommandService commandService) throws IOException {
		final Set<FileObject> all = getUpdaterFiles(files, commandService, true);
		int counter = 0;
		for (final FileObject file : all) {
			if (file.setFirstValidAction(files, Action.UPDATE, Action.INSTALL))
				counter++;
		}
		if (counter == 0) return; // nothing to be done
		final FilesCollection.Filter filter = new FilesCollection.Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return all.contains(file);
			}
		};
		final FilesCollection justTheUpdater = files.clone(files.filter(filter));
		final Installer installer = new Installer(justTheUpdater, progress);
		try {
			installer.start();
		}
		finally {
			// TODO: remove "update/" directory
			installer.done();
		}
	}

	class VerifyFiles implements Progress {

		@Override
		public void itemDone(final Object item) {
			verify((Download) item);
		}

		@Override
		public void setTitle(final String title) {}

		@Override
		public void setCount(final int count, final int total) {}

		@Override
		public void addItem(final Object item) {}

		@Override
		public void setItemCount(final int count, final int total) {}

		@Override
		public void done() {}
	}

	public void verify(final Download download) {
		final File destination = download.getDestination();
		final long size = download.getFilesize();
		final long actualSize = destination.length();
		if (size >= 0 && size != actualSize) throw new RuntimeException(
			"Incorrect file size for " + destination + ": " + actualSize +
				" (expected " + size + ")");

		final FileObject file = download.file;
		final String digest = download.file.getChecksum();
		String actualDigest;
		try {
			actualDigest = UpdaterUtil.getDigest(file.getFilename(), destination);
			if (!digest.equals(actualDigest)) {
				List<String> obsoletes = UpdaterUtil.getObsoleteDigests(file.getFilename(), destination);
				if (obsoletes != null) {
					for (final String obsolete : obsoletes) {
						if (digest.equals(obsolete)) actualDigest = obsolete;
					}
				}
			}
		}
		catch (final Exception e) {
			files.log.error(e);
			throw new RuntimeException("Could not verify checksum " + "for " +
				destination);
		}

		if (!digest.equals(actualDigest)) throw new RuntimeException(
			"Incorrect checksum " + "for " + destination + ":\n" + actualDigest +
				"\n(expected " + digest + ")");

		file.setLocalVersion(file.getFilename(), digest, file.getTimestamp());
		file.setStatus(FileObject.Status.INSTALLED);

		if (file.executable && !files.util.platform.startsWith("win")) try {
			Runtime.getRuntime()
				.exec(
					new String[] { "chmod", "0755",
						download.destination.getAbsolutePath() });
		}
		catch (final Exception e) {
			files.log.error(e);
			throw new RuntimeException("Could not mark " + destination +
				" as executable");
		}
	}

	public void moveUpdatedIntoPlace() throws IOException {
		moveUpdatedIntoPlace(files.prefix("update"), files.prefix("."));
	}

	protected void moveUpdatedIntoPlace(final File sourceDirectory,
		final File targetDirectory) throws IOException
	{
		if (!sourceDirectory.isDirectory()) return;
		final File[] list = sourceDirectory.listFiles();
		if (list == null) return;
		if (!targetDirectory.isDirectory() && !targetDirectory.mkdir()) throw new IOException(
			"Could not create directory '" + targetDirectory + "'");
		for (final File file : list) {
			final File targetFile = new File(targetDirectory, file.getName());
			if (file.isDirectory()) {
				moveUpdatedIntoPlace(file, targetFile);
			}
			else if (file.isFile()) {
				if (file.length() == 0) {
					if (targetFile.exists()) deleteOrThrowException(targetFile);
					deleteOrThrowException(file);
				}
				else {
					if (!file.renameTo(targetFile) &&
						!((targetFile.delete() || moveOutOfTheWay(targetFile)) && file
							.renameTo(targetFile)))
					{
						throw new IOException("Could not move '" + file + "' to '" +
							targetFile + "'");
					}
				}
			}
		}
		deleteOrThrowException(sourceDirectory);
	}

	protected static void deleteOrThrowException(final File file)
		throws IOException
	{
		if (!file.delete()) throw new IOException("Could not remove '" + file + "'");
	}

	protected static boolean moveOutOfTheWay(final File file) {
		if (!file.exists()) return true;
		String prefix = file.getName(), suffix = "";
		if (prefix.endsWith(".exe") || prefix.endsWith(".EXE") ||
				prefix.endsWith(".dll") || prefix.endsWith(".DLL")) {
			suffix = prefix.substring(prefix.length() - 4);
			prefix = prefix.substring(0, prefix.length() - 4);
		}
		File backup = new File(file.getParentFile(), prefix + ".old" + suffix);
		if (backup.exists() && !backup.delete()) {
			final int i = 2;
			for (;;) {
				backup = new File(file.getParentFile(), prefix + ".old" + i + suffix);
				if (!backup.exists()) break;
			}
		}
		return file.renameTo(backup);
	}

	private static void deleteDirectory(Path directory) throws IOException {
		Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file); // Delete each file
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.delete(dir); // Delete directory after contents
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
		Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Path targetPath = targetDir.resolve(sourceDir.relativize(dir));
				if (!Files.exists(targetPath)) {
					Files.createDirectories(targetPath);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path targetPath = targetDir.resolve(sourceDir.relativize(file));
				Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
