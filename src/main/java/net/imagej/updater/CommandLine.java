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

import static net.imagej.updater.util.UpdaterUtil.prettyPrintTimestamp;

import java.awt.Frame;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.imagej.updater.Conflicts.Conflict;
import net.imagej.updater.Conflicts.Resolution;
import net.imagej.updater.Diff.Mode;
import net.imagej.updater.FileObject.Action;
import net.imagej.updater.FileObject.Status;
import net.imagej.updater.FileObject.Version;
import net.imagej.updater.FilesCollection.Filter;
import net.imagej.updater.util.*;

import org.scijava.launcher.Java;
import org.scijava.log.LogService;
import org.scijava.log.Logger;
import org.scijava.util.AppUtils;
import org.scijava.util.FileUtils;
import org.scijava.util.IteratorPlus;
import org.scijava.util.POM;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;

/**
 * This is the command-line interface into the ImageJ Updater.
 * Note that many actions, such as {@code add-update-site} or
 * {@code deactivate-update-site}, will stage changes without applying them.
 * If there is an {@code update} subdir, this will have any prospective changes
 * that can be applied automatically on the next ImageJ launch. Some commands
 * (such as {@code add-update-site}), do not create the staging directory
 * though, e.g. the {@code db.xml.gz} is updated but no files are staged, in
 * which case the {@code update} directive can be used to apply the changes.
 *
 * @author Johannes Schindelin
 */
public class CommandLine {

	protected static LogService log = UpdaterUtil.getLogService();
	protected FilesCollection files;
	protected Progress progress;
	private FilesCollection.DependencyMap dependencyMap;
	private boolean checksummed = false;

	/**
	 * Determines whether die() should exit or throw a RuntimeException.
	 */
	private boolean standalone;

	@Deprecated
	public CommandLine() {
		this(AppUtils.getBaseDirectory("ij.dir", CommandLine.class, "updater"), 80);
	}

	public CommandLine(final File ijDir, final int columnCount) {
		this(ijDir, columnCount, null);
	}

	public CommandLine(final File ijDir, final int columnCount,
			final Progress progress) {
		this.progress = progress == null ? new StderrProgress(columnCount)
				: progress;
		files = new FilesCollection(log, ijDir);
	}

	private void ensureChecksummed() {
		if (checksummed) {
			return;
		}
		String warnings;
		try {
			warnings = files.downloadIndexAndChecksum(progress);
		} catch (final Exception e) {
			log.debug(e);
			throw die("Received exception: " + e.getMessage());
		}
		if (!warnings.equals("")) {
			log.warn(warnings);
		}
		checksummed = true;
	}

	protected class FileFilter implements Filter {

		protected Set<String> fileNames;

		public FileFilter(final List<String> files) {
			if (files != null && files.size() > 0) {
				fileNames = new HashSet<>();
				for (final String file : files)
					fileNames.add(FileObject.getFilename(file, true));
			}
		}

		@Override
		public boolean matches(final FileObject file) {
			if (!file.isActivePlatform(files)) return false;
			if (fileNames != null
					&& !fileNames.contains(file.getFilename(true))) {
				return false;
			}
			return file.getStatus() != Status.OBSOLETE_UNINSTALLED;
		}
	}

	public void diff(final List<String> list) {
		ensureChecksummed();
		final Diff diff = new Diff(System.out);

		Mode mode = Mode.CLASS_FILE_DIFF;
		while (list.size() > 0 && list.get(0).startsWith("--")) {
			final String option = list.remove(0);
			mode = Mode.valueOf(option.substring(2).replace('-', '_')
					.toUpperCase());
		}

		for (final FileObject file : files.filter(new FileFilter(list)))
			try {
				final String filename = file.getLocalFilename(false);
				final URL remote = new URL(files.getURL(file));
				final URL local = files.prefix(filename).toURI().toURL();
				diff.showDiff(filename, remote, local, mode);
			} catch (final IOException e) {
				log.error(e);
			}
	}

	public void listCurrent(final List<String> list) {
		ensureChecksummed();
		for (final FileObject file : files.filter(new FileFilter(list)))
			System.out.println(file.filename + "-" + file.getTimestamp());
	}

	public void list(final List<String> list, Filter filter) {
		ensureChecksummed();
		if (filter == null) {
			filter = new FileFilter(list);
		} else {
			filter = files.and(new FileFilter(list), filter);
		}
		files.sort();
		for (final FileObject file : files.filter(filter)) {
			System.out.println(file.filename + "\t(" + file.getStatus() + ")\t"
					+ file.getTimestamp());
		}
	}

	public void list(final List<String> list) {
		list(list, null);
	}

	public void listUptodate(final List<String> list) {
		list(list, files.is(Status.INSTALLED));
	}

	public void listNotUptodate(final List<String> list) {
		list(list,
				files.not(files.oneOf(new Status[] { Status.OBSOLETE,
						Status.INSTALLED, Status.LOCAL_ONLY })));
	}

	public void listUpdateable(final List<String> list) {
		list(list, files.is(Status.UPDATEABLE));
	}

	public void listModified(final List<String> list) {
		list(list, files.is(Status.MODIFIED));
	}

	public void listLocalOnly(final List<String> list) {
		list(list, files.is(Status.LOCAL_ONLY));
	}

	public void listFromSite(final List<String> sites) {
		if (sites.size() != 1)
			throw die("Usage: list-from-site <name>");
		list(null, files.isUpdateSite(sites.get(0)));
	}

	public void listShadowed(final List<String> list) {
		ensureChecksummed();
		final FileFilter filter = new FileFilter(list);
		files.sort();
		final List<String> overridden = new ArrayList<>();
		for (final FileObject file : files.filter(filter)) {
			if (!file.overridesOtherUpdateSite()) continue;
			for (final Map.Entry<String, FileObject> entry : file.overriddenUpdateSites
				.entrySet())
			{
				final FileObject other = entry.getValue();
				if (other != null && other.current != null) {
					overridden.add(entry.getKey());
				}
			}
			if (overridden.isEmpty()) continue;
			System.out.println(file.filename + "\t(" + file.getStatus() + ")\t" +
				file.updateSite + " overrides " + overridden);
			overridden.clear();
		}
	}

	public void show(final List<String> list) {
		for (final String filename : list) {
			show(filename);
		}
	}

	public void show(final String filename) {
		ensureChecksummed();
		final FileObject file = files.get(filename);
		if (file == null) {
			log.error("File not found: " + filename);
		} else {
			show(file);
		}
	}

