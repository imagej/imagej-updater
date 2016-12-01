/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2016 Board of Regents of the University of
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
import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;

import net.imagej.updater.util.AvailableSites;

import org.scijava.app.AppService;
import org.scijava.command.CommandService;
import org.scijava.event.EventHandler;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;
import org.scijava.ui.event.UIShownEvent;
import org.xml.sax.SAXException;

/**
 * Default service for managing ImageJ updates.
 * 
 * @author Curtis Rueden
 */
@Plugin(type = Service.class)
public class DefaultUpdateService extends AbstractService implements
	UpdateService
{

	@Parameter
	private CommandService commandService;

	@Parameter
	private AppService appService;

	@Parameter(required = false)
	private LogService log;

	private FilesCollection filesCollection;

	// -- UpdateService methods --

	@Override
	public UpdateSite getUpdateSite(final File file) {
		// TODO: Create FileUtils.isSubPath and/or FileUtils.getRelativePath
		// utility methods, to do this comparison 100% correctly, even on
		// Windows with mixed path separators, etc.
		final String path = file.getAbsolutePath();
		final String root = rootDir().getAbsolutePath() + File.separator;
		if (!path.startsWith(root)) return null;
		final String shortPath = path.substring(root.length());

		final FilesCollection fc = filesCollection();
		final FileObject fileObject = fc.get(shortPath);
		return fc.getUpdateSite(fileObject.updateSite, true);
	}

	// -- Event handlers --

	/**
	 * Checks for updates when the ImageJ UI is first shown.
	 * 
	 * @param evt The event indicating the UI was shown.
	 */
	@EventHandler
	protected void onEvent(final UIShownEvent evt) {
		// NB: Check for updates, but on a separate thread (not the EDT!).
		commandService.run(CheckForUpdates.class, true);
	}

	// -- Helper methods --

	private File rootDir() {
		return appService.getApp().getBaseDirectory();
	}

	private FilesCollection filesCollection() {
		if (filesCollection == null) initFilesCollection();
		return filesCollection;
	}

	private synchronized void initFilesCollection() {
		if (filesCollection != null) return;
		final FilesCollection fc = new FilesCollection(rootDir());

		// parse the official list of update sites
		AvailableSites.initializeAndAddSites(fc);

		// parse the user's update site database (db.xml.gz)
		try {
			fc.read();
		}
		catch (final IOException exc) {
			if (log != null) log.error("Error parsing update sites", exc);
		}
		catch (final ParserConfigurationException exc) {
			if (log != null) log.error("Error parsing update sites", exc);
		}
		catch (final SAXException exc) {
			if (log != null) log.error("Error parsing update sites", exc);
		}

		filesCollection = fc;
	}
}
