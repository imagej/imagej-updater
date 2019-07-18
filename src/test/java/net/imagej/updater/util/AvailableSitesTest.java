/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2019 Board of Regents of the University of
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
import org.junit.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static junit.framework.TestCase.assertTrue;
import static net.imagej.updater.UpdaterTestUtils.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Tests functionalities related to the available update sites,
 * including HTTPS compatibility
 *
 * @author Deborah Schmidt
 */
public class AvailableSitesTest {

	@Test
	public void testSecureMode() throws IOException {
		// check if HTTPS is supported
		HTTPSUtil.checkHTTPSSupport(null);
		boolean secure = HTTPSUtil.supportsHTTPS();

		// get list of available sites
		Map<String, UpdateSite > availableUpdateSites = AvailableSites.getAvailableSites();

		// test whether the main ImageJ update site is using HTTP or HTTPS
		UpdateSite mainUpdateSite = null;
		for(UpdateSite site : availableUpdateSites.values()) {
			if(site.getName().equals("ImageJ")) {
				mainUpdateSite = site;
				break;
			}
		}
		assertNotNull(mainUpdateSite);
		assertEquals(mainUpdateSite.getURL(), secure? "https://update.imagej.net/" : "http://update.imagej.net/");
	}

	@Test
	public void testAvailableUpdateSites() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();

		// add two official update sites
		applyOfficialUpdateSitesList(files, "a", "http://a.de/", "b", "http://b.de/");

		// restart
		files = readFromDb(files);

		// test if update site URLS match
		assertEquals("http://a.de/", files.getUpdateSite("a", true).getURL());
		assertEquals("http://b.de/", files.getUpdateSite("b", true).getURL());