	public void show(final FileObject file) {
		ensureChecksummed();
		if (dependencyMap == null) {
			dependencyMap = files.getDependencies(files, false);
		}

		System.out.println();
		System.out.println("File: " + file.getFilename(true));
		if (!file.getFilename(true).equals(file.localFilename)) {
			System.out.println("(Local filename: " + file.localFilename + ")");
		}
		String description = file.description;
		if (description != null && description.length() > 0) {
			description = "\t" + (description.replaceAll("\n", "\n\t"));
			System.out.println("Description:\n" + description);
		}
		System.out.println("Update site: " + file.updateSite);
		if (file.current == null) {
			System.out.println("Removed from update site");
		} else {
			System.out.println("URL: " + files.getURL(file));
			System.out.println("checksum: " + file.current.checksum
					+ ", timestamp: " + file.current.timestamp);
		}
		if (file.localChecksum != null
				&& (file.current == null || !file.localChecksum
						.equals(file.current.checksum))) {
			System.out.println("Local checksum: "
					+ file.localChecksum
					+ " ("
					+ (file.hasPreviousVersion(file.localChecksum) ? ""
							: "NOT a ") + "previous version)");
		}
		final StringBuilder builder = new StringBuilder();
		for (final FileObject dependency : file.getFileDependencies(files,
				false)) {
			if (builder.length() > 0)
				builder.append(", ");
			builder.append(dependency.getFilename(true));
		}
		if (builder.length() > 0) {
			System.out.println("Dependencies: " + builder.toString());
		}
		final FilesCollection dependencees = getDependencees(file);
		if (dependencees != null && !dependencees.isEmpty()) {
			builder.setLength(0);
			for (final FileObject dependencee : dependencees) {
				if (builder.length() > 0)
					builder.append(", ");
				builder.append(dependencee.getFilename(true));
			}
			if (builder.length() > 0) {
				System.out.println("Have '" + file.getFilename(true)
						+ "' as dependency: " + builder.toString());
			}
		}

		final File jarFile = files.prefix(file.localFilename != null ? file.localFilename : file.filename);
		if (jarFile.exists()) {
			final Map<String, String> map = new LinkedHashMap<>();
			try {
				final JarFile jar = new JarFile(jarFile);
				final Manifest manifest = jar.getManifest();
				final Attributes attrs = manifest == null ? null : manifest
						.getMainAttributes();
				final String commit = attrs == null ? null : attrs
						.getValue("Implementation-Build");
				if (commit != null)
					map.put("Commit", commit);
				JarEntry pomEntry = null;
				for (final JarEntry entry : new IteratorPlus<>(
						jar.entries())) {
					if (!entry.getName().endsWith("/pom.xml"))
						continue;
					if (pomEntry == null) {
						pomEntry = entry;
					} else {
						System.out.println("Warning: Ignoring extra pom.xml: "
								+ entry.getName() + " in " + jarFile);
					}
				}
				if (pomEntry != null)
					try {
						final POM pom = new POM(jar.getInputStream(pomEntry));
						final String url = pom.getProjectURL();
						if (url != null)
							map.put("Project URL", url);
						final String scm = pom.cdata("//project/scm/url");
						if (scm != null) {
							if (commit != null
									&& scm.matches("https?://github.com/[^/]*/[^/]*/?")) {
								map.put("Commit URL", scm + "/commit/" + commit);
								map.put("Compare URL", scm + "/compare/" + commit + "...master");
							}
							map.put("Project SCM URL", scm);
						}
						final String issues = pom
								.cdata("//project/issueManagement/url");
						if (issues != null)
							map.put("Project Issue Tracker", issues);
						final String ci = pom
								.cdata("//project/ciManagement/url");
						if (ci != null)
							map.put("Jenkins Job URL", ci);
					} catch (Exception e) {
						log.debug(e);
						System.out.println("Error parsing POM: "
								+ e.getMessage());
					}
				jar.close();
			} catch (IOException e) {
				System.out.println("Could not get manifest: " + e.getMessage());
			}
			if (map.isEmpty()) {
				System.out.println("No manifest/POM information contained in "
						+ jarFile.getName());
			} else {
				System.out.println("Manifest/POM information for "
						+ file.getBaseName() + ":");
				for (final Map.Entry<String, String> entry : map.entrySet()) {
					System.out.println("\t" + entry.getKey() + ": "
							+ entry.getValue());
				}
			}
		}
	}

	public void history(final List<String> list) {
		ensureChecksummed();
		class History extends TreeMap<Long, Set<FileObject>> implements
			Comparator<FileObject>
		{

			public void add(final FileObject file) {
				if (file.current != null) {
					add(file.current.timestamp, file);
				}
				for (final Version version : file.previous) {
					add(version.timestamp, file);
				}
			}

			public void add(final long timestamp, final FileObject file) {
				Set<FileObject> set = get(timestamp);
				if (set == null) {
					set = new TreeSet<>(this);
					put(timestamp, set);
				}
				set.add(file);
			}

			@Override
			public int compare(FileObject a, FileObject b) {
				return a.getFilename(true).compareTo(b.getFilename(true));
			}
		}

		final History history = new History();
		for (final FileObject file : files.filter(new FileFilter(list))) {
			if (file.getStatus() == Status.LOCAL_ONLY) continue;
			history.add(file);
		}

		for (final Entry<Long, Set<FileObject>> entry : history.descendingMap()
			.entrySet())
		{
			final long timestamp = entry.getKey();
			final Set<FileObject> files = entry.getValue();

			System.out.println("" + prettyPrintTimestamp(timestamp));
			for (final FileObject file : files) {
				System.out.println("\t" + file.getFilename(timestamp));
			}
		}
	}

	private FilesCollection getDependencees(final FileObject file) {
		if (dependencyMap == null)
			dependencyMap = files.getDependencies(files, false);
		return dependencyMap.get(file);
	}

	class OneFile implements Downloadable {

		FileObject file;

		OneFile(final FileObject file) {
			this.file = file;
		}

		@Override
		public File getDestination() {
			return files.prefix(file.filename);
		}

		@Override
		public String getURL() {
			return files.getURL(file);
		}

		@Override
		public long getFilesize() {
			return file.filesize;
		}

		@Override
		public String toString() {
			return file.filename;
		}
	}

	public void download(final FileObject file) {
		ensureChecksummed();
		try {
			new Downloader(progress).start(new OneFile(file));
			if (file.executable && !Platforms.isWindows(files.platform())) {
				try {
					Runtime.getRuntime().exec(
							new String[] { "chmod", "0755",
									files.prefix(file.filename).getPath() });
				} catch (final Exception e) {
					e.printStackTrace();
					throw die("Could not mark " + file.filename
							+ " as executable");
				}
			}
			log.info("Installed " + file.filename);
		} catch (final IOException e) {
			log.error("IO error downloading " + file.filename, e);
		}
	}

