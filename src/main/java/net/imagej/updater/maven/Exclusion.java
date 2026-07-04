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

/**
 * A {@code g:a} exclusion pattern; either field may be the wildcard
 * {@code *}. Exclusions never carry versions, classifiers, or packaging
 * (Maven rule).
 */
public final class Exclusion {

	private final String group;
	private final String artifact;

	public Exclusion(final String group, final String artifact) {
		this.group = group;
		this.artifact = artifact;
	}

	/** Parses {@code "g:a"}; {@code *} allowed for either field. */
	public static Exclusion parse(final String s) {
		final String[] tokens = s.split(":", -1);
		if (tokens.length != 2) {
			throw new IllegalArgumentException(
				"Invalid exclusion (expected g:a, * allowed): " + s);
		}
		return new Exclusion(tokens[0], tokens[1]);
	}

	public boolean matches(final GA ga) {
		return ("*".equals(group) || group.equals(ga.group())) &&
			("*".equals(artifact) || artifact.equals(ga.artifact()));
	}

	@Override
	public boolean equals(final Object o) {
		if (!(o instanceof Exclusion)) return false;
		final Exclusion other = (Exclusion) o;
		return group.equals(other.group) && artifact.equals(other.artifact);
	}

	@Override
	public int hashCode() {
		return group.hashCode() * 31 + artifact.hashCode();
	}

	@Override
	public String toString() {
		return group + ":" + artifact;
	}
}