		cleanup(files);

	}

	@Test
	public void testAvailableUpdateSitesChange() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();

		// add two official update sites
		applyOfficialUpdateSitesList(files, "a", "http://a.de/", "b", "http://b.de/");

		// apply changes to the list of available update sites
		applyOfficialUpdateSitesList(files, "a", "http://newa.de/", "b", "http://newb.de/");

		// restart
		files = readFromDb(files);

		// test if the update site URLs got updated
		assertEquals("http://newa.de/", files.getUpdateSite("a", true).getURL());
		assertEquals("http://newb.de/", files.getUpdateSite("b", true).getURL());

		cleanup(files);

	}

	@Test
	public void testLocalUpdateSiteURLModification() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();

		// add two official update sites
		applyOfficialUpdateSitesList(files, "a", "http://a.de/", "b", "http://b.de/");

		// set the URL of one update site and remember choice
		UpdateSite b = files.getUpdateSite("b", true);
		b.setURL("http://manuallymodified.de/");
		b.setKeepURL(true);

		// write and read the changes (= restart)
		files.write();
		files = readFromDb(files);

		// test whether the manually modified URL is returned by default
		assertEquals("http://manuallymodified.de/", files.getUpdateSite("b", true).getURL());

		// restart
		files = readFromDb(files);

		// apply changes to the URLs on the list of available update sites
		applyOfficialUpdateSitesList(files, "a", "http://newa.de/", "b", "http://newb.de/");

		// test whether the update site URLs got updated
		assertEquals("http://newa.de/", files.getUpdateSite("a", true).getURL());
		// test whether the manually modified update site URL was kept
		assertEquals("http://manuallymodified.de/", files.getUpdateSite("b", true).getURL());

		cleanup(files);

	}

	@Test
	public void testAutomaticSecureUpgrade() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();

		// skip test if HTTPS is not supported
		HTTPSUtil.checkHTTPSSupport(null);
		assumeTrue(HTTPSUtil.supportsHTTPS());

		// add two official update sites
		applyOfficialUpdateSitesList(files, "a", "http://sites.imagej.net/a/", "b", "http://other.server.net/b/");

		// test whether the HTTPS URL is used for sites on imagej.net
		assertEquals("https://sites.imagej.net/a/", files.getUpdateSite("a", true).getURL());
		assertEquals("http://other.server.net/b/", files.getUpdateSite("b", true).getURL());

		cleanup(files);
	}

	@Test
	public void testMirrorMainUpdateSite() throws Exception {

		String mirrorURL = "https://downloads.micron.ox.ac.uk/fiji_update/mirrors/imagej/";

		// load initial files collection
		FilesCollection files = initialize();

		// skip test if HTTPS is not supported
		HTTPSUtil.checkHTTPSSupport(null);
		assumeTrue(HTTPSUtil.supportsHTTPS());

		// apply mirror URL as source for main update site
		applyOfficialUpdateSitesList(files, "ImageJ", mirrorURL);

		// test whether the mirror URL is in use
		assertEquals(mirrorURL, files.getUpdateSite("ImageJ", true).getURL());

		// write and read the changes (= restart)
		files.write();
		files = readFromDb(files);

		// apply list of available update sites containing default ImageJ URL
		applyOfficialUpdateSitesList(files, "Image", "https://update.imagej.net/");

		// restart
		files = readFromDb(files);

		// test whether the mirror URL is still in use
		assertEquals(mirrorURL, files.getUpdateSite("ImageJ", true).getURL());

		cleanup(files);

	}

	@Test
	public void testOfficialFlag() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();

		// get list of available update sites
		AvailableSites.initializeAndAddSites(files);

		// test whether the update sites are marked as official
		files.getUpdateSites(true).forEach(updateSite -> assertTrue(updateSite.isOfficial()));

		// write and read the changes (= restart)
		files.write();
		files = readFromDb(files);

		// test whether the update sites are still marked as official
		files.getUpdateSites(true).forEach(updateSite -> assertTrue(updateSite.isOfficial()));

		cleanup(files);
	}

	@Test
	public void testActiveUpdateSitesChange() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();

		// add two official update sites
		applyOfficialUpdateSitesList(files, "a", "http://a.de/");

		files.getUpdateSite("a", true).setActive(true);

		// apply changes to the list of available update sites
		applyOfficialUpdateSitesList(files, "a", "http://newa.de/");

		// restart
		files = readFromDb(files);

		// test if the update site URLs got updated
		assertEquals("http://newa.de/", files.getUpdateSite("a", true).getURL());

		cleanup(files);

	}

	@Test
	public void testCommandLineRefreshUpdateSites() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();
		// there should be a main update site
		UpdateSite updateSite = files.getUpdateSite("ImageJ", true);
		updateSite.setActive(true);
		// the main update site should point to a local folder
		String oldLocalUrl = files.getUpdateSite("ImageJ", true).getURL();

		// when simulating refreshing the update sites, we can observe the changes
		System.out.println("ImageJ --update refresh-update-sites --simulate");
		files = main(files, "refresh-update-sites", "--simulate");
		// the main update site URL should still be the same
		assertEquals(oldLocalUrl, files.getUpdateSite("ImageJ", true).getURL());

		// refresh the URLs again, now forcing to update all URLs
		System.out.println("ImageJ --update refresh-update-sites --updateall");
		files = main(files, "refresh-update-sites", "--updateall");
		// the main update site should now point to the imagej.net server
		assertNotEquals(oldLocalUrl, files.getUpdateSite("ImageJ", true).getURL());
		assertFalse(files.getUpdateSite("ImageJ", true).shouldKeepURL());

		cleanup(files);
	}

	@Test
	public void testCommandLineRefreshInactiveUpdateSites() throws Exception {

		// load initial files collection
		FilesCollection files = initialize();
		// there should be a main update site
		UpdateSite updateSite = files.getUpdateSite("ImageJ", true);
		updateSite.setActive(false);
		// the main update site should point to a local folder
		String oldLocalUrl = files.getUpdateSite("ImageJ", true).getURL();

		// when simulating refreshing the update sites, we can observe the changes
		System.out.println("ImageJ --update refresh-update-sites --simulate");
		files = main(files, "refresh-update-sites", "--simulate");
		// the main update site URL should still be the same
		assertEquals(oldLocalUrl, files.getUpdateSite("ImageJ", true).getURL());

		// refresh the URLs again, now forcing to update all URLs
		System.out.println("ImageJ --update refresh-update-sites");
		files = main(files, "refresh-update-sites");
		// the main update site should now point to the imagej.net server
		assertNotEquals(oldLocalUrl, files.getUpdateSite("ImageJ", true).getURL());
		assertFalse(files.getUpdateSite("ImageJ", true).shouldKeepURL());

		cleanup(files);
	}

	@Test
	public void testURLFormattingChange() {
		UpdateSite updateSite = createOfficialSite("ImageJ", "https://sites.imagej.net/ImageJ/");
		Optional< URLChange > change =
				URLChange.create(updateSite, "https://sites.imagej.net/ImageJ");
		assertFalse(change.isPresent());
	}

	protected static FilesCollection readFromDb(final FilesCollection ijRoot) {
		FilesCollection files = new FilesCollection(ijRoot.prefix(""));
		try {
			files.read();
		} catch (IOException | ParserConfigurationException | SAXException e) {
			e.printStackTrace();
		}
		return files;
	}

	private void applyOfficialUpdateSitesList(FilesCollection files, String... args) {
		List< UpdateSite > availableSites = asListOfUpdateSites(args);
		List< URLChange > urlChanges = AvailableSites
				.initializeAndAddSites(files, availableSites);
		urlChanges.forEach(change -> change.setApproved(change.isRecommended()));
		AvailableSites.applySitesURLUpdates(files, urlChanges);
	}

	private List< UpdateSite > asListOfUpdateSites(String[] args) {
		assert(args.length % 2 == 0);
		List<UpdateSite> availableUpdateSites = new ArrayList<>();
		for (int i = 0; i < args.length-1; i+=2) {
			availableUpdateSites.add(createOfficialSite(args[i], args[i+1]));
		}
		return availableUpdateSites;
	}

	private UpdateSite createOfficialSite(String name, String url) {
		UpdateSite site = new UpdateSite(name, url, "", "", "", "", 0);
		site.setOfficial(true);
		site.setHost("file:localhost");
		return site;
	}
}