	public void delete(final FileObject file) {
		if (new File(file.filename).delete()) {
			log.info("Deleted " + file.filename);
		} else {
			log.error("Failed to delete " + file.filename);
		}
	}

	public void update(final List<String> list) {
		update(list, false);
	}

	public void update(final List<String> list, final boolean force) {
		update(list, force, false);
	}

	public void update(final List<String> list, final boolean force,
			final boolean pristine) {
		ensureChecksummed();
		if (list.size() > 0) {
			for (final FileObject file : files) {
				if (file.getStatus() == Status.NEW
						&& file.getAction() == Action.INSTALL) {
					file.setAction(files, Action.NEW);
				}
			}
		}
		try {
			for (final FileObject file : files.filter(new FileFilter(list))) {
				if (file.getStatus() == Status.LOCAL_ONLY) {
					if (pristine)
						file.setAction(files, Action.UNINSTALL);
				} else if (file.isObsolete()) {
					if (file.getStatus() == Status.OBSOLETE) {
						log.info("Removing " + file.filename);
						file.stageForUninstall(files);
					} else if (file.getStatus() == Status.OBSOLETE_MODIFIED) {
						if (force || pristine) {
							file.stageForUninstall(files);
							log.info("Removing " + file.filename);
						} else {
							log.warn("Skipping obsolete, but modified "
									+ file.filename);
						}
					}
				} else if (file.getStatus() != Status.INSTALLED
						&& !file.stageForUpdate(files, force)) {
					log.warn("Skipping " + file.filename);
				}
				// remove obsolete versions in pristine mode
				if (pristine) {
					final File correctVersion = files.prefix(file);
					final File[] versions = FileUtils.getAllVersions(
							correctVersion.getParentFile(),
							correctVersion.getName());
					if (versions != null) {
						for (final File version : versions) {
							if (!version.equals(correctVersion)) {
								log.info("Deleting obsolete version " + version);
								if (!version.delete()) {
									log.error("Could not delete " + version
											+ "!");
								}
							}
						}
					}
				}
			}
			resolveConflicts(false);
			final Installer installer = new Installer(files, progress);
			installer.start();
			installer.moveUpdatedIntoPlace();
			files.write();
		} catch (final Exception e) {
			if (e.getMessage().indexOf("conflicts") >= 0) {
				log.error("Could not update due to conflicts:");
				for (final Conflict conflict : new Conflicts(files).getConflicts(false))
				{
					log.error(conflict.getFilename() + ": " + conflict.getConflict());
				}
			} else {
				log.error("Error updating", e);
			}
		}
	}

	public void revertUnrealChanges(final List<String> list) {
		ensureChecksummed();
		boolean simulate = false;
		if (list != null && list.size() > 0 && "--simulate".equals(list.get(0))) {
			simulate = true;
			list.remove(0);
		}
		int count = 0;
		for (final FileObject file : files.filter(new FileFilter(list))) {
			switch (file.getStatus()) {
				case MODIFIED:
				case UPDATEABLE:
					break;
				case INSTALLED:
				case LOCAL_ONLY:
				case NEW:
				case NOT_INSTALLED:
				case OBSOLETE:
				case OBSOLETE_MODIFIED:
				case OBSOLETE_UNINSTALLED:
					// do nothing
					continue;
			}

			if (file.filename.endsWith(".dll")) {
				try {
					final DllFile dll = new DllFile(files.prefix(file));
					try {
						final URL url = new URL(files.getURL(file));
						if (!dll.equals(url.openConnection())) continue;
					}
					finally {
						dll.close();
					}
				}
				catch (Throwable t) {
					// print exception, but ignore otherwise
					log.warn(t);
					continue;
				}
				if (simulate) {
					System.out.println("Would overwrite " + file.filename);
				}
				else {
					file.setAction(files, Action.UPDATE);
				}
				count++;
			}
		}

		if (count == 0) {
			System.err.println("Nothing to do!");
		}
		else if (simulate) {
			System.out.println("Would overwrite " + count + " file(s)");
		}
		else try {
			final Installer installer = new Installer(files, progress);
			installer.start();
			installer.moveUpdatedIntoPlace();
			files.write();
		}
		catch (Throwable t) {
			log.error(t);
		}
	}

	public void downgrade(final List<String> list) {
		if (standalone) {
			final Console console = System.console();
			if (console == null) {
				throw die("Downgrading is only allowed interactively");
			}

			final List<String> answers = Arrays.asList("Yours, and yours alone", "Obama's", "San Andreas'");
			final String correct = answers.get(0);
			Collections.shuffle(answers);
			String answer = console.readLine("Downgrading is not accurate, due to lack of accurate metadata\n" +
					"In particular, when files have been shadowed/unshadowed or marked obsolete,\n" +
					"the result of a downgrade is guaranteed to be inaccurate.\n\n" +
					"If you do not want an inaccurate time, now is the time to quit.\n\n" +
					"Otherwise, please answer the following question accurately:\n" +
					"Whose fault is it if anything goes wrong with this downgrade?\n\n" +
					"\t1) %s\n\t2) %s\n\t3) %s\n", answers.toArray()).trim();
			if (answer.matches("[0-9]+")) try {
				answer = answers.get(Integer.parseInt(answer) - 1);
			}
			catch (final NumberFormatException e) {
				// ignore
			}
			if (!correct.equals(answer)) {
				throw die("Absolutely not.");
			}
		}

		boolean simulate = false;
		if (list.size() > 0 && "--simulate".equals(list.get(0))) {
			simulate = true;
			list.remove(0);
		}

		if (list.size() < 1) {
			throw die("What date?");
		}
		final String timestampString = list.remove(0);
		final long timestamp = Long.parseLong(timestampString);

		ensureChecksummed();

		int count = 0;
		for (final FileObject file : files.filter(new FileFilter(list))) {
			if (file.getStatus() == Status.LOCAL_ONLY) continue;
			if (file.current != null && file.current.timestamp <= timestamp) {
				if (!file.current.checksum.equals(file.localChecksum)) {
					if (simulate) {
						System.out.println("Would update/install " + file.current.filename);
					}
					else {
						file.setStatus(Status.UPDATEABLE);
						file.setFirstValidAction(files, Action.UPDATE, Action.INSTALL);
					}
					count++;
				}
				continue;
			}

			String result = null;
			String checksum = null;
			long matchedTimestamp = 0;
			for (final Version version : file.previous) {
				if (timestamp >= version.timestamp && version.timestamp > matchedTimestamp) {
					result = version.filename;
					checksum = version.checksum;
					matchedTimestamp = version.timestamp;
				}
			}

			if (checksum == null) {
				if (file.localChecksum != null) {
					if (simulate) {
						System.out.println("Would uninstall " + file.getLocalFilename(false));
					}
					else {
						file.setAction(files, Action.UNINSTALL);
					}
					count++;
				}
			}
			else if (!result.equals(file.filename) || !checksum.equals(file.localChecksum)) {
				if (simulate) {
					System.out.println("Would update/install " + result);
				}
				else {
					if (file.current == null) {
						file.current = new Version(checksum, matchedTimestamp);
					}
					else {
						file.current.checksum = checksum;
						file.current.timestamp = matchedTimestamp;
					}
					file.filename = file.current.filename = result;
					file.filesize = -1;
					file.setStatus(Status.UPDATEABLE);
					file.setFirstValidAction(files, Action.UPDATE, Action.INSTALL);
				}
				count++;
			}
		}

		if (count == 0) {
			System.err.println("Nothing to do!");
		}
		else if (!simulate) try {
			resolveConflicts(false);
			final Installer installer = new Installer(files, progress);
			installer.start();
			installer.moveUpdatedIntoPlace();
			files.write();
		} catch (final Exception e) {
			if (e.getMessage().indexOf("conflicts") >= 0) {
				log.error("Could not update due to conflicts:");
				for (final Conflict conflict : new Conflicts(files).getConflicts(false))
				{
					log.error(conflict.getFilename() + ": " + conflict.getConflict());
				}
			} else {
				log.error("Error updating", e);
			}
		}
	}

