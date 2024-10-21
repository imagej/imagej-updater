/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2024 Board of Regents of the University of
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

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.scijava.log.LogService;
import org.scijava.log.StderrLogService;

/**
 * Utility methods for use with the {@link net.imagej.updater} package and
 * subpackages.
 * <p>
 * Class functionality:
 * <ul>
 * <li>Calculate timestamps of files</li>
 * <li>Calculate the checksums of files</li>
 * <li>Get the absolute path (prefix()) of the ImageJ directory</li>
 * <li>Copy a file over to a particular location</li>
 * <li>Get details of the Operating System ImageJ application is on</li>
 * </ul>
 * 
 * @author Johannes Schindelin
 */
public class UpdaterUtil {

	public static String MAIN_URL = HTTPSUtil.getProtocol() + "update.imagej.net/";
	public static String UPDATE_DIRECTORY = "/home/imagej/update-site";
	public static String SSH_HOST = "update.imagej.net";

	public static final String XML_COMPRESSED = "db.xml.gz";

	// Prefix for the preference key names
	public static final String PREFS_USER = "imagej.updater.login";

	public final static String macPrefix = "Contents/MacOS/";

	private static final Map<String, String> launcherToPlatform;

	public final String platform;
	public final String[] platforms, launchers;
	protected final Set<String> updateablePlatforms;

	static {
		launcherToPlatform = new HashMap<>();
		// Jaunch: https://github.com/apposed/jaunch
		launcherToPlatform.put("Contents/MacOS/fiji-macos-arm64", "macos-arm64");
		launcherToPlatform.put("Contents/MacOS/fiji-macos-x64", "macosx");
		launcherToPlatform.put("Contents/MacOS/fiji-macos-universal", "macos-arm64");
		launcherToPlatform.put("Contents/MacOS/jaunch-macos-arm64", "macos-arm64");
		launcherToPlatform.put("Contents/MacOS/jaunch-macos-x64", "macosx");
		launcherToPlatform.put("Contents/MacOS/jaunch-macos-universal", "macos-arm64");
		launcherToPlatform.put("fiji-linux-arm64", "linux-arm64");
		launcherToPlatform.put("fiji-linux-x64", "linux64");
		launcherToPlatform.put("fiji-windows-arm64.exe", "win-arm64");
		launcherToPlatform.put("fiji-windows-x64.exe", "win64");
		launcherToPlatform.put("config/jaunch/jaunch-linux-arm64", "linux-arm64");
		launcherToPlatform.put("config/jaunch/jaunch-linux-x64", "linux64");
		launcherToPlatform.put("config/jaunch/jaunch-windows-arm64.exe", "win-arm64");
		launcherToPlatform.put("config/jaunch/jaunch-windows-x64.exe", "win64");
		// ImageJ Launcher: https://github.com/imagej/imagej-launcher
		launcherToPlatform.put("ImageJ-linux32", "linux32");
		launcherToPlatform.put("ImageJ-linux64", "linux64");
		launcherToPlatform.put("Contents/MacOS/ImageJ-macosx", "macosx");
		launcherToPlatform.put("Contents/MacOS/ImageJ-tiger", "macosx");
		launcherToPlatform.put("ImageJ-win32.exe", "win32");
		launcherToPlatform.put("ImageJ-win64.exe", "win64");
	}

	protected final DropboxURLMapper dropboxURLMapper;

	public UpdaterUtil(final File imagejRoot) {
		platform = getPlatform();

		// These platform names determine what can be supported in the updater
		platforms = new HashSet<>(launcherToPlatform.values()).toArray(new String[0]);

		launchers = launcherToPlatform.keySet().toArray(new String[0]);
		Arrays.sort(launchers);

		// Note: A platform is "updateable" if at least one
		// launcher for that platform is currently installed.
		updateablePlatforms = new HashSet<>();
		updateablePlatforms.add(platform);
		if (imagejRoot != null) {
			for (Map.Entry<String, String> lap : launcherToPlatform.entrySet()) {
				if (new File(imagejRoot, lap.getKey()).exists()) {
					updateablePlatforms.add(lap.getValue());
				}
			}
		}
		dropboxURLMapper = new DropboxURLMapper(this);
	}

