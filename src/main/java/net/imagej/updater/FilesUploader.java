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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.imagej.updater.Conflicts.Conflict;
import net.imagej.updater.FileObject.Action;
import net.imagej.updater.util.Progress;
import net.imagej.updater.util.StderrProgress;
import net.imagej.updater.util.UpdaterUserInterface;
import net.imagej.updater.util.UpdaterUtil;

import org.scijava.Context;
import org.scijava.log.LogService;

/**
 * This class is responsible for writing updates to server, upon given the
 * updated file records.
 * <p>
 * Note: Files are uploaded differently:
 * </p>
 * <ul>
 * <li>Local-only files &amp; new file versions will have files AND details uploaded
 * </li>
 * <li>Uninstalled &amp; up-to-date files will ONLY have their details uploaded
 * (i.e.: XML file)</li>
 * </ul>
 * 
 * @author Johannes Schindelin
 */
public class FilesUploader {

	private FilesCollection files;
	private Uploader uploader;

	private String siteName;
	private UpdateSite site;
	private List<Uploadable> uploadables;
	private String compressed;
	private boolean loggedIn;

	private static UploaderService createUploaderService() {
		setClassLoaderIfNecessary();
		final Context context = new Context(UploaderService.class);
		return context.getService(UploaderService.class);
	}

	/**
	 * Sets the context class loader if necessary.
	 *
	 * If the current class cannot be found by the current Thread's context
	 * class loader, we should tell the Thread about the class loader that
	 * loaded this class.
	 */
	private static void setClassLoaderIfNecessary() {
		ClassLoader thisLoader = FilesUploader.class.getClassLoader();
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		for (; loader != null; loader = loader.getParent()) {
			if (thisLoader == loader) return;
		}
		Thread.currentThread().setContextClassLoader(thisLoader);
	}

	/**
	 * @deprecated use {@link #FilesUploader(UploaderService, FilesCollection, String, Progress)} instead
	 */
	@Deprecated
	public FilesUploader(final FilesCollection files, final String updateSite) {
		this(createUploaderService(), files, updateSite);
	}

	/**
	 * @deprecated use {@link #FilesUploader(UploaderService, FilesCollection, String, Progress)} instead
	 */
	@Deprecated
	public FilesUploader(final UploaderService uploaderService,
			final FilesCollection files, final String updateSite) {
		this(uploaderService, files, updateSite, null);
	}

	public FilesUploader(UploaderService uploaderService,
			final FilesCollection files, final String updateSite,
			final Progress progress) {
		if (uploaderService == null) uploaderService = createUploaderService();
		this.files = files;
		siteName = updateSite;
		site = files.getUpdateSite(updateSite, false);
		compressed = UpdaterUtil.XML_COMPRESSED;
		final String protocol = site.getUploadProtocol();
		uploader = uploaderService.installUploader(protocol, files,
				progress == null ? new StderrProgress() : progress);
		if (uploader == null) {
			throw new IllegalArgumentException(
					"No uploader found for protocol " + protocol);
		}
	}

	public boolean hasUploader() {
		return uploader != null;
	}

	public FilesCollection getFilesCollection() {
		return files;
	}

	public String getSiteName() {
		return siteName;
	}

	public String getDefaultUsername() {
		String host = site.getHost();
		if (host.startsWith("sftp:")) host = host.substring(5);
		final int at = host.indexOf('@');
		if (at > 0) return host.substring(0, at);
		final String name = UpdaterUserInterface.get().getPref(UpdaterUtil.PREFS_USER);
		if (name == null) return "";
		return name;
	}

	public String getUploadHost() {
		String host = site.getHost();
		if (uploader != null) {
			final String protocol = uploader.getProtocol();
			if (protocol != null && host.startsWith(protocol + ":")) {
				host = host.substring(protocol.length() + 1);
			}
		}
		return host.substring(host.indexOf('@') + 1);
	}

	public String getUploadDirectory() {
		return site.getUploadDirectory();
	}

	protected class DbXmlFile implements Uploadable {

		public byte[] bytes;

		@Override
		public String getFilename() {
			return compressed + ".lock";
		}

		@Override
		public String getPermissions() {
			return "C0444";
		}

		@Override
		public long getFilesize() {
			return bytes.length;
		}

