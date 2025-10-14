/*-
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

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Platforms {

	private static final Map<String, String> LAUNCHERS;
	private static final List<String> PLATFORMS;

	static {
		LAUNCHERS = new HashMap<>();
		// Jaunch: https://github.com/apposed/jaunch
		LAUNCHERS.put("fiji-linux-arm64", "linux-arm64");
		LAUNCHERS.put("fiji-linux-x64", "linux64");
		LAUNCHERS.put("fiji-windows-arm64.exe", "win-arm64");
		LAUNCHERS.put("fiji-windows-x64.exe", "winx");
		LAUNCHERS.put("config/jaunch-linux-arm64", "linux-arm64");
		LAUNCHERS.put("config/jaunch-linux-x64", "linux64");
		LAUNCHERS.put("config/jaunch-windows-arm64.exe", "win-arm64");
		LAUNCHERS.put("config/jaunch-windows-x64.exe", "winx");
		// Deprecated: previous nested Jaunch config directory.
		LAUNCHERS.put("config/jaunch/jaunch-linux-arm64", "linux-arm64");
		LAUNCHERS.put("config/jaunch/jaunch-linux-x64", "linux64");
		LAUNCHERS.put("config/jaunch/jaunch-windows-arm64.exe", "win-arm64");
		LAUNCHERS.put("config/jaunch/jaunch-windows-x64.exe", "winx");
		// Deprecated ImageJ Launcher: https://github.com/imagej/imagej-launcher
		LAUNCHERS.put("ImageJ-linux32", "linux32");
		LAUNCHERS.put("ImageJ-linux64", "linux64");
		LAUNCHERS.put("Contents/MacOS/ImageJ-macosx", "macosx");
		LAUNCHERS.put("Contents/MacOS/ImageJ-tiger", "macosx");
		LAUNCHERS.put("ImageJ-win32.exe", "win32");
		LAUNCHERS.put("ImageJ-win64.exe", "win64");

		// Note: All files within toplevel .app folders also count as launchers,
		// so that e.g. Fiji.app stays together; see #platformForLauncher(String)
		// and #isLauncher(String) methods below.

		// Supported platforms:
		// - linux64     = Linux x64
		// - linux-arm64 = Linux arm64
		// - linuxx      = Linux, all architectures
		// - macos64     = macOS x64 (i.e. Intel)
		// - macos-arm64 = macOS arm64 (i.e. Apple Silicon)
		// - macosx      = macOS, all architectures
		// - win64       = Windows x64
		// - win-arm64   = Windows arm64 (e.g. some Surface Pros)
		// - winx        = Windows, all architectures
		//
		// Platforms ending in "x" are platform groups, meaning files associated
		// with them will match that operating system regardless of architecture:
		//
		// * All macOS-specific launchers are assigned to the macosx platform
		//   group, so that they ship with all macOS installations. We do this so
		//   that .app launcher folders do not get split up even when containing
		//   binaries for different architectures, and so that Apple Silicon Macs
		//   still have the Intel/x86-64 binaries available in case they want to
		//   launch in x86 emulation mode via Rosetta.
		//
		// * The jaunch-windows-x64.exe configurator is marked winx because it is
		//   also used for Windows arm64 launches: Kotlin Native does not yet
		//   support Windows arm64 as a target, so the Jaunch launcher invokes the
		//   x64 configurator instead, which works transparently via Windows ARM's
		//   Prism emulation layer.
		//
		// * The fiji-windows-x64.exe launcher is marked winx so that Windows ARM
		//   systems can easily launch in emulated x86 mode if desired.

		final Set<String> platformSet = new HashSet<>(LAUNCHERS.values());
		for (String arch : Arrays.asList("64", "-arm64", "x")) {
			for (String os : Arrays.asList("linux", "macos", "win")) {
				platformSet.add(os + arch);
			}
		}
		PLATFORMS = new ArrayList<>(platformSet);
		Collections.sort(PLATFORMS);
	}

	private Platforms() {
		// Prevent instantiation of utility class.
	}

	// -- Static utility methods --

	public static List<String> known() {
		return Collections.unmodifiableList(PLATFORMS);
	}

	public static Collection<String> launchers() {
		return Collections.unmodifiableSet(LAUNCHERS.keySet());
	}

	public static String platformForLauncher(final String filename) {
		final int slash = filename.indexOf("/");
		if (slash >= 0 && filename.substring(0, slash).endsWith(".app")) {
			// This file resides inside a macOS-style .app folder.
			return "macosx";
		}
		return LAUNCHERS.get(filename);
	}

	public static boolean matches(final String platform, final String candidate) {
		return platform.equals(candidate) ||
			("linuxx".equals(candidate) && isLinux(platform)) ||
			("macosx".equals(candidate) && isMac(platform)) ||
			("winx".equals(candidate) && isWindows(platform));
	}

	public static boolean matches(final String platform, final Collection<String> candidates) {
		if (candidates.isEmpty()) return true; // targets all platforms
		return candidates.stream().anyMatch(c -> matches(platform, c));
	}

	public static boolean matches(final Collection<String> platforms, final String candidate) {
		return platforms.stream().anyMatch(p -> matches(p, candidate));
	}

	public static boolean isWindows(String platform) {
		return platform.startsWith("win");
	}

	public static boolean isMac(String platform) {
		return platform.startsWith("macos");
	}

	public static boolean isLinux(String platform) {
		return platform.startsWith("linux");
	}

	/**
	 * Gets the short platform name for the currently running platform,
	 * including both operating system and CPU architecture.
	 * <table>
	 *   <caption>Supported platforms</caption>
	 *   <tr><th>Platform name</th><th>Operating system</th><th>Architecture</th></tr>
	 *   <tr><td>linux-arm64</td>  <td>Linux</td>           <td>arm64</td></tr>
	 *   <tr><td>linux32</td>      <td>Linux</td>           <td>x86-32</td></tr>
	 *   <tr><td>linux64</td>      <td>Linux</td>           <td>x86-64</td></tr>
	 *   <tr><td>macos-arm64</td>  <td>macOS</td>           <td>arm64</td></tr>
	 *   <tr><td>macos64</td>      <td>macOS</td>           <td>x86-64</td></tr>
	 *   <tr><td>win-arm64</td>    <td>Windows</td>         <td>arm64</td></tr>
	 *   <tr><td>win32</td>        <td>Windows</td>         <td>x86-32</td></tr>
	 *   <tr><td>win64</td>        <td>Windows</td>         <td>x86-64</td></tr>
	 * </table>
	 */
	public static String current() {
		final String osName = System.getProperty("os.name")
			.toLowerCase().replaceAll("[^a-z0-9_-]", "");
		final String osArch = System.getProperty("os.arch");

		final String os;
		if (osName.startsWith("linux")) os = "linux";
		else if (osName.startsWith("mac")) os = "macos";
		else if (osName.startsWith("win")) os = "win";
		else os = osName;

		final HashMap<String, String> archMap = new HashMap<>();
		archMap.put("aarch32", "-arm32");
		archMap.put("aarch64", "-arm64");
		archMap.put("i386", "32");
		archMap.put("i486", "32");
		archMap.put("i586", "32");
		archMap.put("i686", "32");
		archMap.put("x86", "32");
		archMap.put("x86-32", "32");
		archMap.put("x86_32", "32");
		archMap.put("amd64", "64");
		archMap.put("x64", "64");
		archMap.put("x86-64"	, "64");
		archMap.put("x86_64"	, "64");
		final String arch = archMap.getOrDefault(osArch, "-" + osArch);

		return os + arch;
	}

	public static boolean isLauncher(final String filename) {
		final int slash = filename.indexOf("/");
		if (slash >= 0 && filename.substring(0, slash).endsWith(".app")) {
			// This file appears to reside inside a macOS-style .app folder.
			// All files in such folders count as launcher files, because the entire
			// structure must be stored together to maintain code-signing integrity.
			return true;
		}
		return LAUNCHERS.containsKey(filename);
	}

	public static Set<String> inferActive(final File appRoot) {
		// Note: A platform is considered "active" if:
		// 1. It is the current platform; or
		// 2. At least known launcher for that platform is present; or
		// 3. At least one directory specific to that platform is present.

		final Set<String> activePlatforms = new HashSet<>();

		// Activate the currently running platform.
		activePlatforms.add(current());

		if (appRoot == null) return activePlatforms;

		// For each existing launcher, add its platform.
		for (Map.Entry<String, String> lap : LAUNCHERS.entrySet()) {
			final String launcher = lap.getKey();
			final String platform = lap.getValue();
			final File launcherFile = new File(appRoot, launcher);
			if (launcherFile.exists()) activePlatforms.add(platform);
		}

		// For each existing special platform directory, add its platform.
		final String[] specialDirs = {"jars", "lib"};
		for (final String specialDir : specialDirs) {
			for (final String platform : Platforms.known()) {
				final File dir = appRoot.toPath().resolve(specialDir).resolve(platform).toFile();
				if (dir.isDirectory()) activePlatforms.add(platform);
			}
		}

		return activePlatforms;
	}
}
