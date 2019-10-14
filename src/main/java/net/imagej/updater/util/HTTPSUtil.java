package net.imagej.updater.util;

import net.imagej.updater.FilesCollection;
import net.imagej.updater.UpdateSite;
import org.scijava.log.LogService;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

public class HTTPSUtil {

	private static boolean secureMode = true;
	private static boolean offlineMode = false;

	private static final String secureURL = "https://imagej.net/api.php";
	private static final String insecureUserSiteURL = "http://sites.imagej.net";
	private static final String secureUserSiteURL = "https://sites.imagej.net";

	/**
	 * Calls {@link #secureURL} to check whether the HTTPS certificate can be handled by the JVM.
	 */
	public static void checkHTTPSSupport(LogService log) {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(secureURL).openConnection();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			connection.setRequestMethod("HEAD");
			connection.setConnectTimeout(10000);
		} catch (ProtocolException e) {
			e.printStackTrace();
		}
		try {
			connection.getResponseCode();
			secureMode = true;
		} catch (UnknownHostException e) {
			String msg = "Could not determine the IP address of https://imagej.net. "
					+ "Make sure you are connected to a network.";
			if (log != null) log.warn(msg);
			else System.out.println("[WARNING] " + msg);
			offlineMode = true;
		} catch (SSLHandshakeException e) {
			secureMode = false;
			String msg = "Your Java might be too old to handle updates via HTTPS. This is a security risk. " +
					"Depending on your setup please download a recent version of this software or update your local Java installation.";
			if (log != null) log.warn(msg);
			else System.out.println("[WARNING] " + msg);
		} catch (SocketTimeoutException e) {
			secureMode = false;
			String msg = "Timeout while trying to update securely via HTTPS. Will fall back to HTTP. This is a security risk. " +
					"Please contact your system administrator to enable communication via HTTPS.";
			if (log != null) log.warn(msg);
			else System.out.println("[WARNING] " + msg);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @return whether this ImageJ instance can handle HTTPS
	 */
	public static boolean supportsHTTPS() {
		return secureMode;
	}

	public static boolean noConnection() {
		return offlineMode;
	}

	/**
	 * @return the protocol component of the URL depending on whether this ImageJ instance can handle HTTPS
	 */
	public static String getProtocol() {
		return secureMode ? "https://" : "http://";
	}

	/**
	 * This method returns ImageJ user site URLs in the correct protocol (HTTPS or HTTPS), depending on what's supported.
	 * In case the site is not an ImageJ user site, the original url will be returned.
	 */
	public static String fixImageJUserSiteProtocol(final String url) {
		if(!supportsHTTPS() && isHTTPSUserSiteURL(url)) {
			return userSiteConvertToHTTP(url);
		}
		if(supportsHTTPS() && isHTTPUserSiteURL(url)) {
			return userSiteConvertToHTTPS(url);
		}
		return url;
	}

	private static boolean isHTTPUserSiteURL(String url) {
		return url.startsWith(insecureUserSiteURL);
	}

	private static boolean isHTTPSUserSiteURL(String url) {
		return url.startsWith(secureUserSiteURL);
	}

	public static boolean supportsURLProtocol(String url) {
		if(!url.startsWith(secureUserSiteURL)) return true;
		return supportsHTTPS();
	}

	public static String userSiteConvertToHTTP(String url) {
		return url.replace(secureUserSiteURL, insecureUserSiteURL);
	}

	private static String userSiteConvertToHTTPS(String url) {
		return url.replace(insecureUserSiteURL, secureUserSiteURL);
	}

	public static boolean hasImageJUserSiteProtocolUpdates(FilesCollection plugins) {
		if(supportsHTTPS()) {
			for(UpdateSite site : plugins.getUpdateSites(false)) {
				if(site.getURL().startsWith(insecureUserSiteURL)) return true;
			}
		} else {
			for(UpdateSite site : plugins.getUpdateSites(false)) {
				if(site.getURL().startsWith(secureUserSiteURL)) return true;
			}
		}
		return false;
	}
}