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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;

import net.imagej.updater.Conflicts.Conflict;
import net.imagej.updater.FileObject.Action;
import net.imagej.updater.FileObject.Status;
import net.imagej.updater.action.InstallOrUpdate;
import net.imagej.updater.action.KeepAsIs;
import net.imagej.updater.action.Remove;
import net.imagej.updater.action.Uninstall;
import net.imagej.updater.action.Upload;
import net.imagej.updater.util.DependencyAnalyzer;
import net.imagej.updater.util.HTTPSUtil;
import net.imagej.updater.util.Platforms;
import net.imagej.updater.util.Progress;
import net.imagej.updater.util.UpdateCanceledException;
import net.imagej.updater.util.UpdaterUtil;
import org.scijava.log.LogService;
import org.scijava.log.StderrLogService;
import org.xml.sax.SAXException;

/**
 * This class represents the database of available {@link FileObject}s.
 * <p>
 * By itself, a {@code FilesCollection} is fairly naive. If built around an
 * existing installation, the first action to take after instantiation is often
 * {@link #tryLoadingCollection()} - which will then recognize update sites,
 * etc...
 * </p>
 * <p>
 * After any updates to the {@code FilesCollection}, the {@link #write()} method
 * is used to persist configuration state to the {@code db.xml.gz} on disk. 
 * To rebuild the {@code FilesCollection} after such a modification, use
 * {@link #reloadCollectionAndChecksum(Progress)}. This will, for example,
 * provide {@link FileObject} instances for newly added update sites that are
 * not yet installed. To make actual file changes (not just modifications to the
 * database) you must first stage them with 
 * {@link FileObject#stageForUninstall(FilesCollection)} or
 * {@link FileObject#stageForUpdate(FilesCollection, boolean)}. At that point,
 * an {@link net.imagej.updater.Installer} can be used to create an
 * {@code update} directory reflecting the staged changes, and apply them if
 * needed.
 * </p>
 * <p>
 * Note that which files are incorporated into the {@code FilesCollection} is
 * based on the behavior of the {@link Checksummer}.
 * </p>
 * 
 * @author Johannes Schindelin
 */
