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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.imagej.updater.FileObject.Action;
import net.imagej.updater.util.Progress;

import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.PluginService;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;

/**
 * Default service for managing available ImageJ upload mechanisms.
 * 
 * @author Johannes Schindelin
 * @author Curtis Rueden
 */
@Plugin(type = Service.class)
public class DefaultUploaderService extends AbstractService implements
	UploaderService
{

	@Parameter
	private LogService log;

	@Parameter
	private PluginService pluginService;

	private HashMap<String, Uploader> uploaderMap;

	// -- UploaderService methods --

	@Override
	public boolean hasUploader(final String protocol) {
		return uploaderMap().containsKey(protocol);
	}

	@Override
	public Uploader getUploader(String protocol)
		throws IllegalArgumentException
	{
		final Uploader uploader = uploaderMap().get(protocol);
		if (uploader == null) {
			throw new IllegalArgumentException("No uploader found for protocol " +
				protocol);
		}
		return uploader;
	}

	@Override
	public Uploader installUploader(String protocol, FilesCollection files, final Progress progress) {
		if (hasUploader(protocol)) return getUploader(protocol);

		FileObject uploader = files.get("jars/imagej-plugins-uploader-" + protocol + ".jar");
		if (uploader == null && "sftp".equals(protocol)) {
			uploader = files.get("jars/imagej-plugins-uploader-ssh.jar");
		}
		if (uploader == null) {
			throw new IllegalArgumentException("No uploader found for protocol " + protocol);
		}
		switch (uploader.getStatus()) {
			case NEW:
			case NOT_INSTALLED:
				break;
			default:
				throw new IllegalArgumentException("The uploader found for protocol " + protocol
					+ " could not be instantiated; the status of "
					+ uploader + " is " + uploader.getStatus());
		}
		final Set<URL> urls = new LinkedHashSet<>();
		final FilesCollection toInstall = files.clone(uploader.getFileDependencies(files, true));
		for (final FileObject file : toInstall) {
			switch (file.getStatus()) {
			case NEW:
			case NOT_INSTALLED:
			case UPDATEABLE:
				toInstall.add(file);
				file.setAction(toInstall, Action.INSTALL);
				try {
					urls.add(toInstall.prefixUpdate(file.filename).toURI().toURL());
				} catch (MalformedURLException e) {
					log.error(e);
					return null;
				}
				break;
			case MODIFIED:
				toInstall.ignoredConflicts.add(file);
				log.warn("Dependency " + file + " modified; the uploader might not be compatible");
				// FALL THRU
			case INSTALLED:
				try {
					urls.add(toInstall.prefix(file.filename).toURI().toURL());
				} catch (MalformedURLException e) {
					log.error(e);
					return null;
				}
				break;
			default:
				log.debug("Failure: dependency not installed: " + file + " (" + file.getStatus() + ")");
				return null;
			}
		}
		progress.setTitle("Installing uploader for '" + protocol + "'");
		final Installer installer = new Installer(toInstall, progress);
		try {
			installer.start();
			final ClassLoader parent = Thread.currentThread().getContextClassLoader();
			final URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);
			Thread.currentThread().setContextClassLoader(loader);
			pluginService.reloadPlugins();
			uploaderMap = null; // force re-discovery
			return hasUploader(protocol) ? getUploader(protocol) : null;
		} catch (IOException e) {
			log.error(e);
			return null;
		}
	}

	// -- Helper methods --

	private HashMap<String, Uploader> uploaderMap() {
		if (uploaderMap == null) {
			// ask the plugin service for the list of available upload mechanisms
			uploaderMap = new HashMap<>();
			final List<? extends Uploader> uploaders =
				pluginService.createInstancesOfType(Uploader.class);
			for (final Uploader uploader : uploaders) {
				uploaderMap.put(uploader.getProtocol(), uploader);
			}
			log.debug("Found " + uploaderMap.size() + " upload mechanisms.");
		}
		return uploaderMap;
	}

}
