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

import java.util.List;

import net.imagej.updater.util.UpdaterUtil;

import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * This plugin prompts the user to launch the updater due to available updates.
 * It is called by the {@link CheckForUpdates} command when updates are
 * available.
 * 
 * @author Johannes Schindelin
 */
@Plugin(type = Command.class, label = "There are updates available")
public class PromptUserToUpdate implements Command {

	private final static String YES = "Yes, please", NEVER = "Never",
			LATER = "Remind me later";

	@Parameter
	private CommandService commandService;

	@Parameter
	private LogService log;

	@Parameter(label = "Do you want to start the Updater now?", choices = { YES,
		NEVER, LATER })
	private String updateAction = YES;

	@Override
	public void run() {
		if (Boolean.getBoolean("java.awt.headless")) {
			// do not display the Updater UI in headless mode
			return;
		}
		if (updateAction.equals(YES)) {
			final List<CommandInfo> updaters =
				commandService.getCommandsOfType(UpdaterUI.class);
			if (updaters.size() > 0) {
				commandService.run(updaters.get(0), true);
			}
			else {
				if (log == null) {
					log = UpdaterUtil.getLogService();
				}
				log.error("No updater plugins found!");
			}
		}
		else if (updateAction.equals(NEVER)) UpToDate.setLatestNag(Long.MAX_VALUE);
		else if (updateAction.equals(LATER)) UpToDate.setLatestNag();
		else throw new RuntimeException("Unknown update action: " + updateAction);
	}

}