	public static String platformForLauncher(final String filename) {
		return launcherToPlatform.get(filename);
	}

	public static String stripSuffix(final String string, final String suffix) {
		if (!string.endsWith(suffix)) return string;
		return string.substring(0, string.length() - suffix.length());
	}

	public static String stripPrefix(final String string, final String prefix) {
		if (!string.startsWith(prefix)) return string;
		return string.substring(prefix.length());
	}

	public static String getPlatform() {
		final String osArch = System.getProperty("os.arch", "");
		final boolean is64bit = osArch.indexOf("64") >= 0;
		final String osName = System.getProperty("os.name", "<unknown>");
		if (osName.equals("Linux")) return "linux" + (is64bit ? "64" : "32");
		if (osName.equals("Mac OS X")) return osArch.equals("aarch64") ? "macos-arm64" : "macosx";
		if (osName.startsWith("Windows")) return "win" + (is64bit ? "64" : "32");
		// System.err.println("Unknown platform: " + osName);
		return osName.toLowerCase();
	}

	// get digest of the file as according to fullPath
	public static String getDigest(final String path, final File file)
		throws NoSuchAlgorithmException, FileNotFoundException, IOException,
		UnsupportedEncodingException
	{
		if (path.endsWith(".jar")) return getJarDigest(file);
		final MessageDigest digest = getDigest();
		digest.update(path.getBytes("ASCII"));
		if (file != null) updateDigest(new FileInputStream(file), digest);
		return toHex(digest.digest());
	}

