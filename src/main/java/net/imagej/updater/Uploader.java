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

import net.imagej.ImageJPlugin;
import net.imagej.updater.util.Progressable;

import org.scijava.plugin.Plugin;

/**
 * Interface for ImageJ upload mechanisms.
 * <p>
 * Upload mechanisms discoverable at runtime must implement this interface and
 * be annotated with @{@link Plugin} with attribute {@link Plugin#type()} =
 * {@link Uploader}.class. While it possible to create an upload mechanism
 * merely by implementing this interface, it is encouraged to instead extend
 * {@link AbstractUploader}, for convenience.
 * </p>
 * 
 * @author Johannes Schindelin
 * @author Curtis Rueden
 * @see Plugin
 * @see UploaderService
 */
public interface Uploader extends ImageJPlugin, Progressable {

	/** TODO */
	void upload(List<Uploadable> files, List<String> locks) throws IOException;

	/** TODO */
	void calculateTotalSize(List<Uploadable> sources);

	/** TODO */
	boolean login(FilesUploader uploader);

	/** TODO */
	void logout();

	/** TODO */
	long getTimestamp();

	/** TODO */
	String getProtocol();

}