	public void upload(final List<String> list) {
		if (list == null) {
			throw die("Which files do you mean to upload?");
		}
		boolean forceUpdateSite = false, forceShadow = false, simulate = false, forgetMissingDeps = false;
		String updateSite = null;
		while (list.size() > 0 && list.get(0).startsWith("-")) {
			final String option = list.remove(0);
			if ("--update-site".equals(option) || "--site".equals(option)) {
				if (list.size() < 1) {
					throw die("Missing name for --update-site");
				}
				updateSite = list.remove(0);
				forceUpdateSite = true;
			} else if ("--simulate".equals(option)) {
				simulate = true;
			} else if ("--force-shadow".equals(option)) {
				forceShadow = true;
			} else if ("--forget-missing-dependencies".equals(option)) {
				forgetMissingDeps = true;
			} else {
				throw die("Unknown option: " + option);
			}
		}
		if (list.size() == 0) {
			throw die("Which files do you mean to upload?");
		}
		if (forceShadow && updateSite == null) {
			throw die("Need an explicit update site with --force-shadow");
		}

		ensureChecksummed();
		int count = 0;
		for (final String name : list) {
			final FileObject file = files.get(name);
			if (file == null) {
				throw die("No file '" + name + "' found!");
			}
			if (file.getStatus() == Status.INSTALLED && (file.localFilename == null || file.localFilename.equals(file.filename))) {
				if (forceShadow && !updateSite.equals(file.updateSite)) {
					// TODO: add overridden update site
					file.updateSite = updateSite;
					file.setStatus(Status.MODIFIED);
					log.info("Uploading (force-shadow) '" + name
							+ "' to site '" + updateSite + "'");
				} else {
					log.info("Skipping up-to-date " + name);
					continue;
				}
			}
			handleLauncherForUpload(file);
			if (updateSite == null) {
				updateSite = file.updateSite;
				if (updateSite == null) {
					updateSite = file.updateSite = chooseUploadSite(name);
				}
				if (updateSite == null) {
					throw die("Canceled");
				}
			} else if (file.updateSite == null) {
				log.info("Uploading new file '" + name + "' to  site '"
						+ updateSite + "'");
				file.updateSite = updateSite;
			} else if (!file.updateSite.equals(updateSite)) {
				if (forceUpdateSite) {
					file.updateSite = updateSite;
				} else {
					throw die("Cannot upload to multiple update sites ("
							+ list.get(0) + " to " + updateSite + " and "
							+ name + " to " + file.updateSite + ")");
				}
			}
			if (file.getStatus() == Status.NOT_INSTALLED
					|| file.getStatus() == Status.NEW) {
				log.info("Removing file '" + name + "'");
				file.setAction(files, Action.REMOVE);
			} else {
				if (simulate) {
					log.info("Would upload '" + name + "'");
				}
				if (file.localFilename != null && !file.localFilename.equals(file.filename)) {
					file.addPreviousVersion(file.current.checksum, file.current.timestamp, file.filename, 0);
				}
				file.setAction(files, Action.UPLOAD);
			}
			count++;
		}
		if (count == 0) {
			log.info("Nothing to upload");
			return;
		}

		if (updateSite != null
				&& files.getUpdateSite(updateSite, false) == null) {
			throw die("Unknown update site: '" + updateSite + "'");
		}

		if (forgetMissingDeps) {
			for (final Conflict conflict : new Conflicts(files)
					.getConflicts(true)) {
				final String message = conflict.getConflict();
				if (!message.startsWith("Depends on ")
						|| !message.endsWith(" which is about to be removed.")) {
					continue;
				}
				log.info("Breaking dependency: " + conflict);
				for (final Resolution resolution : conflict.getResolutions()) {
					if (resolution.getDescription().startsWith("Break")) {
						resolution.resolve();
						break;
					}
				}
			}
		}

		if (simulate) {
			final Iterable<Conflict> conflicts = new Conflicts(files)
					.getConflicts(true);
			if (Conflicts.needsFeedback(conflicts)) {
				log.error("Unresolved upload conflicts!\n\n"
						+ UpdaterUtil.join("\n", conflicts));
			} else {
				log.info("Would upload/remove " + count + " to/from "
						+ getLongUpdateSiteName(updateSite));
			}
			final String errors = files.checkConsistency();
			if (errors != null) {
				log.error(errors);
			}
			return;
		}


		final String errors = files.checkConsistency();
		if (errors != null) {
			throw die(errors);
		}

		log.info("Uploading to " + getLongUpdateSiteName(updateSite));
		upload(updateSite);
	}

