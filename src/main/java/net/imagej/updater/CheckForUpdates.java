/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2022 Board of Regents of the University of
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

import static org.scijava.ui.DialogPrompt.MessageType.WARNING_MESSAGE;

import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

/**
 * This plugin checks whether updates are available, and prompts the user to
 * launch the updater if so. It typically runs when ImageJ first starts up.
 * 
 * @author Johannes Schindelin
 * @author Curtis Rueden
 */
@Plugin(type = Command.class, label = "Up-to-date check")
public class CheckForUpdates implements Command {

	@Parameter
	private CommandService commandService;

	@Parameter(required = false)
	private UIService uiService;

	@Parameter(required = false)
	private LogService log;

	@Override
	public void run() {
		try {
			final UpToDate.Result result = UpToDate.check();
			switch (result) {
				case UP_TO_DATE:
				case OFFLINE:
				case REMIND_LATER:
				case CHECK_TURNED_OFF:
				case UPDATES_MANAGED_DIFFERENTLY:
				case DEVELOPER:
					return;
				case UPDATEABLE:
					commandService.run(PromptUserToUpdate.class, true);
					break;
				case PROXY_NEEDS_AUTHENTICATION:
					throw new RuntimeException(
						"TODO: authenticate proxy with the configured user/pass pair");
				case PROTECTED_LOCATION:
					fail("Your ImageJ installation cannot be updated because it is in " +
						"a protected location (e.g., \"C:\\Program Files\").\nPlease " +
						"move your installation to a directory with write permission.");
					break;
				case READ_ONLY:
					fail("Your ImageJ installation cannot be updated because " +
						"it is read-only.");
					break;
				default:
					if (log != null) log.error("Unhandled UpToDate case: " + result);
			}
		}
		catch (final Exception e) {
			if (log != null) log.error(e);
		}
	}

	private void fail(final String message) {
		if (log != null) log.warn(message);
		if (uiService != null) {
			uiService.showDialog(message, "ImageJ Updater", WARNING_MESSAGE);
		}
	}
}