		@Override
		public InputStream getInputStream() {
			return new ByteArrayInputStream(bytes);
		}

		@Override
		public String toString() {
			return compressed;
		}
	}

	public void upload(final Progress progress) throws Exception {
		if (uploader == null) throw new RuntimeException("No uploader set for " +
			site.getHost());
		if (!loggedIn) throw new RuntimeException("Not logged in!");
		final Iterable<Conflict> conflicts = new Conflicts(files).getConflicts(true);
		if (Conflicts.needsFeedback(conflicts)) {
			throw new RuntimeException("Unresolved upload conflicts!\n\n"
				+ UpdaterUtil.join("\n", conflicts));
		}
		uploader.addProgress(progress);
		uploader.addProgress(new VerifyTimestamp());

		uploadables = new ArrayList<>();
		final List<String> locks = new ArrayList<>();
		uploadables.add(new DbXmlFile());

		/*
		 * Stage new versions of otherwise unchanged files for upload (Bio-Formats, I
		 * am looking at you!).
		 */
		for (final FileObject file : files.forUpdateSite(siteName)) {
			if (file.getAction() == Action.INSTALLED && file.metadataChanged &&
				file.localFilename != null && !file.localFilename.equals(file.filename))
			{
				file.addPreviousVersion(file.current.checksum, file.current.timestamp,
					file.filename, 0);
				file.setAction(files, Action.UPLOAD);
			}
		}

		for (final FileObject file : files.toUpload(siteName)) {
			// remove obsolete/invalid dependencies
			for (Iterator<Dependency> iter = file.getDependencies().iterator(); iter.hasNext(); ) {
				final String filename = iter.next().filename;
				final FileObject other = files.get(filename);
				if (other == null || other.isObsolete()) {
					files.log.warn("Removed obsolete dependency " + filename + " of " + file.filename);
					iter.remove();
				}
			}
			uploadables.add(new UploadableFile(files, file));
		}

		// must be last lock
		locks.add(UpdaterUtil.XML_COMPRESSED);

		// verify that the files have not changed in the meantime
		final long[] timestamps = new long[uploadables.size()];
		int counter = 0;
		for (final Uploadable uploadable : uploadables) {
			if (uploadable instanceof UploadableFile) {
				final UploadableFile file = (UploadableFile) uploadable;
				timestamps[counter] = UpdaterUtil.getTimestamp(file.source);
			}
			verifyUnchanged(uploadable, true);
			counter++;
		}

		uploader.upload(uploadables, locks);

		// verify that the files have not changed in the meantime
		counter = 0;
		for (final Uploadable uploadable : uploadables) {
			if (uploadable instanceof UploadableFile) {
				final UploadableFile file = (UploadableFile) uploadable;
				if (timestamps[counter] != UpdaterUtil.getTimestamp(file.source)) throw new RuntimeException(
					"Timestamp of " + file.getFilename() +
						"changed since being checksummed (was " + timestamps[counter] +
						" but is " + UpdaterUtil.getTimestamp(file.source) + "!)");
			}
			counter++;
		}

		site.setLastModified(getCurrentLastModified());
	}

	protected void verifyUnchanged(final Uploadable file,
		final boolean checkTimestamp)
	{
		if (!(file instanceof UploadableFile)) return;
		final UploadableFile uploadable = (UploadableFile) file;
		final long size = uploadable.source.length();
		if (uploadable.filesize != size) throw new RuntimeException(
			"File size of " + uploadable.file.filename +
				" changed since being checksummed (was " + uploadable.filesize +
				" but is " + size + ")!");
		if (checkTimestamp) {
			final long stored =
				uploadable.file.getStatus() == FileObject.Status.LOCAL_ONLY
					? uploadable.file.current.timestamp : uploadable.file.localTimestamp;
			if (stored != UpdaterUtil.getTimestamp(uploadable.source)) throw new RuntimeException(
				"Timestamp of " + uploadable.file.filename +
					" changed since being checksummed (was " + stored + " but is " +
					UpdaterUtil.getTimestamp(uploadable.source) + ")!");
		}
	}

