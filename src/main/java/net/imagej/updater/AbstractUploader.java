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
import java.util.List;

import net.imagej.updater.util.AbstractProgressable;

/**
 * Abstract base class for ImageJ upload mechanisms.
 * 
 * @author Johannes Schindelin
 */
public abstract class AbstractUploader extends AbstractProgressable implements
	Uploader
{

	protected String uploadDir;
	protected int total;
	protected long timestamp;

	@Override
	public abstract void upload(List<Uploadable> files, List<String> locks)
		throws IOException;

	@Override
	public void calculateTotalSize(final List<Uploadable> sources) {
		total = 0;
		for (final Uploadable source : sources)
			total += (int) source.getFilesize();
	}

	@Override
	public boolean login(final FilesUploader uploader) {
		uploadDir = uploader.getUploadDirectory();
		return true; // no login required; override this if login _is_ required!
	}

	@Override
	public void logout() {
		// no logout required; override this if logout _is_ required!
	}

	@Override
	public long getTimestamp() {
		return timestamp;
	}

}