@SuppressWarnings("serial")
public class FilesCollection extends LinkedHashMap<String, FileObject>
	implements Iterable<FileObject>
{

	static {
		XMLFileWriter.prepare();
	}

	public final static String DEFAULT_UPDATE_SITE = "ImageJ";
	private final File appRoot;
	public final LogService log;
	protected Set<FileObject> ignoredConflicts = new HashSet<>();
	protected List<Conflict> conflicts = new ArrayList<>();

	private Map<String, UpdateSite> updateSites;
	private boolean updateSitesChanged = false;

	private DependencyAnalyzer dependencyAnalyzer;

	private final String platform;
	private final Set<String> activePlatforms;

	/**
	 * This constructor takes the app root primarily for testing purposes.
	 * 
	 * @param appRoot the application base directory
	 */
	public FilesCollection(final File appRoot) {
		this(UpdaterUtil.getLogService(), appRoot);
	}

	/**
	 * This constructor takes the app root primarily for testing purposes.
	 * 
	 * @param log the log service
	 * @param appRoot the application base directory
	 */
	public FilesCollection(final LogService log, final File appRoot) {
		this.log = log == null ? new StderrLogService() : log;
		this.appRoot = appRoot;

		platform = Platforms.current();
		activePlatforms = Platforms.inferActive(appRoot);

		updateSites = new LinkedHashMap<>();
		final UpdateSite updateSite =
			addUpdateSite(DEFAULT_UPDATE_SITE, UpdaterUtil.MAIN_URL, null, null,
				timestamp());
		updateSite.setOfficial(true);
	}

	public File getAppRoot() {
		return appRoot;
	}

	public UpdateSite addUpdateSite(final String name, final String url,
		final String sshHost, final String uploadDirectory, final long timestamp)
	{
		final UpdateSite site = new UpdateSite(name, url, sshHost, uploadDirectory,
				null, null, timestamp);
		site.setActive(true);
		return addUpdateSite(site);
	}

	public UpdateSite addUpdateSite(UpdateSite site) {
		addUpdateSite(site.getName(), site);
		return site;
	}

	protected void addUpdateSite(final String name, final UpdateSite updateSite) {
		UpdateSite already = updateSites.get(name);
		updateSite.rank = already != null ? already.rank : updateSites.size();
		if (already != null) updateSite.setOfficial(already.isOfficial());
		updateSites.put(name,  updateSite);
		if (updateSite != already) setUpdateSitesChanged(true);
	}

	public void renameUpdateSite(final String oldName, final String newName) {
		if (getUpdateSite(newName, true) != null) throw new RuntimeException(
			"Update site " + newName + " exists already!");
		if (getUpdateSite(oldName, true) == null) throw new RuntimeException(
			"Update site " + oldName + " does not exist!");

		// handle all files
		for (final FileObject file : this)
			if (oldName.equals(file.updateSite)) file.updateSite = newName;

		// preserve order
		final Map<String, UpdateSite> oldMap = updateSites;
		updateSites = new LinkedHashMap<>();
		for (final String name : oldMap.keySet()) {
			final UpdateSite site = oldMap.get(name);
			if (name.equals(oldName)) site.setName(newName);
			addUpdateSite(site.getName(), site);
		}
	}

	public void removeUpdateSite(final String name) {
		for (final FileObject file : clone(forUpdateSite(name))) {
			file.removeFromUpdateSite(name, this);
		}
		updateSites.remove(name);
		setUpdateSitesChanged(true);

		// update rank
		int counter = 1;
		for (final Map.Entry<String, UpdateSite> entry : updateSites.entrySet()) {
			entry.getValue().rank = counter++;
		}
	}

	public void replaceUpdateSites(List<UpdateSite> sites) {
		updateSites.clear();
		sites.forEach(site -> addUpdateSite(site));
	}

	/** @deprecated use {@link #getUpdateSite(String, boolean)} instead */
	@Deprecated
	public UpdateSite getUpdateSite(final String name) {
		return getUpdateSite(name, false);
	}

	public UpdateSite getUpdateSite(final String name, final boolean evenDisabled) {
		if (name == null) return null;
		final UpdateSite site = updateSites.get(name);
		return evenDisabled || site == null || site.isActive() ? site : null;
	}

	/** @deprecated use {@link #getUpdateSiteNames(boolean)} instead */
	@Deprecated
	public Collection<String> getUpdateSiteNames() {
		return getUpdateSiteNames(false);
	}

	/** Gets the names of known update sites. */
	public Collection<String> getUpdateSiteNames(final boolean evenDisabled) {
		if (evenDisabled) return updateSites.keySet();
		final List<String> result = new ArrayList<>();
		final Iterator<java.util.Map.Entry<String, UpdateSite>> it = updateSites.entrySet().iterator();
		while (it.hasNext()) {
			java.util.Map.Entry<String, UpdateSite> entry = it.next();
			if (entry.getValue().isActive()) result.add(entry.getKey());
		}
		return result;
	}

	/** Gets the list of known update sites. */
	public Collection<UpdateSite> getUpdateSites(final boolean evenDisabled) {
		if (evenDisabled) return updateSites.values();
		final List<UpdateSite> result = new ArrayList<>();
		for (final UpdateSite site : updateSites.values()) {
			if (site.isActive()) result.add(site);
		}
		return result;
	}

	public Collection<String> getSiteNamesToUpload() {
		final Collection<String> set = new HashSet<>();
		for (final FileObject file : toUpload(false))
			set.add(file.updateSite);
		for (final FileObject file : toRemove())
			set.add(file.updateSite);
		// keep the update sites' order
		final List<String> result = new ArrayList<>();
		for (final String name : getUpdateSiteNames(false))
			if (set.contains(name)) result.add(name);
		if (result.size() != set.size()) throw new RuntimeException(
			"Unknown update site in " + set.toString() + " (known: " +
				result.toString() + ")");
		return result;
	}

	public boolean hasUploadableSites() {
		for (final UpdateSite site : updateSites.values())
			if (site.isActive() && site.isUploadable()) return true;
		return false;
	}

	public boolean hasUpdateSitesChanges() {
		return updateSitesChanged;
	}

	public void setUpdateSitesChanged(boolean updateSitesChanged) {
		this.updateSitesChanged = updateSitesChanged;
	}

	/**
	 * Deactivates the given update site.
	 * 
	 * @param site the site to deactivate
	 * @return the number of files marked for update/install/uninstall
	 */
	public int deactivateUpdateSite(final UpdateSite site) {
		if (!site.isActive()) return 0;
		final List<FileObject> list = new ArrayList<>();
		final String updateSite = site.getName();
		for (final FileObject file : forUpdateSite(updateSite)) {
			list.add(file);
		}
		for (final FileObject file : list) {
			file.removeFromUpdateSite(updateSite, this);
		}
		site.setActive(false);
		return list.size();
	}

	/**
	 * Activate the given update site.
	 * 
	 * @param updateSite the update site to activate
	 * @param progress the object to display the progress
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws SAXException
	 */
	public void activateUpdateSite(final UpdateSite updateSite, final Progress progress)
			throws ParserConfigurationException, IOException, SAXException {
		if (updateSite.isActive()) return;
		updateSite.setActive(true);
		reReadUpdateSite(updateSite.getName(), progress);
		markForUpdate(updateSite.getName(), false);
	}

	private void markForUpdate(final String updateSite, final boolean evenForcedUpdates) {
		for (final FileObject file : forUpdateSite(updateSite)) {
			if ((file.isUpdateable(evenForcedUpdates) || file.getStatus()
					.isValid(Action.INSTALL))
					&& file.isActivePlatform(this)) {
				file.setFirstValidAction(this, Action.UPDATE,
					Action.UNINSTALL, Action.INSTALL);
			}
		}
	}

	public void reReadUpdateSite(final String name, final Progress progress) throws ParserConfigurationException, IOException, SAXException {
		new XMLFileReader(this).read(name);
		final List<String> filesFromSite = new ArrayList<>();
		for (final FileObject file : forUpdateSite(name))
			filesFromSite.add(file.localFilename != null ? file.localFilename : file.filename);
		final Checksummer checksummer =
			new Checksummer(this, progress);
		checksummer.updateFromLocal(filesFromSite);
	}

	public String protocolsMissingUploaders(final UploaderService uploaderService, final Progress progress) {
		final Map<String, Set<String>> map = new LinkedHashMap<>();
		for (final Map.Entry<String, UpdateSite> entry : updateSites.entrySet()) {
			final UpdateSite site = entry.getValue();
			if (!site.isUploadable()) continue;
			final String protocol = site.getUploadProtocol();
			try {
				uploaderService.installUploader(protocol, this, progress);
			} catch (IllegalArgumentException e) {
				Set<String> set = map.get(protocol);
				if (set == null) {
					set = new LinkedHashSet<>();
					map.put(protocol, set);
				}
				set.add(entry.getKey());
			}
		}
		if (map.size() == 0) return null;
		final StringBuilder builder = new StringBuilder();
		builder.append(prefixUpdate("").isDirectory() ? "Uploads via these protocols require a restart:\n" : "Missing uploaders:\n");
		for (final Map.Entry<String, Set<String>> entry : map.entrySet()) {
			final String list = Arrays.toString(entry.getValue().toArray());
			builder.append("'").append(entry.getKey()).append("': ").append(list).append("\n");
		}
		return builder.toString();
	}

	public Set<GroupAction> getValidActions() {
		final Set<GroupAction> actions = new LinkedHashSet<>();
		actions.add(new KeepAsIs());
		boolean hasChanges = hasChanges(), hasUploadOrRemove = hasUploadOrRemove();
		if (!hasUploadOrRemove) {
			actions.add(new InstallOrUpdate());
		}
		if (hasUploadOrRemove || !hasChanges) {
			final Collection<String> siteNames = getSiteNamesToUpload();
			final Map<String, UpdateSite> updateSites;
			if (siteNames.size() == 0) updateSites = this.updateSites;
			else {
				updateSites = new LinkedHashMap<>();
				for (final String name : siteNames) {
					updateSites.put(name, getUpdateSite(name, true));
				}
			}
			for (final UpdateSite updateSite : getUpdateSites(false)) {
				if (updateSite.isUploadable()) {
					final String name = updateSite.getName();
					actions.add(new Upload(name));
					actions.add(new Remove(name));
				}
			}
		}
		if (!hasUploadOrRemove) {
			actions.add(new Uninstall());
		}
		return actions;
	}

	public Set<GroupAction> getValidActions(final Iterable<FileObject> selected) {
		final Set<GroupAction> actions = getValidActions();
		for (final Iterator<GroupAction> iter = actions.iterator(); iter.hasNext(); ) {
			final GroupAction action = iter.next();
			for (final FileObject file : selected) {
				if (!action.isValid(this, file)) {
					iter.remove();
					break;
				}
			}
		}
		return actions;
	}

	@Deprecated
	public Action[] getActions(final FileObject file) {
		return file.isUploadable(this, false) ? file.getStatus().getDeveloperActions()
			: file.getStatus().getActions();
	}

	@Deprecated
	public Action[] getActions(final Iterable<FileObject> files) {
		List<Action> result = null;
		for (final FileObject file : files) {
			final Action[] actions = getActions(file);
			if (result == null) {
				result = new ArrayList<>();
				for (final Action action : actions)
					result.add(action);
			}
			else {
				final Set<Action> set = new TreeSet<>();
				for (final Action action : actions)
					set.add(action);
				final Iterator<Action> iter = result.iterator();
				while (iter.hasNext())
					if (!set.contains(iter.next())) iter.remove();
			}
		}
		if (result == null) {
			return new Action[0];
		}
		return result.toArray(new Action[result.size()]);
	}

	public void read() throws IOException, ParserConfigurationException,
		SAXException
	{
		read(prefix(UpdaterUtil.XML_COMPRESSED));
	}

	public void read(final File file) throws IOException,
		ParserConfigurationException, SAXException
	{
		read(new FileInputStream(file));
	}

	public void read(final FileInputStream in) throws IOException,
		ParserConfigurationException, SAXException
	{
		new XMLFileReader(this).read(in);
		in.close();
	}

	public void write() throws IOException, SAXException,
		TransformerConfigurationException
	{
		final File out = prefix(UpdaterUtil.XML_COMPRESSED);
		final File tmp = prefix(UpdaterUtil.XML_COMPRESSED + ".tmp");
		new XMLFileWriter(this).write(new GZIPOutputStream(
				new FileOutputStream(tmp)), true);
		if (out.exists() && !out.delete())
			out.renameTo(prefix(UpdaterUtil.XML_COMPRESSED + ".backup"));
		tmp.renameTo(out);
		setUpdateSitesChanged(false);
	}

	public interface Filter {

		boolean matches(FileObject file);
	}

	public FilesCollection clone(final Iterable<FileObject> iterable) {
		final FilesCollection result = new FilesCollection(appRoot);
		for (final FileObject file : iterable)
			result.add(file);
		for (final String name : updateSites.keySet())
			result.updateSites.put(name, (UpdateSite) updateSites.get(name).clone());
		return result;
	}

	public Iterable<FileObject> toUploadOrRemove() {
		return filter(or(is(Action.UPLOAD), is(Action.REMOVE)));
	}

	public Iterable<FileObject> toUpload() {
		return toUpload(false);
	}

	public Iterable<FileObject> toUpload(final boolean includeMetadataChanges) {
		if (!includeMetadataChanges) return filter(is(Action.UPLOAD));
		return filter(or(is(Action.UPLOAD), new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.metadataChanged &&
					file.isUploadable(FilesCollection.this, false);
			}
		}));
	}

	public Iterable<FileObject> toUpload(final String updateSite) {
		return filter(and(is(Action.UPLOAD), isUpdateSite(updateSite)));
	}

	public Iterable<FileObject> toUninstall() {
		return filter(is(Action.UNINSTALL));
	}

	public Iterable<FileObject> toRemove() {
		return filter(is(Action.REMOVE));
	}

	public Iterable<FileObject> toUpdate() {
		return filter(is(Action.UPDATE));
	}

	public Iterable<FileObject> upToDate() {
		return filter(is(Action.INSTALLED));
	}

	public Iterable<FileObject> toInstall() {
		return filter(is(Action.INSTALL));
	}

	public Iterable<FileObject> toInstallOrUpdate() {
		return filter(oneOf(Action.INSTALL, Action.UPDATE));
	}

	public Iterable<FileObject> notHidden() {
		return filter(and(not(is(Status.OBSOLETE_UNINSTALLED)), doesPlatformMatch()));
	}

	public Iterable<FileObject> uninstalled() {
		return filter(is(Status.NOT_INSTALLED));
	}

	public Iterable<FileObject> installed() {
		return filter(not(oneOf(Status.LOCAL_ONLY,
			Status.NOT_INSTALLED)));
	}

	public Iterable<FileObject> locallyModified() {
		return filter(oneOf(Status.MODIFIED,
			Status.OBSOLETE_MODIFIED));
	}

	public Iterable<FileObject> forUpdateSite(final String name) {
		return forUpdateSite(name, false);
	}

	public Iterable<FileObject> forUpdateSite(final String name, boolean includeObsoletes) {
		Filter filter = and(doesPlatformMatch(), isUpdateSite(name));
		if (!includeObsoletes) {
			filter = and(not(is(Status.OBSOLETE_UNINSTALLED)), filter);
			return filter(filter);
		}
		// make sure that overridden records are kept
		List<FileObject> result = new ArrayList<>();
		for (FileObject file : this) {
			if (filter.matches(file))
				result.add(file);
			else {
				FileObject overridden = file.overriddenUpdateSites.get(name);
				if (overridden != null)
					result.add(overridden);
			}
		}
		return result;
	}

	public Iterable<FileObject> managedFiles() {
		return filter(not(is(Status.LOCAL_ONLY)));
	}

	public Iterable<FileObject> localOnly() {
		return filter(is(Status.LOCAL_ONLY));
	}

	public Iterable<FileObject> shownByDefault() {
		/*
		 * Let's not show the NOT_INSTALLED ones, as the user chose not
		 * to have them.
		 */
		final Status[] oneOf =
			{ Status.UPDATEABLE, Status.NEW, Status.OBSOLETE,
				Status.OBSOLETE_MODIFIED };
		return filter(or(oneOf(oneOf), oneOf(Action.INSTALL, Action.UPDATE, Action.UNINSTALL)));
	}

	public Iterable<FileObject> uploadable() {
		return uploadable(false);
	}

	/**
	 * Gets the list of uploadable files.
	 * 
	 * @param assumeModified true if we want the potentially uploadable files if
	 *          they (or their metadata) were to be changed.
	 * @return the list of uploadable files
	 */
	public Iterable<FileObject> uploadable(final boolean assumeModified) {
		return filter(new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.isUploadable(FilesCollection.this, assumeModified);
			}
		});
	}

	public Iterable<FileObject> changes() {
		return filter(new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.getAction() != file.getStatus().getNoAction();
			}
		});
	}

	public static class FilteredIterator implements Iterator<FileObject> {

		Filter filter;
		boolean opposite;
		Iterator<FileObject> iterator;
		FileObject next;

		FilteredIterator(final Filter filter, final Iterable<FileObject> files) {
			this.filter = filter;
			iterator = files.iterator();
			findNext();
		}

		@Override
		public boolean hasNext() {
			return next != null;
		}

		@Override
		public FileObject next() {
			final FileObject file = next;
			findNext();
			return file;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}

		protected void findNext() {
			while (iterator.hasNext()) {
				next = iterator.next();
				if (filter.matches(next)) return;
			}
			next = null;
		}
	}

	public static Iterable<FileObject> filter(final Filter filter,
		final Iterable<FileObject> files)
	{
		return new Iterable<FileObject>() {

			@Override
			public Iterator<FileObject> iterator() {
				return new FilteredIterator(filter, files);
			}
		};
	}

	public static Iterable<FileObject> filter(final String search,
		final Iterable<FileObject> files)
	{
		final String keyword = search.trim().toLowerCase();
		return filter(new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.getFilename().trim().toLowerCase().indexOf(keyword) >= 0;
			}
		}, files);
	}

	public Filter yes() {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return true;
			}
		};
	}

	public Filter doesPlatformMatch() {
		// If we're a developer or no platform was specified, return yes
		if (hasUploadableSites()) return yes();
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.isActivePlatform(FilesCollection.this);
			}
		};
	}

	public Filter is(final Action action) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.getAction() == action;
			}
		};
	}

	public Filter isNoAction() {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.getAction() == file.getStatus().getNoAction();
			}
		};
	}

	public Filter oneOf(final Action... actions) {
		final Set<Action> oneOf = new HashSet<>();
		for (final Action action : actions)
			oneOf.add(action);
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return oneOf.contains(file.getAction());
			}
		};
	}

	public Filter is(final Status status) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.getStatus() == status;
			}
		};
	}

	public Filter hasMetadataChanges() {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.metadataChanged;
			}
		};
	}

	public Filter isUpdateSite(final String updateSite) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.updateSite != null && // is null for local-only files
					file.updateSite.equals(updateSite);
			}
		};
	}

	public Filter oneOf(final Status... states) {
		final Set<Status> oneOf = new HashSet<>();
		for (final Status status : states)
			oneOf.add(status);
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return oneOf.contains(file.getStatus());
			}
		};
	}

	public Filter startsWith(final String prefix) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.filename.startsWith(prefix);
			}
		};
	}

	public Filter startsWith(final String... prefixes) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				for (final String prefix : prefixes)
					if (file.filename.startsWith(prefix)) return true;
				return false;
			}
		};
	}

	public Filter endsWith(final String suffix) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.filename.endsWith(suffix);
			}
		};
	}

	public Filter not(final Filter filter) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return !filter.matches(file);
			}
		};
	}

	public Filter or(final Filter a, final Filter b) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return a.matches(file) || b.matches(file);
			}
		};
	}

	public Filter and(final Filter a, final Filter b) {
		return new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return a.matches(file) && b.matches(file);
			}
		};
	}

	public Iterable<FileObject> filter(final Filter filter) {
		return filter(filter, this);
	}

	public FileObject
		getFileFromDigest(final String filename, final String digest)
	{
		for (final FileObject file : this)
			if (file.getFilename().equals(filename) &&
				file.getChecksum().equals(digest)) return file;
		return null;
	}

	public Iterable<String> analyzeDependencies(final FileObject file) {
		try {
			if (dependencyAnalyzer == null) dependencyAnalyzer =
				new DependencyAnalyzer(appRoot);
			return dependencyAnalyzer.getDependencies(appRoot, file);
		}
		catch (final IOException e) {
			log.error(e);
			return null;
		}
	}

	public void updateDependencies(final FileObject file) {
		final Iterable<String> dependencies = analyzeDependencies(file);
		if (dependencies == null) return;
		for (final String dependency : dependencies)
			file.addDependency(dependency, prefix(dependency));
	}

	public boolean has(final Filter filter) {
		for (final FileObject file : this)
			if (filter.matches(file)) return true;
		return false;
	}

	public boolean hasChanges() {
		return changes().iterator().hasNext();
	}

	public boolean hasUploadOrRemove() {
		return has(oneOf(Action.UPLOAD, Action.REMOVE));
	}

	public boolean hasForcableUpdates() {
		for (final FileObject file : updateable(true))
			if (!file.isUpdateable(false)) return true;
		return false;
	}

	public Iterable<FileObject> updateable(final boolean evenForcedOnes) {
		return filter(new Filter() {

			@Override
			public boolean matches(final FileObject file) {
				return file.isUpdateable(evenForcedOnes) && file.isActivePlatform(FilesCollection.this);
			}
		});
	}

	public void markForUpdate(final boolean evenForcedUpdates) {
		for (final FileObject file : updateable(evenForcedUpdates)) {
			file.setFirstValidAction(this, Action.UPDATE,
				Action.UNINSTALL, Action.INSTALL);
		}
	}

	public String getURL(final FileObject file) {
		final String siteName = file.updateSite;
		assert (siteName != null && !siteName.equals(""));
		final UpdateSite site = getUpdateSite(siteName, false);
		if (site == null) return null;
		return site.getURL() + file.filename.replace(" ", "%20") + "-" +
			file.getTimestamp();
	}

	public static class DependencyMap extends
		HashMap<FileObject, FilesCollection>
	{

		// returns true when the map did not have the dependency before
		public boolean
			add(final FileObject dependency, final FileObject dependencee)
		{
			if (containsKey(dependency)) {
				get(dependency).add(dependencee);
				return false;
			}
			final FilesCollection list = new FilesCollection(null);
			list.add(dependencee);
			put(dependency, list);
			return true;
		}
	}

	void addDependencies(final FileObject file, final DependencyMap map,
		final boolean overriding)
	{
		for (final Dependency dependency : file.getDependencies()) {
			final FileObject other = get(dependency.filename);
			if (other == null || overriding != dependency.overrides ||
				!other.isActivePlatform(this)) continue;
			if (other.isObsolete() && other.willNotBeInstalled()) {
				log.debug("Ignoring obsolete dependency " + dependency.filename
						+ " of " + file.filename);
				continue;
			}
			if (dependency.overrides) {
				if (other.willNotBeInstalled()) continue;
			}
			else if (other.willBeUpToDate()) continue;
			if (!map.add(other, file)) continue;
			// overriding dependencies are not recursive
			if (!overriding) addDependencies(other, map, overriding);
		}
	}

	/**
	 * Gets a map listing all the dependencees of files to be installed or
	 * updated.
	 * 
	 * @param overridingOnes
	 *            whether to include files that override a particular dependency
	 * @return the map
	 */
	public DependencyMap getDependencies(final boolean overridingOnes) {
		return getDependencies(toInstallOrUpdate(), overridingOnes);
	}

	/**
	 * Gets a map listing all the dependencees of the specified files.
	 * 
	 * @param files
	 *            the dependencies
	 * @param overridingOnes
	 *            whether to include files that override a particular dependency
	 * @return the map
	 */
	public DependencyMap getDependencies(final Iterable<FileObject> files, final boolean overridingOnes) {
		final DependencyMap result = new DependencyMap();
		for (final FileObject file : files)
			addDependencies(file, result, overridingOnes);
		return result;
	}

	public void sort() {
		// first letters in this order: 'C', 'I', 'f', 'p', 'j', 's', 'i', 'm', 'l,
		// 'r'
		final ArrayList<FileObject> files = new ArrayList<>();
		for (final FileObject file : this) {
			files.add(file);
		}
		Collections.sort(files, new Comparator<FileObject>() {

			@Override
			public int compare(final FileObject a, final FileObject b) {
				final int result = firstChar(a) - firstChar(b);
				return result != 0 ? result : a.filename.compareTo(b.filename);
			}

			int firstChar(final FileObject file) {
				final char c = file.filename.charAt(0);
				final int index = "CIfpjsim".indexOf(c);
				return index < 0 ? 0x200 + c : index;
			}
		});
		this.clear();
		for (final FileObject file : files) {
			super.put(file.filename, file);
		}
	}

	String checkForCircularDependency(final FileObject file,
		final Set<FileObject> seen, final String updateSite)
	{
		if (seen.contains(file)) return "";
		final String result =
			checkForCircularDependency(file, seen, new HashSet<FileObject>(), updateSite);
		if (result == null) return "";

		// Display only the circular dependency
		final int last = result.lastIndexOf(' ');
		final int off = result.lastIndexOf(result.substring(last), last - 1);
		return "Circular dependency detected: " + result.substring(off + 1) + "\n";
	}

	String checkForCircularDependency(final FileObject file,
		final Set<FileObject> seen, final Set<FileObject> chain, final String updateSite)
	{
		if (seen.contains(file)) return null;
		for (final String dependency : file.dependencies.keySet()) {
			final FileObject dep = get(dependency);
			if (dep == null) continue;
			if (updateSite != null && !updateSite.equals(dep.updateSite)) continue;
			if (chain.contains(dep)) return " " + dependency;
			chain.add(dep);
			final String result = checkForCircularDependency(dep, seen, chain, updateSite);
			seen.add(dep);
			if (result != null) return " " + dependency + " ->" + result;
			chain.remove(dep);
		}
		return null;
	}

	/* returns null if consistent, error string when not */
	public String checkConsistency() {
		final Collection<String> uploadSiteNames = getSiteNamesToUpload();
		final String uploadSiteName = uploadSiteNames.isEmpty() ? null :
			uploadSiteNames.iterator().next();
		final StringBuilder result = new StringBuilder();
		final Set<FileObject> circularChecked = new HashSet<>();
		for (final FileObject file : this) {
			if (uploadSiteName != null && !uploadSiteName.equals(file.updateSite) || file.getAction() == Action.REMOVE) {
				continue;
			}
			result.append(checkForCircularDependency(file, circularChecked, uploadSiteName));
			// only non-obsolete components can have dependencies
			final Set<String> deps = file.dependencies.keySet();
			if (deps.size() > 0 && file.isObsolete() && file.getAction() != Action.UPLOAD) {
				result.append("Obsolete file " + file + " has dependencies: " + UpdaterUtil.join(", ", deps) + "!\n");
			}
			for (final String dependency : deps) {
				final FileObject dep = get(dependency);
				if (dep == null || dep.current == null) result.append("The file " +
					file + " has the obsolete/local-only " + "dependency " + dependency +
					"!\n");
			}
		}
		return result.length() > 0 ? result.toString() : null;
	}

	public File prefix(final FileObject file) {
		return prefix(file.getFilename());
	}

	public File prefix(final String path) {
		final File file = new File(path);
		if (file.isAbsolute()) return file;
		assert (appRoot != null);
		return new File(appRoot, path);
	}

	public File prefixUpdate(final String path) {
		return prefix("update/" + path);
	}

	public boolean fileExists(final String filename) {
		return prefix(filename).exists();
	}

	@Override
	public String toString() {
		return UpdaterUtil.join(", ", this);
	}

	@Deprecated
	public FileObject get(final int index) {
		throw new UnsupportedOperationException();
	}

	public void add(final FileObject file) {
		super.put(file.getFilename(true), file);
	}

	@Override
	public FileObject get(final Object filename) {
		return super.get(FileObject.getFilename((String)filename, true));
	}

	@Override
	public FileObject put(final String key, final FileObject file) {
		throw new UnsupportedOperationException();
	}

	@Override
	public FileObject remove(final Object file) {
		if (file instanceof FileObject) super.remove(((FileObject) file).getFilename(true));
		if (file instanceof String) return super.remove(FileObject.getFilename((String)file, true));
		return null;
	}

	@Override
	public Iterator<FileObject> iterator() {
		final Iterator<Map.Entry<String, FileObject>> iterator = entrySet().iterator();
		return new Iterator<FileObject>() {

			@Override
			public boolean hasNext() {
				return iterator.hasNext();
			}

			@Override
			public FileObject next() {
				return iterator.next().getValue();
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}

		};
	}

	public void tryLoadingCollection() throws ParserConfigurationException, SAXException {
		try {
			read();
		}
		catch (final FileNotFoundException e) {
			if (prefix("plugins/Fiji_Updater.jar").exists()) {
				// make sure that the Fiji update site is enabled
				UpdateSite fiji = getUpdateSite("Fiji", true);
				if (fiji == null) {
					addUpdateSite("Fiji", HTTPSUtil.getProtocol() + "update.fiji.sc/", null, null, 0);
				}
			}
		}
		catch (final IOException e) { /* ignore */ }
	}

	public String reloadCollectionAndChecksum(final Progress progress) {
		// clear the files
		clear();

		final XMLFileDownloader downloader = new XMLFileDownloader(this);
		downloader.addProgress(progress);
		try {
			downloader.start(false);
		} catch (final UpdateCanceledException e) {
			downloader.done();
			throw e;
		}
		new Checksummer(this, progress).updateFromLocal();

		// When upstream fixed dependencies, heed them
		for (final FileObject file : upToDate()) {
			for (final FileObject dependency : file.getFileDependencies(this, false)) {
				if (dependency.getAction() == Action.NOT_INSTALLED && dependency.isActivePlatform(this)) {
					dependency.setAction(this, Action.INSTALL);
				}
			}
		}

		return downloader.getWarnings();
	}

	public String downloadIndexAndChecksum(final Progress progress) throws ParserConfigurationException, SAXException {
		tryLoadingCollection();
		return reloadCollectionAndChecksum(progress);
	}

	public List<Conflict> getConflicts() {
		return conflicts;
	}

	/**
	 * Utility method for the Bug Submitter
	 *
	 * @return the list of files known to the Updater, with versions, as a String
	 */
	public static String getInstalledVersions(final File ijDirectory, final Progress progress) {
		final StringBuilder sb = new StringBuilder();
		final FilesCollection files = new FilesCollection(ijDirectory);
		try {
			files.read();
		} catch (Exception e) {
			sb.append("Error while reading db.xml.gz: ").append(e.getMessage()).append("\n\n");
		}
		final Checksummer checksummer = new Checksummer(files, progress);
		try {
			checksummer.updateFromLocal();
		} catch (UpdateCanceledException t) {
			return null;
		}

		final Map<String, FileObject.Version> checksums = checksummer.getCachedChecksums();

		sb.append("Activated update sites:\n");
		for (final UpdateSite site : files.getUpdateSites(false)) {
			sb.append(site.getName()).append(": ").append(site.getURL())
					.append(" (last check:").append(site.getTimestamp())
					.append(")\n");
		}
		boolean notUpToDateShown = false;
		for (final Map.Entry<String, FileObject.Version> entry : checksums.entrySet()) {
			String file = entry.getKey();
			if (file.startsWith(":") && file.length() == 41) continue;
			final FileObject fileObject = files.get(file);
			if (fileObject != null && fileObject.getStatus() == Status.INSTALLED) continue;
			if (!notUpToDateShown) {
				sb.append("\nFiles not up-to-date:\n");
				notUpToDateShown = true;
			}
			final FileObject.Version version = entry.getValue();
			String checksum = version.checksum;
			if (version.checksum != null && version.checksum.length() > 8) {
				final StringBuilder rebuild = new StringBuilder();
				for (final String element : checksum.split(":")) {
					if (rebuild.length() > 0) rebuild.append(":");
					if (element == null || element.length() <= 8) rebuild.append(element);
					else rebuild.append(element.substring(0, 8));
				}
				checksum = rebuild.toString();
			}
			sb.append("  ").append(checksum).append(" ");
			if (fileObject != null) sb.append("(").append(fileObject.getStatus()).append(") ");
			sb.append(version.timestamp).append(" ");
			sb.append(file).append("\n");
		}
		return sb.toString();
	}

	public Collection<String> getProtocols(Iterable<FileObject> selected) {
		final Set<String> protocols = new LinkedHashSet<>();
		for (final FileObject file : selected) {
			final UpdateSite site = getUpdateSite(file.updateSite, false);
			if (site != null) {
				if (site.getHost() == null)
					protocols.add("unknown(" + file.filename + ")");
				else
					protocols.add(site.getUploadProtocol());
			}
		}
		return protocols;
	}

	public String platform() {
		return platform;
	}

	public boolean isActivePlatform(final String platform) {
		return Platforms.matches(activePlatforms, platform);
	}

	public void setActivePlatforms(final String... platforms) {
		activePlatforms.clear();
		for (String platform : platforms) {
			if (platform == null) continue;
			platform = platform.trim();
			if ("all".equals(platform)) {
				activePlatforms.add(this.platform);
				activePlatforms.addAll(Platforms.known());
			} else {
				activePlatforms.add(platform);
			}
		}
	}

	// -- Helper methods --

	private long timestamp() {
		if (appRoot == null) return 0;
		return UpdaterUtil.getTimestamp(prefix(UpdaterUtil.XML_COMPRESSED));
	}

}