	protected void updateUploadTimestamp(final long timestamp) throws Exception {
		for (final Uploadable f : uploadables) {
			if (!(f instanceof UploadableFile)) continue;
			final UploadableFile uploadable = (UploadableFile) f;
			final FileObject file = uploadable.file;
			if (file == null) continue;
			file.filesize = uploadable.filesize = uploadable.source.length();
			file.localTimestamp = timestamp;
			uploadable.filename = file.filename + "-" + timestamp;
			if (file.getStatus() == FileObject.Status.LOCAL_ONLY) {
				file.setStatus(FileObject.Status.INSTALLED);
				file.current.timestamp = timestamp;
			}
		}

		final XMLFileWriter writer =
			new XMLFileWriter(files.clone(files.forUpdateSite(siteName, true)));
		if (files.size() > 0) writer.validate(false);
		((DbXmlFile) uploadables.get(0)).bytes =
			writer.toCompressedByteArray(false);

		uploader.calculateTotalSize(uploadables);
	}

	/*
	 * This class serves two purposes:
	 *
	 * - after locking, it ensures that the timestamp of db.xml.gz is the
	 *   same as when it was last downloaded, to prevent race-conditions
	 *
	 * - it takes the timestamp of the lock file and updates the timestamps
	 *   of all files to be uploaded, so that local time skews do not
	 *   harm
	 */
	protected class VerifyTimestamp implements Progress {

		@Override
		public void addItem(final Object item) {
			if (item != uploadables.get(0)) return;
			verifyTimestamp();
		}

		@Override
		public void setTitle(final String string) {
			try {
				updateUploadTimestamp(uploader.getTimestamp());
			}
			catch (final Exception e) {
				files.log.error(e);
				throw new RuntimeException("Could not update "
					+ "the timestamps in db.xml.gz");
			}
		}

		@Override
		public void itemDone(final Object item) {
			if (item instanceof UploadableFile) verifyUnchanged(
				(UploadableFile) item, false);
		}

		@Override
		public void setCount(final int count, final int total) {}

		@Override
		public void setItemCount(final int count, final int total) {}

		@Override
		public void done() {}
	}

	protected long getCurrentLastModified() {
		try {
			URLConnection connection;
			try {
				connection = UpdaterUtil.openConnection(new URL(site.getURL() + UpdaterUtil.XML_COMPRESSED));
			}
			catch (final FileNotFoundException e) {
				files.log.error(e);
				Thread.sleep(500);
				connection = UpdaterUtil.openConnection(new URL(site.getURL() + UpdaterUtil.XML_COMPRESSED));
			}
			connection.setUseCaches(false);
			final long lastModified = connection.getLastModified();
			connection.getInputStream().close();
			UpdaterUserInterface.get().debug(
				"got last modified " + lastModified + " = timestamp " +
					UpdaterUtil.timestamp(lastModified));
			return lastModified;
		}
		catch (final Exception e) {
			UpdaterUserInterface.get().debug(e.getMessage());
			if (files.size() == 0) return -1; // assume initial upload
			if (e instanceof FileNotFoundException) {
				files.log.debug(e);
			} else {
				files.log.error(e);
			}
			return 0;
		}
	}

	protected void verifyTimestamp() {
		if (site.getTimestamp() == 0) return;
		final long lastModified = getCurrentLastModified();
		if (!site.isLastModified(lastModified)) throw new RuntimeException(
			"db.xml.gz was " + "changed in the meantime (was " + site.getTimestamp() +
				" but now is " + UpdaterUtil.timestamp(lastModified) + ")");
	}

	public boolean login() {
		if (loggedIn) return loggedIn;
		loggedIn = uploader.login(this);
		return loggedIn;
	}

	public void logout() {
		if (uploader != null)
			uploader.logout();
	}

	@Deprecated
	public static FilesUploader initialUpload(final String url,
		final String sshHost, final String uploadDirectory)
	{
		return initialUploader(null, url, sshHost, uploadDirectory, null);
	}

	public static FilesUploader initialUploader(
		final UploaderService uploaderService, final String url,
		final String sshHost, final String uploadDirectory, final Progress progress)
	{
		final String updateSiteName = "Dummy";
		final FilesCollection files = new FilesCollection(null);
		files.addUpdateSite(updateSiteName, url, sshHost, uploadDirectory, Long
			.parseLong(UpdaterUtil.timestamp(-1)));
		return new FilesUploader(uploaderService, files, updateSiteName, progress);
	}

	public LogService getLog() {
		return files.log;
	}
}