	public void uploadCompleteSite(final List<String> list) {
		if (list == null) {
			throw die("Which files do you mean to upload?");
		}
		boolean ignoreWarnings = false, forceShadow = false, simulate = false;
		while (!list.isEmpty() && list.get(0).startsWith("-")) {
			final String option = list.remove(0);
			if ("--force".equals(option)) {
				ignoreWarnings = true;
			} else if ("--force-shadow".equals(option)) {
				forceShadow = true;
			} else if ("--simulate".equals(option)) {
				simulate = true;
			} else if ("--platforms".equals(option)) {
				if (list.isEmpty()) {
					throw die("Need a comma-separated list of platforms with --platform");
				}
				files.setActivePlatforms(list.remove(0).split(","));
			} else {
				throw die("Unknown option: " + option);
			}
		}
		if (list.size() != 1) {
			throw die("Which files do you mean to upload?");
		}
		final String updateSite = list.get(0);

		ensureChecksummed();
		if (files.getUpdateSite(updateSite, false) == null) {
			throw die("Unknown update site '" + updateSite + "'");
		}

		int removeCount = 0, uploadCount = 0, warningCount = 0;
		for (final FileObject file : files) {
			if (!file.isActivePlatform(files)) {
				continue;
			}
			final String name = file.filename;
			handleLauncherForUpload(file);
			switch (file.getStatus()) {
			case OBSOLETE:
			case OBSOLETE_MODIFIED:
				if (forceShadow) {
					file.updateSite = updateSite;
					file.setAction(files, Action.UPLOAD);
					if (simulate) {
						log.info("Would upload " + file.filename);
					}
					uploadCount++;
				} else if (ignoreWarnings && updateSite.equals(file.updateSite)) {
					file.setAction(files, Action.UPLOAD);
					if (simulate) {
						log.info("Would re-upload " + file.filename);
					}
					uploadCount++;

				} else {
					log.warn("Obsolete '" + name + "' still installed!");
					warningCount++;
				}
				break;
			case UPDATEABLE:
			case MODIFIED:
				if (!forceShadow && !updateSite.equals(file.updateSite)) {
					log.warn("'" + name + "' of update site '"
							+ file.updateSite + "' is not up-to-date!");
					warningCount++;
					continue;
				}
				//$FALL-THROUGH$
			case LOCAL_ONLY:
				file.updateSite = updateSite;
				file.setAction(files, Action.UPLOAD);
				if (simulate) {
					log.info("Would upload new "
							+ (file.getStatus() == Status.LOCAL_ONLY ? ""
									: "version of ")
							+ file.getLocalFilename(true));
				}
				uploadCount++;
				break;
			case NEW:
			case NOT_INSTALLED:
				// special: keep tools-1.4.2.jar, needed for ImageJ 1.x
				if ("ImageJ".equals(updateSite)
						&& file.getFilename(true).equals("jars/tools.jar")) {
					break;
				}
				if (!file.isActivePlatform(files)) break;
				file.setAction(files, Action.REMOVE);
				if (simulate) {
					log.info("Would mark " + file.filename + " obsolete");
				}
				removeCount++;
				break;
			case INSTALLED:
			case OBSOLETE_UNINSTALLED:
				// leave these alone
				break;
			}
		}

		// remove all obsolete dependencies of the same upload site
		for (final FileObject file : files.forUpdateSite(updateSite)) {
			if (!file.willBeUpToDate()) {
				continue;
			}
			for (final FileObject dependency : file.getFileDependencies(files,
					false)) {
				if (dependency.willNotBeInstalled()
						&& updateSite.equals(dependency.updateSite)) {
					file.removeDependency(dependency.getFilename(false));
				}
			}
			if (ignoreWarnings) {
				final List<String> obsoleteDependencies = new ArrayList<>();
				for (final Dependency dependency : file.getDependencies()) {
					final FileObject dep = files.get(dependency.filename);
					if (dep != null && dep.isObsolete()) {
						obsoleteDependencies.add(dependency.filename);
					}
				}
				for (final String filename : obsoleteDependencies) {
					file.removeDependency(filename);
				}
			}
		}

		if (!ignoreWarnings && warningCount > 0) {
			throw die("Use --force to ignore warnings and upload anyway");
		}

		if (removeCount == 0 && uploadCount == 0) {
			log.info("Nothing to upload");
			return;
		}

		if (simulate) {
			final Iterable<Conflict> conflicts = new Conflicts(files)
					.getConflicts(true);
			if (Conflicts.needsFeedback(conflicts)) {
				log.error("Unresolved upload conflicts!\n\n"
						+ UpdaterUtil.join("\n", conflicts));
			} else {
				log.info("Would upload " + uploadCount + " (removing "
						+ removeCount + ") to "
						+ getLongUpdateSiteName(updateSite));
			}
			final String errors = files.checkConsistency();
			if (errors != null) {
				log.error(errors);
			}
			return;
		}

		log.info("Uploading " + uploadCount + " (removing " + removeCount
				+ ") to " + getLongUpdateSiteName(updateSite));
		upload(updateSite);
	}

	private void handleLauncherForUpload(final FileObject file) {
		if (file.getStatus() == Status.LOCAL_ONLY
				&& Platforms.isLauncher(file.filename)) {
			file.executable = true;
			file.addPlatform(Platforms.platformForLauncher(file.filename));
			for (final String fileName : new String[] { "jars/imagej-launcher.jar" }) {
				final FileObject dependency = files.get(fileName);
				if (dependency != null) {
					file.addDependency(files, dependency);
				}
			}
		}
	}

	private void upload(final String updateSite) {
		resolveConflicts(true);
		FilesUploader uploader = null;
		try {
			uploader = new FilesUploader(null, files, updateSite, progress);
			if (!uploader.login())
				throw die("Login failed!");
			uploader.upload(progress);
			files.write();
		} catch (final Throwable e) {
			final String message = e.getMessage();
			if (message != null && message.indexOf("conflicts") >= 0) {
				log.error("Could not upload due to conflicts:");
				for (final Conflict conflict : new Conflicts(files)
						.getConflicts(true)) {
					log.error(conflict.getFilename() + ": "
							+ conflict.getConflict());
				}
			} else {
				e.printStackTrace();
				throw die("Error during upload: " + e);
			}
			if (uploader != null)
				uploader.logout();
		}
	}

	private void resolveConflicts(final boolean forUpload) {
		final Console console = System.console();
		final Conflicts conflicts = new Conflicts(files);
		for (;;) {
			final Iterable<Conflict> list = conflicts.getConflicts(forUpload);
			if (!Conflicts.needsFeedback(list)) {
				for (final Conflict conflict : list) {
					final String filename = conflict.getFilename();
					log.info((filename != null ? filename + ": " : "")
							+ conflict.getConflict());
				}
				return;
			}
			if (console == null) {
				final StringBuilder builder = new StringBuilder();
				for (final Conflict conflict : list) {
					final String filename = conflict.getFilename();
					builder.append((filename != null ? filename + ": " : "")
							+ conflict.getConflict() + "\n");
				}
				throw die("There are conflicts:\n" + builder);
			}
			for (final Conflict conflict : list) {
				final String filename = conflict.getFilename();
				if (filename != null) {
					console.printf("File '%s':\n", filename);
				}
				console.printf("%s\n", conflict.getConflict());
				if (conflict.getResolutions().length == 0) {
					continue;
				}
				console.printf("\nResolutions:\n");
				final Resolution[] resolutions = conflict.getResolutions();
				for (int i = 0; i < resolutions.length; i++) {
					console.printf("% 3d %s\n", i + 1,
							resolutions[i].getDescription());
				}
				for (;;) {
					final String answer = console.readLine("\nResolution? ");
					if (answer == null || answer.toLowerCase().startsWith("x")) {
						throw die("Aborted");
					}
					try {
						final int index = Integer.parseInt(answer);
						if (index > 0 && index <= resolutions.length) {
							resolutions[index - 1].resolve();
							break;
						}
						console.printf(
								"Invalid choice: %d (must be between 1 and %d)",
								index, resolutions.length);
					} catch (final NumberFormatException e) {
						console.printf("Invalid answer: %s\n", answer);
					}
				}
			}
		}
	}

