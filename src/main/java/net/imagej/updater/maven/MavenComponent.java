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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All known versions of one {@link GA}, plus site-level flags.
 * <p>
 * Presence in a catalog is a <em>fact</em>, not an installation
 * instruction; only {@code offered} components can be enabled by users,
 * and the installed set is always the output of mediation.
 * </p>
 */
public final class MavenComponent {

	private final GA ga;
	private final Map<String, Release> releases = new LinkedHashMap<>();
	private boolean offered;
	private String current;

	public MavenComponent(final GA ga) {
		this.ga = ga;
	}

	public GA ga() { return ga; }
	public Map<String, Release> releases() { return releases; }

	public void add(final Release release) {
		releases.put(release.version(), release);
	}

	public Release release(final String version) {
		return releases.get(version);
	}

	public boolean offered() { return offered; }
	public void setOffered(final boolean offered) { this.offered = offered; }

	public String current() { return current; }
	public void setCurrent(final String current) { this.current = current; }

	@Override
	public String toString() {
		return ga.toString();
	}
}
