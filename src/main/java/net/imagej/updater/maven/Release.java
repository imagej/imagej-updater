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

package net.imagej.updater.maven;

import java.util.ArrayList;
import java.util.List;

/**
 * One version of a component, with its direct dependency edges.
 * <p>
 * Slim entries carry identity+sha1 for local-file recognition only; they
 * have no (reliable) edges and are not installable.
 * </p>
 */
public final class Release {

	private final String version;
	private final List<DependencyEdge> edges = new ArrayList<>();
	private String sha1;
	private String timestamp;
	private long filesize = -1;
	private int minJava = -1;
	private String description;
	private boolean slim;

	public Release(final String version) {
		this.version = version;
	}

	public String version() { return version; }
	public List<DependencyEdge> edges() { return edges; }

	public String sha1() { return sha1; }
	public void setSha1(final String sha1) { this.sha1 = sha1; }

	public String timestamp() { return timestamp; }
	public void setTimestamp(final String timestamp) { this.timestamp = timestamp; }

	public long filesize() { return filesize; }
	public void setFilesize(final long filesize) { this.filesize = filesize; }

	public int minJava() { return minJava; }
	public void setMinJava(final int minJava) { this.minJava = minJava; }

	public String description() { return description; }
	public void setDescription(final String description) { this.description = description; }

	public boolean slim() { return slim; }
	public void setSlim(final boolean slim) { this.slim = slim; }

	@Override
	public String toString() {
		return version + (slim ? " (slim)" : "");
	}
}
