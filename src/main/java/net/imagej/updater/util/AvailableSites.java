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

package net.imagej.updater.util;

import net.imagej.updater.URLChange;
import net.imagej.updater.FilesCollection;
import net.imagej.updater.UpdateSite;
import net.imagej.util.MediaWikiClient;
import org.scijava.log.LogService;
import org.scijava.log.Logger;
import org.xml.sax.SAXException;
import javax.xml.transform.TransformerConfigurationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class for parsing the list of available update sites.
 * 
 * @author Johannes Schindelin
 * @author Curtis Rueden
 */
public final class AvailableSites {

	private AvailableSites() {
		// NB: prevent instantiation of utility class
	}

	private static final String SITE_LIST_PAGE_TITLE = "List of update sites";

	public static Map<String, UpdateSite> getAvailableSites() throws IOException {
		return getAvailableSites(null);
	}

	public static Map<String, UpdateSite> getAvailableSites(final Logger log) throws IOException {
		final String text = downloadWikiPage(log);
		final Map<String, UpdateSite> result = parseWikiPage(text);
		runSanityChecks(result);
		return result;
	}

	private static String downloadWikiPage(final Logger log) throws IOException {
		String wikiURL = HTTPSUtil.getProtocol() + "imagej.net/";

		if(log != null) log.info("Reading available sites from " + wikiURL);
		else System.out.println("[INFO] Reading available sites from " + wikiURL);

		final MediaWikiClient wiki = new MediaWikiClient(wikiURL);
		return wiki.getPageSource(SITE_LIST_PAGE_TITLE);
	}

	private static Map<String, UpdateSite> parseWikiPage(final String text) throws IOException {
		final int start = text.indexOf("\n{| class=\"wikitable\"\n");
		int end = text.indexOf("\n|}\n", start);
		if (end < 0) end = text.length();
		if (start < 0) {
			throw new IOException("Could not find table");
		}
		final String[] table = text.substring(start + 1, end).split("\n\\|-");

		final Map<String, UpdateSite> result = new LinkedHashMap<>();
		int nameColumn = -1;
		int urlColumn = -1;
		int descriptionColumn = -1;
		int maintainerColumn = -1;
		for (final String row : table) {
			if (row.matches("(?s)(\\{\\||[\\|!](style=\"vertical-align|colspan=\"4\")).*")) continue;
			final String[] columns = row.split("\n[\\|!]");
			if (columns.length > 1 && columns[1].endsWith("|'''Name'''")) {
				nameColumn = urlColumn = descriptionColumn = maintainerColumn = -1;
				int i = 0;
				for (final String column : columns) {
					if (column.endsWith("|'''Name'''")) nameColumn = i;
					else if (column.endsWith("|'''Site'''")) urlColumn = i;
					else if (column.endsWith("|'''URL'''")) urlColumn = i;
					else if (column.endsWith("|'''Description'''")) descriptionColumn = i;
					else if (column.endsWith("|'''Maintainer'''")) maintainerColumn = i;
					i++;
				}
			} else if (nameColumn >= 0 && urlColumn >= 0 && columns.length > nameColumn && columns.length > urlColumn) {
				final String name = stripWikiMarkup(columns, nameColumn);
				final String url = stripWikiMarkup(columns, urlColumn);
				final String description = stripWikiMarkup(columns, descriptionColumn);
				final String maintainer = stripWikiMarkup(columns, maintainerColumn);
				final UpdateSite info = new UpdateSite(name, url, null, null, description, maintainer, 0l);
				info.setOfficial(true);
				result.put(info.getURL(), info);
			}
		}
		return result;
	}

	private static void runSanityChecks(final Map<String, UpdateSite> result) throws IOException {
		final Iterator<UpdateSite> iter = result.values().iterator();
		if (!iter.hasNext()) throw new IOException("Invalid page: " + SITE_LIST_PAGE_TITLE);
	}

	/**
	 * As {@link #initializeAndAddSites(FilesCollection)} with an optional
	 * {@link Logger} for reporting errors.
	 */
	public static List< URLChange > initializeAndAddSites(final FilesCollection files, final Logger log) {
		return initializeAndAddSites(files, tryGetAvailableSites(log));
	}

	public static void initializeAndAddSites(final FilesCollection files, final LogService log) {
		initializeAndAddSites(files, tryGetAvailableSites(log));
	}

	static List< URLChange > initializeAndAddSites(
			final FilesCollection files, final Collection< UpdateSite > availableSites)
	{
		final List< UpdateSite > sites = prepareAvailableUpdateSites(availableSites);
		// method is package private to allow testing
		ArrayList< URLChange > urlChanges = mergeLocalAndAvailableUpdateSites(
				files, sites);
		makeSureNamesAreUnique(sites);

		files.replaceUpdateSites(sites);

		return urlChanges;
	}