	public String chooseUploadSite(final String file) {
		final List<String> names = new ArrayList<>();
		final List<String> options = new ArrayList<>();
		for (final String name : files.getUpdateSiteNames(false)) {
			final UpdateSite updateSite = files.getUpdateSite(name, true);
			if (updateSite.getUploadDirectory() == null
					|| updateSite.getUploadDirectory().equals("")) {
				continue;
			}
			names.add(name);
			options.add(getLongUpdateSiteName(name));
		}
		if (names.size() == 0) {
			log.error("No uploadable sites found");
			return null;
		}
		final String message = "Choose upload site for file '" + file + "'";
		final int index = UpdaterUserInterface.get().optionDialog(message,
				message, options.toArray(new String[options.size()]), 0);
		return index < 0 ? null : names.get(index);
	}

	public String getLongUpdateSiteName(final String name) {
		final UpdateSite site = files.getUpdateSite(name, true);
		String host = site.getHost();
		if (host == null || host.equals("")) {
			host = "";
		} else {
			if (host.startsWith("webdav:")) {
				final int colon = host.indexOf(':', 8);
				if (colon > 0) {
					host = host.substring(0, colon) + ":<password>";
				}
			}
			host += ":";
		}
		return name + " (" + host + site.getUploadDirectory() + ")";
	}

	public void listUpdateSites(Collection<String> args) {
		ensureChecksummed();
		if (args == null || args.size() == 0)
			args = files.getUpdateSiteNames(true);
		for (final String name : args) {
			final UpdateSite site = files.getUpdateSite(name, true);
			System.out.print(name + (site.isActive() ? "" : " (DISABLED)")
					+ ": " + site.getURL());
			if (site.getUploadDirectory() == null)
				System.out.println();
			else
				System.out.println(" (upload host: " + site.getHost()
						+ ", upload directory: " + site.getUploadDirectory()
						+ ")");
		}
	}

	public void addOrEditUploadSite(final List<String> args, final boolean add) {
		if (args.size() != 2 && args.size() != 4)
			throw die("Usage: " + (add ? "add" : "edit")
					+ "-update-site <name> <url> [<host> <upload-directory>]");
		addOrEditUploadSite(args.get(0), args.get(1),
				args.size() > 2 ? args.get(2) : null,
				args.size() > 3 ? args.get(3) : null, add);
	}

	public void addOrEditUploadSite(final String name, final String url,
			final String sshHost, final String uploadDirectory,
			final boolean add) {
		ensureChecksummed();
		final UpdateSite site = files.getUpdateSite(name, false);
		if (site == null || add) {
			if (site != null)
				throw die("Site '" + name + "' was already added!");
			files.addUpdateSite(name, url, sshHost, uploadDirectory, 0l);
		} else {
			site.setURL(url);
			site.setHost(sshHost);
			site.setUploadDirectory(uploadDirectory);
			site.setActive(true);
		}
		try {
			// Update the db.xml.gz
			files.write();
		} catch (final Exception e) {
			UpdaterUserInterface.get().handleException(e);
			throw die("Could not write local file database");
		}
	}

	public void addUploadSites(final List<String> names, final List<String> urls) {
		final int size = Math.min(names.size(), urls.size());
		ensureChecksummed();
		for (int i = 0; i < size; i++) {
			final String name = names.get(i);
			final String url = urls.get(i);
			final String sshHost = null;
			final String uploadDirectory = null;
			final UpdateSite site = files.getUpdateSite(name, false);
			if (site != null)
				throw die("Site '" + name + "' was already added!");
			files.addUpdateSite(name, url, sshHost, uploadDirectory, 0l);
		}
		try {
			files.write();
		} catch (final Exception e) {
			UpdaterUserInterface.get().handleException(e);
			throw die("Could not write local file database");
		}
	}

	public void removeUploadSite(final List<String> names) {
		if (names == null || names.size() < 1) {
			throw die("Which update-site do you want to remove, exactly?");
		}
		removeUploadSite(names.toArray(new String[names.size()]));
	}

	public void removeUploadSite(final String... names) {
		ensureChecksummed();
		for (final String name : names) {
			files.removeUpdateSite(name);
		}
		try {
			files.write();
		} catch (final Exception e) {
			UpdaterUserInterface.get().handleException(e);
			throw die("Could not write local file database");
		}
	}

	/**
	 * Turn off an update site without removing it from the list. Requires restart
	 * to apply the update.
	 */
	public void deactivateUpdateSite(final List<String> names) {
		if (names == null || names.size() < 1) {
			throw die("Which update-site do you want to deactivate, exactly?");
		}
		deactivateUpdateSite(names.toArray(new String[names.size()]));
	}

	/**
	 * See {@link #deactivateUpdateSite(List)}
	 */
	public void deactivateUpdateSite(final String... names) {
		ensureChecksummed();

		// Deactivate the indicated update site(s)
		for (final String name : names) {
			UpdateSite site = files.getUpdateSite(name, false);
			if (site != null) {
				files.deactivateUpdateSite(site);
			}
		}

		// Prepare any changes for update
		try {
			// NB: necessary to create the update folder
			new Installer(files, progress).start();
			// NB: don't Installer#moveUpdatedIntoPlace(); as this will fail
			files.write();
		} catch (final Exception e) {
			UpdaterUserInterface.get().handleException(e);
			throw die("Could not write local file database");
		}
	}

