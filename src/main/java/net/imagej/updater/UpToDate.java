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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Enumeration;

import javax.xml.parsers.ParserConfigurationException;

import net.imagej.updater.util.AvailableSites;
import net.imagej.updater.util.HTTPSUtil;
import net.imagej.updater.util.UpdaterUtil;

import org.scijava.util.AppUtils;
import org.scijava.util.Prefs;
import org.xml.sax.SAXException;

/**
 * This class helps to determine the status of the current ImageJ installation.
 * Use it to find out whether there are updates available, whether we're
 * offline, etc.
 * 
 * @author Johannes Schindelin
 */
public class UpToDate {

	public enum Result {
		UP_TO_DATE,
		UPDATEABLE,
		PROXY_NEEDS_AUTHENTICATION,
		OFFLINE,
		REMIND_LATER,
		CHECK_TURNED_OFF,
		READ_ONLY,
		UPDATES_MANAGED_DIFFERENTLY, // e.g. Debian packaging
		DEVELOPER,
		PROTECTED_LOCATION,          // e.g. "C:\Program Files" on Windows
		GPR_ACTIVATED                // Gatekeeper Path Randomization on macOS
	}

	private final static String KEY = "latestNag";
	private final static long REMINDER_INTERVAL = 86400; // 24h
	private final static long FOUR_O_SEVEN = -111381;

	/**
	 * Check the update status of the current ImageJ installation.
	 * 
	 * @return the status
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public static Result check() throws IOException,
		ParserConfigurationException, SAXException
	{
		return check(AppUtils.getBaseDirectory("ij.dir", UpToDate.class, "updater"));
	}

	/**
	 * Check the update status of a given ImageJ installation.
	 * 
	 * @param ijRoot the root directory of the ImageJ installation to check
	 * @return the status
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public static Result check(final File ijRoot) throws IOException,
		ParserConfigurationException, SAXException
	{
		if (neverRemind()) return Result.CHECK_TURNED_OFF;
		if (shouldRemindLater()) return Result.REMIND_LATER;
		if (UpdaterUtil.isGPRActivated(ijRoot)) return Result.GPR_ACTIVATED;
		if (UpdaterUtil.isProtectedLocation(ijRoot)) return Result.PROTECTED_LOCATION;
		if (!canWrite(ijRoot)) return Result.READ_ONLY;
		if (isDeveloper()) return Result.DEVELOPER;
		if (!haveNetworkConnection()) return Result.OFFLINE;
		final FilesCollection plugins = new FilesCollection(ijRoot);
		try {
			try {
				plugins.read();
			}
			catch (final FileNotFoundException e) { /* ignore */}
			HTTPSUtil.checkHTTPSSupport(null);
			if(HTTPSUtil.noConnection()) {
				return Result.OFFLINE;
			}
			if(AvailableSites.hasUpdateSiteURLUpdates(plugins)) {
				return Result.UPDATEABLE;
			}
			for (final String name : plugins.getUpdateSiteNames(false)) {
				final UpdateSite updateSite = plugins.getUpdateSite(name, true);
				final long lastModified =
					getLastModified(updateSite.getURL() + UpdaterUtil.XML_COMPRESSED);
				if (lastModified == FOUR_O_SEVEN) return Result.PROXY_NEEDS_AUTHENTICATION;
				if (lastModified < 0) return Result.OFFLINE; // assume network is down
				if (!updateSite.isLastModified(lastModified)) {
					setLatestNag();
					return Result.UPDATEABLE;
				}
			}
		}
		catch (final FileNotFoundException  | UnknownHostException e) {
			/*
			 * Ignore when it is a temporary failure, or even when the site went away:
			 * this is just an up-to-date-check, nothing more.
			 */
		}
		setLatestNag(-1);
		return Result.UP_TO_DATE;
	}

	public static long now() {
		return new Date().getTime() / 1000;
	}

	/**
	 * @return whether the user specified not to be reminded again
	 */
	public static boolean neverRemind() {
		final String latestNag = Prefs.get(UpToDate.class, KEY);
		if (latestNag == null || latestNag.equals("")) return false;
		final long time = Long.parseLong(latestNag);
		return time == Long.MAX_VALUE;
	}

	/**
	 * @return whether the user said that she wanted to be reminded later
	 */
	public static boolean shouldRemindLater() {
		final String latestNag = Prefs.get(UpToDate.class, KEY);
		if (latestNag == null || latestNag.equals("")) return false;
		return now() - Long.parseLong(latestNag) < REMINDER_INTERVAL;
	}

	/**
	 * @return whether we started in a developer setting (i.e., not using the
	 *         Launcher, which sets the imagej.dir property)
	 */
	public static boolean isDeveloper() {
		return System.getProperty("imagej.dir") == null;
	}

	/**
	 * @param ijRoot the root directory to test
	 * @return whether we can write to the ImageJ directory
	 */
	public static boolean canWrite(final File ijRoot) {
		return ijRoot.canWrite();
	}

	/**
	 * @return whether we have a real network connection at the moment (not just
	 *         localhost)
	 */
	public static boolean haveNetworkConnection() {
		try {
			final Enumeration<NetworkInterface> ifaces =
				NetworkInterface.getNetworkInterfaces();
			while (ifaces.hasMoreElements()) {
				final Enumeration<InetAddress> addresses =
					ifaces.nextElement().getInetAddresses();
				while (addresses.hasMoreElements())
					if (!addresses.nextElement().isLoopbackAddress()) return true;
			}
		}
		catch (final SocketException e) { /* ignore */ }
		return false;
	}

	/**
	 * @param url the URL to access
	 * @return the mtime (see {@link File#lastModified()})
	 */
	public static long getLastModified(final String url) {
		try {
			final URLConnection connection = UpdaterUtil.openConnection(new URL(url));
			if (connection instanceof HttpURLConnection) ((HttpURLConnection) connection)
				.setRequestMethod("HEAD");
			connection.setUseCaches(false);
			final long lastModified = connection.getLastModified();
			connection.getInputStream().close();
			return lastModified;
		}
		catch (final IOException e) {
			if (e.getMessage().startsWith("Server returned HTTP response code: 407")) return FOUR_O_SEVEN;
			// assume no network; so let's pretend everything's ok.
			return -1;
		}
	}

	/**
	 * Remember that we just nagged the user about an update.
	 */
	public static void setLatestNag() {
		setLatestNag(now());
	}

	/**
	 * Remember when we last nagged the user about an update.
	 * 
	 * @param ticks
	 */
	public static void setLatestNag(final long ticks) {
		Prefs.put(UpToDate.class, KEY, ticks < 0 ? "" : ("" + ticks));
	}
}