	private static List< UpdateSite > prepareAvailableUpdateSites(
			Collection< UpdateSite > availableSites)
	{
		// method is package private to allow testing
		for (final UpdateSite site : availableSites)
			site.setURL(HTTPSUtil.fixImageJUserSiteProtocol(site.getURL()));
		final List<UpdateSite> sites = new ArrayList<>();
		// make sure that the main update site is the first one.
		sites.add(initializeMainUpdateSite());
		addAvailableUpdateSites(sites, availableSites);
		return sites;
	}

	private static Collection< UpdateSite > tryGetAvailableSites(Logger log)
	{
		try {
			return getAvailableSites(log).values();
		} catch (Exception e) {
			if (log != null) log.error("Error processing available update sites from ImageJ wiki", e);
			else e.printStackTrace();
			return Collections.emptyList();
		}
	}

	private static UpdateSite initializeMainUpdateSite() {
		final UpdateSite mainSite = new UpdateSite(FilesCollection.DEFAULT_UPDATE_SITE, UpdaterUtil.MAIN_URL, "", "", null, null, 0l);
		mainSite.setOfficial(true);
		return mainSite;
	}

	private static void addAvailableUpdateSites(List< UpdateSite > sites,
			Collection< UpdateSite > availableSites)
	{
		for (final UpdateSite site : availableSites ) {
			Integer index = findIndexByName(sites, site);
			if (index == null) {
				sites.add(site);
			} else {
				sites.set(index, site);
			}
		}
	}

	private static ArrayList< URLChange > mergeLocalAndAvailableUpdateSites(FilesCollection files,
	                                                                        List< UpdateSite > sites)
	{
		ArrayList< URLChange > urlChanges = new ArrayList<>();
		for (final UpdateSite local : files.getUpdateSites(true)) {
			Integer index = findIndexByName(sites, local);
			if (index == null) {
				sites.add(local);
				Optional< URLChange > change = URLChange.create(local,
						HTTPSUtil.fixImageJUserSiteProtocol(local.getURL()));
				change.ifPresent( urlChanges::add );
			} else {
				final UpdateSite available = sites.get(index);
				local.setOfficial(available.isOfficial());
				local.setDescription(available.getDescription());
				local.setMaintainer(available.getMaintainer());
				Optional< URLChange > change =
						URLChange.create(local, available.getURL());
				change.ifPresent( urlChanges::add );
				sites.set(index, local);
			}
		}
		return urlChanges;
	}

	private static Integer findIndexByName(List<UpdateSite> sites, UpdateSite site) {
		for (int i = 0; i < sites.size(); i++) {
			if( sites.get(i).getName().equals(site.getName()) )
				return i;
		}
		return null;
	}

	private static void makeSureNamesAreUnique(List< UpdateSite > sites)
	{
		final Set<String> names = new HashSet<>();
		for (final UpdateSite site : sites) {
			if (site.isActive()) continue;
			if (names.contains(site.getName())) {
				int i = 2;
				while (names.contains(site.getName() + "-" + i))
					i++;
				site.setName(site.getName() + ("-" + i));
			}
			names.add(site.getName());
		}
	}

	/**
	 * Initializes the list of update sites,
	 * <em>and</em> adds them to the given {@link FilesCollection}.
	 */
	public static void initializeAndAddSites(final FilesCollection files) {
		initializeAndAddSites(files, (Logger) null);
	}

	private static String stripWikiMarkup(final String[] columns, int index) {
		if (index < 0 || index >= columns.length) return null;
		final String string = columns[index];
		return string.replaceAll("'''", "").replaceAll("\\[\\[([^\\|\\]]*\\|)?([^\\]]*)\\]\\]", "$2").replaceAll("\\[[^\\[][^ ]*([^\\]]*)\\]", "$1");
	}

	/**
	 * Checks whether for all update sites of a given {@link FilesCollection} there is
	 * an updated URL on the remote list of available update sites.
	 */
	public static boolean hasUpdateSiteURLUpdates(FilesCollection plugins) throws IOException {
		return hasUpdateSiteURLUpdates(plugins, getAvailableSites());
	}

	/**
	 * Checks whether for all update sites of a given {@link FilesCollection} there is
	 * an updated URL on the given list of available sites.
	 */
	public static boolean hasUpdateSiteURLUpdates(FilesCollection plugins, Map<String, UpdateSite> availableSites) {
		for(UpdateSite site : availableSites.values()) {
			// TODO use site id
			UpdateSite local = plugins.getUpdateSite(site.getName(), false);
			if(local == null) continue;
			if(!local.shouldKeepURL() && !local.getURL().equals(site.getURL())) {
				return true;
			}
		}
		if(HTTPSUtil.hasImageJUserSiteProtocolUpdates(plugins)) return true;
		return false;
	}

	/**
	 * Apply prepared changes to update site URLs
	 */
	public static void applySitesURLUpdates(FilesCollection plugins, List< URLChange > urlChanges ) {
		for(URLChange site : urlChanges) {
			site.applyIfApproved();
		}
		try {
			plugins.write();
		} catch (IOException | SAXException | TransformerConfigurationException e) {
			e.printStackTrace();
		}
	}
}