	public void refreshUpdateSites(List<String> list) {
		boolean simulate = false, updateall = false;
		while (list.size() > 0 && list.get(0).startsWith("-")) {
			final String option = list.remove(0);
			if ("--simulate".equals(option)) {
				simulate = true;
				continue;
			}
			if ("--updateall".equals(option)) {
				updateall = true;
				continue;
			}
			throw die("Unknown option: " + option);
		}
		try {
			files.tryLoadingCollection();
		} catch (ParserConfigurationException | SAXException e) {
			e.printStackTrace();
		}
		HTTPSUtil.checkHTTPSSupport(log);
		final List< URLChange > urlChanges = AvailableSites.initializeAndAddSites(files, (Logger)log);
		if(updateall) {
			urlChanges.forEach( change -> change.setApproved(true));
		}
		else {
			urlChanges.forEach( change -> change.setApproved(change.isRecommended()));
		}
		urlChanges.forEach(change -> {
			UpdateSite site = change.updateSite();
			if(change.isApproved()) {
				System.out.println("  [UPDATE] " + site.getName() + ": " + site.getURL() + " -> " + change.getNewURL());
			} else {
				System.out.println("  [KEEP] " + site.getName() + ": " + site.getURL() + " (new: " + change.getNewURL() + ")");
			}
		});
		if(!simulate) {
			AvailableSites.applySitesURLUpdates(files, urlChanges);
		}
	}

	@Deprecated
	public static CommandLine getInstance() {
		try {
			return new CommandLine();
		} catch (final Exception e) {
			e.printStackTrace();
			log.error("Could not parse db.xml.gz: " + e.getMessage());
			throw new RuntimeException(e);
		}
	}

	private static List<String> makeList(final String[] list, int start) {
		final List<String> result = new ArrayList<>();
		while (start < list.length)
			result.add(list[start++]);
		return result;
	}

	/**
	 * Print an error message and exit the process with an error.
	 * 
	 * Note: Java has no "noreturn" annotation, but you can always write:
	 * <code>throw die(<message>)</code> to make the Java compiler understand.
	 * 
	 * @param message
	 *            the error message
	 * @return a dummy return value to be able to use "throw die(...)" to shut
	 *         up the compiler
	 */
	private RuntimeException die(final String message) {
		if (standalone) {
			log.error(message);
			System.exit(1);
		}
		return new RuntimeException(message);
	}

	public void usage() {
		final StringBuilder diffOptions = new StringBuilder();
		diffOptions.append("[ ");
		for (final Mode mode : Mode.values()) {
			if (diffOptions.length() > 2) {
				diffOptions.append(" | ");
			}
			diffOptions.append("--"
					+ mode.toString().toLowerCase().replace(' ', '-'));
		}
		diffOptions.append(" ]");

		throw die("Usage: ImageJ --update <command>\n"
				+ "\n"
				+ "Commands:\n"
				+ "\tdiff "
				+ diffOptions
				+ " [<files>]\n"
				+ "\tlist [<files>]\n"
				+ "\tlist-uptodate [<files>]\n"
				+ "\tlist-not-uptodate [<files>]\n"
				+ "\tlist-updateable [<files>]\n"
				+ "\tlist-modified [<files>]\n"
				+ "\tlist-current [<files>]\n"
				+ "\tlist-local-only [<files>]\n"
				+ "\tlist-shadowed [<files>]\n"
				+ "\tlist-from-site <name>\n"
				+ "\tshow [<files>]\n"
				+ "\tupdate [<files>]\n"
				+ "\tupdate-force [<files>]\n"
				+ "\tupdate-force-pristine [<files>]\n"
				+ "\trevert-unreal-changes [<files>]\n"
				+ "\tupgrade-java\n"
				+ "\tupload [--simulate] [--[update-]site <name>] [--force-shadow] [--forget-missing-dependencies] [<files>]\n"
				+ "\tupload-complete-site [--simulate] [--force] [--force-shadow] [--platforms <platform>[,<platform>...]] <name>\n"
				+ "\tlist-update-sites [<nick>...]\n"
				+ "\tadd-update-site <nick> <url> [<host> <upload-directory>]\n"
				+ "\tadd-update-sites <nick1> <url1> [<nick2> <url2> ...]\n"
				+ "\tremove-update-site <nick1> [<nick2> ...]\n"
				+ "\tdeactivate-update-site <nick> [<nick2> ...]\n"
				+ "\tedit-update-site <nick> <url> [<host> <upload-directory>]\n"
				+ "\trefresh-update-sites [--simulate] [--updateall]");
	}

	public static void main(final String... args) {
		if (System.getProperty("imagej.dir") == null) {
			final String ijDir = System.getProperty("ij.dir");
			if (ijDir != null) System.setProperty("imagej.dir", ijDir);
			else {
				final String fijiDir = System.getProperty("fiji.dir");
				if (fijiDir != null) System.setProperty("imagej.dir", fijiDir);
			}
		}
		try {
			main(AppUtils.getBaseDirectory("imagej.dir", CommandLine.class, "updater"), 79, null, true, args);
		} catch (final RuntimeException e) {
			log.error(e);
			System.exit(1);
		} catch (final Exception e) {
			log.error("Could not parse db.xml.gz", e);
			System.exit(1);
		}
	}

	public static void main(final File ijDir, final int columnCount,
			final String... args) {
		main(ijDir, columnCount, null, args);
	}

	public static void main(final File ijDir, final int columnCount,
			final Progress progress, final String... args) {
		main(ijDir, columnCount, progress, false, args);
	}