	public static MessageDigest getDigest() throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-1");
	}

	/**
	 * Handle previous methods to calculate the checksums gracefully Earlier, we
	 * simply checksummed all the contents in .jar files. But that leads to files
	 * marked as modified all the time when Maven rebuilds them. So we changed the
	 * way they are checksummed. Let's handle old checksums, too, though, by
	 * simply adding them to the previous versions.
	 * 
	 * @param path the path relative to the ImageJ directory
	 * @param file the file
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	public static List<String> getObsoleteDigests(final String path,
		final File file) throws FileNotFoundException, IOException
	{
		if (!path.endsWith(".jar")) return null;
		final List<String> result = new ArrayList<>();
		result.add(getJarDigest(file, true, true, false));
		result.add(getJarDigest(file, true, false, false));
		result.add(getJarDigest(file, false, false, false));
		return result;
	}

	public static void updateDigest(final InputStream input,
		final MessageDigest digest) throws IOException
	{
		final byte[] buffer = new byte[65536];
		final DigestInputStream digestStream = new DigestInputStream(input, digest);
		while (digestStream.read(buffer) >= 0); /* do nothing */
		digestStream.close();
	}

	public final static char[] hex = { '0', '1', '2', '3', '4', '5', '6', '7',
		'8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

	public static String toHex(final byte[] bytes) {
		final char[] buffer = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			buffer[i * 2] = hex[(bytes[i] & 0xf0) >> 4];
			buffer[i * 2 + 1] = hex[bytes[i] & 0xf];
		}
		return new String(buffer);
	}

	public static String getJarDigest(final File file) throws FileNotFoundException, IOException {
		return getJarDigest(file, true, true, true);
	}

	public static String getJarDigest(final File file, boolean treatPropertiesSpecially, boolean treatManifestsSpecially) throws FileNotFoundException, IOException {
		return getJarDigest(file, treatPropertiesSpecially, treatManifestsSpecially, false);
	}

	public static String getJarDigest(final File file, boolean treatPropertiesSpecially, boolean treatManifestsSpecially, boolean keepOnlyMainClassInManifest) throws FileNotFoundException, IOException {
		MessageDigest digest = null;
		try {
			digest = getDigest();
		}
		catch (final NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}

		if (file != null) {
			final JarFile jar = new JarFile(file);
			final List<JarEntry> list = Collections.list(jar.entries());
			Collections.sort(list, new JarEntryComparator());

			for (final JarEntry entry : list) {
				digest.update(entry.getName().getBytes("ASCII"));
				InputStream inputStream = jar.getInputStream(entry);
				// .properties files have a date in a comment; let's ignore this for the checksum
				// For backwards-compatibility, activate the .properties mangling only from June 15th, 2012
				if (treatPropertiesSpecially && entry.getName().endsWith(".properties")) {
					inputStream = new SkipHashedLines(inputStream);
				}
				// same for manifests, but with July 6th, 2012
				if (treatManifestsSpecially && entry.getName().equals("META-INF/MANIFEST.MF")) {
					inputStream = new FilterManifest(inputStream, keepOnlyMainClassInManifest);
				}
				updateDigest(inputStream, digest);
			}
			jar.close();
		}
		return toHex(digest.digest());
	}

	private static class JarEntryComparator implements Comparator<JarEntry> {

		@Override
		public int compare(final JarEntry entry1, final JarEntry entry2) {
			final String name1 = entry1.getName();
			final String name2 = entry2.getName();
			return name1.compareTo(name2);
		}

	}

	public static long currentTimestamp() {
		return Long.parseLong(UpdaterUtil.timestamp(Calendar.getInstance()));
	}

	public static long getTimestamp(final File file) {
		final long modified = file.lastModified();
		return Long.parseLong(timestamp(modified));
	}

	public static String timestamp(final long millis) {
		final Calendar date = Calendar.getInstance();
		date.setTimeInMillis(millis);
		return timestamp(date);
	}

	public static String timestamp(final Calendar date) {
		final DecimalFormat format = new DecimalFormat("00");
		final int month = date.get(Calendar.MONTH) + 1;
		final int day = date.get(Calendar.DAY_OF_MONTH);
		final int hour = date.get(Calendar.HOUR_OF_DAY);
		final int minute = date.get(Calendar.MINUTE);
		final int second = date.get(Calendar.SECOND);
		return "" + date.get(Calendar.YEAR) + format.format(month) +
			format.format(day) + format.format(hour) + format.format(minute) +
			format.format(second);
	}

	public static long timestamp2millis(final long timestamp) {
		return timestamp2millis("" + timestamp);
	}

	public static long timestamp2millis(final String timestamp) {
		final Calendar calendar = Calendar.getInstance();
		calendar.set(Integer.parseInt(timestamp.substring(0, 4)), Integer
			.parseInt(timestamp.substring(4, 6)) - 1, Integer.parseInt(timestamp
			.substring(6, 8)), Integer.parseInt(timestamp.substring(8, 10)), Integer
			.parseInt(timestamp.substring(10, 12)), Integer.parseInt(timestamp
			.substring(12, 14)));
		return calendar.getTimeInMillis();
	}

	private static final String[] months = { "Zero", "Jan", "Feb", "Mar", "Apr",
		"May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };

	public static String prettyPrintTimestamp(final long timestamp) {
		final String t = "" + timestamp + "00000000000000";
		return t.substring(6, 8) + " " +
			months[Integer.parseInt(t.substring(4, 6))] + " " + t.substring(0, 4) + " " +
			t.substring(8, 10) + ":" + t.substring(10, 12) + ":" + t.substring(12,14);
	}

	public boolean isLauncher(final String filename) {
		return Arrays.binarySearch(launchers, filename) >= 0;
	}

	public boolean isUpdateablePlatform(final String platform) {
		return updateablePlatforms.contains(platform);
	}

	public void setUpdateablePlatforms(final String... platforms) {
		updateablePlatforms.clear();
		for (String platform : platforms) {
			if (platform == null) continue;
			platform = platform.trim();
			if ("all".equals(platform)) {
				updateablePlatforms.add(this.platform);
				updateablePlatforms.addAll(Arrays.asList(this.platforms));
			} else {
				updateablePlatforms.add(platform);
			}
		}
	}

	public boolean isMacOSX() {
		return platform.equals("macosx");
	}

	public static <T> String join(final String delimiter, final Iterable<T> list)
	{
		final StringBuilder builder = new StringBuilder();
		for (final T object : list)
			builder.append((builder.length() > 0 ? delimiter : "") + object.toString());
		return builder.toString();
	}

	public static void useSystemProxies() {
		/*
		 * Avoid those pesky
		 * "GConf-WARNING **: Client failed to connect to the D-BUS daemon"
		 * messages in headless mode (e.g. Jenkins).
		 */
		final String osName = System.getProperty("os.name", "<unknown>");
		if (osName.equals("Linux") && GraphicsEnvironment.isHeadless()) return;

		System.setProperty("java.net.useSystemProxies", "true");
	}

	@Deprecated
	public static long getLastModified(final String url) {
		try {
			return new UpdaterUtil(null).getLastModified(new URL(url));
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}

	public long getLastModified(final URL url) {
		try {
			final URLConnection connection = openConnection(url);
			if (connection instanceof HttpURLConnection) ((HttpURLConnection) connection)
				.setRequestMethod("HEAD");
			connection.setUseCaches(false);
			final long lastModified = connection.getLastModified();
			connection.getInputStream().close();
			return lastModified;
		}
		catch (final IOException e) {
			if (e.getMessage().startsWith("Server returned HTTP response code: 407")) return -111381;
			if (e.getMessage().startsWith("Server returned HTTP response code: 405")) try {
				final URLConnection connection = openConnection(url);
				connection.setUseCaches(false);
				final long lastModified = connection.getLastModified();
				connection.getInputStream().close();
				return lastModified;
			} catch (IOException e2) {
				e.printStackTrace();
				e2.printStackTrace();
			}
			// assume no network; so let's pretend everything's ok.
			return -1;
		}
	}

	/**
	 * Open a stream to a {@link URL}.
	 * 
	 * ... possibly mapping Dropbox URLs.
	 * 
	 * @param url the URL to open
	 */
	public InputStream openStream(final URL url) throws IOException {
		return openConnection(url).getInputStream();
	}

	/**
	 * Open a connection to a {@link URL}.
	 * 
	 * ... possibly mapping Dropbox URLs.
	 * 
	 * @param url the URL to open
	 */
	public URLConnection openConnection(final URL url) throws IOException {
		final URLConnection connection = dropboxURLMapper.get(url).openConnection();
		if (connection instanceof HttpURLConnection) {
			HttpURLConnection http = (HttpURLConnection)connection;
			http.setInstanceFollowRedirects(true); // Follow HTTP 3xx redirects.
			final String javaVmVersion = System.getProperty("java.runtime.version");
			final String javaVersion = javaVmVersion != null ?
					javaVmVersion : System.getProperty("java.version");
			final String osVersion = System.getProperty("os.version");
			final String os = "" + System.getProperty("os.name") + "-"
					+ (osVersion != null ? osVersion + "-" : "")
					+ System.getProperty("os.arch");
			http.setRequestProperty("User-Agent",
					"curl/7.22.0 compatible ImageJ updater/2.0.0-SNAPSHOT (Java "
					+ javaVersion + "/" + os + ")");
		}
		return connection;
	}

	// Get entire byte data
	public static byte[] readStreamAsBytes(final InputStream input)
		throws IOException
	{
		byte[] buffer = new byte[1024];
		int offset = 0, len = 0;
		for (;;) {
			if (offset == buffer.length) buffer = realloc(buffer, 2 * buffer.length);
			len = input.read(buffer, offset, buffer.length - offset);
			if (len < 0) return realloc(buffer, offset);
			offset += len;
		}
	}

	private static byte[] realloc(final byte[] buffer, final int newLength) {
		if (newLength == buffer.length) return buffer;
		final byte[] newBuffer = new byte[newLength];
		System.arraycopy(buffer, 0, newBuffer, 0, Math
			.min(newLength, buffer.length));
		return newBuffer;
	}

	protected static String readFile(final File file) throws IOException {
		final StringBuilder builder = new StringBuilder();
		final BufferedReader reader = new BufferedReader(new FileReader(file));
		for (;;) {
			final String line = reader.readLine();
			if (line == null) break;
			builder.append(line).append('\n');
		}
		reader.close();

		return builder.toString();
	}

	// This method writes to a .bup file and then renames; this might not work on
	// Windows
	protected static void writeFile(final File file, final String contents)
		throws IOException
	{
		final File result =
			new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".new");
		final FileOutputStream out = new FileOutputStream(result);
		out.write(contents.getBytes());
		out.close();
		result.renameTo(file);
	}

	// This method protects us from Java5's absence of File.canExecute
	public static boolean canExecute(File file) {
		try {
			return file.canExecute();
		} catch (Throwable t) {
			// ignore
			return false;
		}
	}

	/**
	 * Determines whether the ImageJ root directory has been moved to an area
	 * dictated by macOS's Gatekeeper Path Randomization feature. This happens
	 * when the application is downloaded, unpacked, and launched without first
	 * moving the application to a different folder such as Applications.
	 * 
	 * @param ijRoot the root directory to test
	 * @return whether the directory is protected by GPR
	 */
	public static boolean isGPRActivated(final File ijRoot) {
		final String path = ijRoot.getAbsolutePath();
		return path.matches("^/private/var/folders/.*/AppTranslocation/.*");
	}

	private static Set<File> protectedFiles;
	private final static Pattern majorVersionPattern = Pattern.compile("([0-9]+).*");

	/**
	 * Determines whether the ImageJ root directory is in an area protected by the OS.
	 * 
	 * <p>On Windows Vista and later, C:\Program Files is a protected location.
	 * 
	 * @param ijRoot the root directory to test
	 * @return whether the directory is protected by the OS
	 */
	public static boolean isProtectedLocation(final File ijRoot) {
		if (getPlatform().startsWith("win")) {
			final String osVersion = System.getProperty("os.version");
			if (osVersion == null) return false;
			final Matcher matcher = majorVersionPattern.matcher(osVersion);
			/*
			 * Vista is 6.0, Server 2008, too, to keep it confusing, Server 2008
			 * R2 is 6.1, to keep it more confusing, Windows 7 is 6.1 (just to
			 * keep it even more confusing). See:
			 * http://msdn.microsoft.com/en-us/library/windows/desktop/ms724832%28v=vs.85%29.aspx
			 */
			if (!matcher.matches() || Integer.parseInt(matcher.group(1)) < 6) return false;
			try {
				if (protectedFiles == null) {
					protectedFiles = new HashSet<>();
					for (final String key : new String[] {
									"PROGRAMFILES", "PROGRAMFILES(X86)", "SystemRoot", "ALLUSERSPROFILE"
					}) {
						final String path = System.getenv(key);
						if (path != null) {
							File f = new File(path).getCanonicalFile();
							if (!f.canWrite()) protectedFiles.add(f);
						}
					}
				}
				for (File dir = ijRoot.getCanonicalFile(); dir != null; dir = dir.getParentFile()) {
					if (protectedFiles.contains(dir)) {
						protectedFiles.add(ijRoot);
						return true;
					}
				}
			} catch (final IOException e) {
				e.printStackTrace(); // but ignore otherwise
			}
		}
		return false;
	}

	public static boolean patchInfoPList(final File infoPList, final String executable)
		throws IOException
	{
		if (!infoPList.exists()) return false;
		String contents = readFile(infoPList);
		final Pattern pattern =
			Pattern.compile(".*<key>CFBundleExecutable</key>[^<]*<string>([^<]*).*",
				Pattern.DOTALL | Pattern.MULTILINE);
		final Matcher matcher = pattern.matcher(contents);
		if (!matcher.matches()) return false;
		contents =
			contents.substring(0, matcher.start(1)) + executable +
				contents.substring(matcher.end(1));
		writeFile(infoPList, contents);
		return true;
	}

	public static<T> Iterable<T> iterate(final Enumeration<T> en) {
        final Iterator<T> iterator = new Iterator<T>() {
            @Override
			public boolean hasNext() {
                    return en.hasMoreElements();
            }

            @Override
			public T next() {
                    return en.nextElement();
            }

            @Override
			public void remove() {
                    throw new UnsupportedOperationException();
            }
        };

	    return new Iterable<T>() {
            @Override
			public Iterator<T> iterator() {
                    return iterator;
            }
	    };
	}

	/**
	 * Get a log service.
	 * 
	 * This works around version skews (where the
	 * {@link org.scijava.log.StderrLogService} cannot be found).
	 * 
	 * @return the log service
	 */
	public static LogService getLogService() {
		return new StderrLogService();
	}

	/**
	 * CamelCases a {@link String}.
	 */
	public static String toCamelCase(final String string) {
		if (string == null || string.length() == 0) return string;
		final StringBuilder builder = new StringBuilder();
		boolean upCase = true;
		for (char ch : string.toCharArray()) {
			if (Character.isLetterOrDigit(ch)) {
				builder.append(upCase ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
				upCase = false;
			} else {
				upCase = true;
			}
		}
		return builder.toString();
	}

}