	private static void main(final File ijDir, final int columnCount,
			final Progress progress, final boolean standalone,
			final String[] args) {
		String http_proxy = System.getenv("http_proxy");
		if (http_proxy != null && http_proxy.startsWith("http://")) {
			final int colon = http_proxy.indexOf(':', 7);
			final int slash = http_proxy.indexOf('/', 7);
			int port = 80;
			if (colon < 0) {
				http_proxy = slash < 0 ? http_proxy.substring(7) : http_proxy
						.substring(7, slash);
			} else {
				port = Integer.parseInt(slash < 0 ? http_proxy
						.substring(colon + 1) : http_proxy.substring(colon + 1,
						slash));
				http_proxy = http_proxy.substring(7, colon);
			}
			System.setProperty("http.proxyHost", http_proxy);
			System.setProperty("http.proxyPort", "" + port);
		} else {
			UpdaterUtil.useSystemProxies();
		}
		Authenticator.setDefault(new ConsoleAuthenticator());
		setUserInterface();

		final CommandLine instance = new CommandLine(ijDir, columnCount,
				progress);
		instance.standalone = standalone;

		if (args.length == 0) {
			instance.usage();
		}

		final String command = args[0];
		if (command.equals("diff")) {
			instance.diff(makeList(args, 1));
		} else if (command.equals("list")) {
			instance.list(makeList(args, 1));
		} else if (command.equals("list-current")) {
			instance.listCurrent(makeList(args, 1));
		} else if (command.equals("list-uptodate")) {
			instance.listUptodate(makeList(args, 1));
		} else if (command.equals("list-not-uptodate")) {
			instance.listNotUptodate(makeList(args, 1));
		} else if (command.equals("list-updateable")) {
			instance.listUpdateable(makeList(args, 1));
		} else if (command.equals("list-modified")) {
			instance.listModified(makeList(args, 1));
		} else if (command.equals("list-local-only")) {
			instance.listLocalOnly(makeList(args, 1));
		} else if (command.equals("list-from-site")) {
			instance.listFromSite(makeList(args, 1));
		} else if (command.equals("list-shadowed")) {
			instance.listShadowed(makeList(args, 1));
		} else if (command.equals("show")) {
			instance.show(makeList(args, 1));
		} else if (command.equals("update")) {
			instance.update(makeList(args, 1));
		} else if (command.equals("update-force")) {
			instance.update(makeList(args, 1), true);
		} else if (command.equals("update-force-pristine")) {
			instance.update(makeList(args, 1), true, true);
		} else if (command.equals("revert-unreal-changes")) {
			instance.revertUnrealChanges(makeList(args, 1));
		} else if (command.equals("upgrade-java")) {
			instance.upgradeJava(makeList(args, 1));
		} else if (command.equals("upload")) {
			instance.upload(makeList(args, 1));
		} else if (command.equals("upload-complete-site")) {
			instance.uploadCompleteSite(makeList(args, 1));
		} else if (command.equals("list-update-sites")) {
			instance.listUpdateSites(makeList(args, 1));
		} else if (command.equals("add-update-site")) {
			instance.addOrEditUploadSite(makeList(args, 1), true);
		} else if (command.equals("edit-update-site")) {
			instance.addOrEditUploadSite(makeList(args, 1), false);
		} else if (command.equals("add-update-sites")) {
			final List<String> names = new ArrayList<>();
			final List<String> urls = new ArrayList<>();
			for (int i = 1; i < args.length - 1; i += 2) {
				names.add(args[i]);
				urls.add(args[i + 1]);
			}
			instance.addUploadSites(names, urls);
		} else if (command.equals("remove-update-site")) {
			instance.removeUploadSite(makeList(args, 1));
		} else if (command.equals("deactivate-update-site")) {
			instance.deactivateUpdateSite(makeList(args, 1));
		} else if (command.equals("refresh-update-sites")) {
			instance.refreshUpdateSites(makeList(args, 1));
		// hidden commands, i.e. not for public consumption
		} else if (command.equals("history")) {
			instance.history(makeList(args, 1));
		} else if (command.equals("downgrade")) {
			instance.downgrade(makeList(args, 1));
		} else {
			instance.usage();
		}
	}

	private void upgradeJava(List<String> list) {
		boolean simulate = false;
		if (list != null && !list.isEmpty() && "--simulate".equals(list.get(0))) {
			simulate = true;
			list.remove(0);
		}
		if (Java.isBelowRecommended()) {
			if (simulate) System.out.println("Would upgrade Java");
			else Java.upgrade();
		}
	}

	protected static class ConsoleAuthenticator extends Authenticator {

		protected Console console = System.console();

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			if (console == null) {
				throw new RuntimeException(
						"Need a console for user interaction!");
			}
			final String user = console
					.readLine("                                  \r" + getRequestingPrompt() + "\nUser: ");
			final char[] password = console.readPassword("Password: ");
			return new PasswordAuthentication(user, password);
		}
	}

	protected static void setUserInterface() {
		UpdaterUserInterface.set(new ConsoleUserInterface());
	}

	protected static class ConsoleUserInterface extends UpdaterUserInterface {

		protected Console console = System.console();
		protected int count;

		@Override
		public String getPassword(final String message) {
			if (console == null) {
				throw new RuntimeException(
						"Password prompt requires interactive operation!");
			}
			System.out.print(message + ": ");
			return new String(console.readPassword());
		}

		@Override
		public boolean promptYesNo(final String title, final String message) {
			if (console == null) {
				throw new RuntimeException(
						"Prompt requires interactive operation!");
			}
			System.err.print(title + ": " + message);
			final String line = console.readLine();
			return line.startsWith("y") || line.startsWith("Y");
		}

		public void showPrompt(String prompt) {
			if (!prompt.endsWith(": ")) {
				prompt += ": ";
			}
			System.err.print(prompt);
		}

		public String getUsername(final String prompt) {
			if (console == null) {
				throw new RuntimeException(
						"Username prompt requires interactive operation!");
			}
			showPrompt(prompt);
			return console.readLine();
		}

		public int askChoice(final String[] options) {
			if (console == null) {
				throw new RuntimeException(
						"Prompt requires interactive operation!");
			}
			for (int i = 0; i < options.length; i++)
				System.err.println("" + (i + 1) + ": " + options[i]);
			for (;;) {
				System.err.print("Choice? ");
				final String answer = console.readLine();
				if (answer.equals("")) {
					return -1;
				}
				try {
					return Integer.parseInt(answer) - 1;
				} catch (final Exception e) { /* ignore */
				}
			}
		}

		@Override
		public void error(final String message) {
			log.error(message);
		}

		@Override
		public void info(final String message, final String title) {
			log.info(title + ": " + message);
		}

		@Override
		public void log(final String message) {
			log.info(message);
		}

		@Override
		public void debug(final String message) {
			log.debug(message);
		}

		@Override
		public OutputStream getOutputStream() {
			return System.out;
		}

		@Override
		public void showStatus(final String message) {
			log(message);
		}

		@Override
		public void handleException(final Throwable exception) {
			exception.printStackTrace();
		}

		@Override
		public boolean isBatchMode() {
			return false;
		}

		@Override
		public int optionDialog(final String message, final String title,
				final Object[] options, final int def) {
			if (console == null) {
				throw new RuntimeException(
						"Prompt requires interactive operation!");
			}
			for (int i = 0; i < options.length; i++)
				System.err.println("" + (i + 1) + ") " + options[i]);
			for (;;) {
				System.out.print("Your choice (default: " + def + ": "
						+ options[def] + ")? ");
				final String line = console.readLine();
				if (line.equals("")) {
					return def;
				}
				try {
					final int option = Integer.parseInt(line);
					if (option > 0 && option <= options.length) {
						return option - 1;
					}
				} catch (final NumberFormatException e) {
					// ignore
				}
			}
		}

		@Override
		public String getPref(final String key) {
			return null;
		}

		@Override
		public void setPref(final String key, final String value) {
			log("Ignoring setting '" + key + "'");
		}

		@Override
		public void savePreferences() { /* do nothing */
		}

		@Override
		public void openURL(final String url) throws IOException {
			log("Please open " + url + " in your browser");
		}

		@Override
		public String getString(final String title) {
			if (console == null) {
				throw new RuntimeException(
						"Prompt requires interactive operation!");
			}
			System.out.print(title + ": ");
			return console.readLine();
		}

		@Override
		public void addWindow(final Frame window) { }

		@Override
		public void removeWindow(final Frame window) { }
	}
}
