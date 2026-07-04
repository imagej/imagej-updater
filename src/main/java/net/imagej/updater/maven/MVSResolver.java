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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimum Version Selection (MVS) mediation over a composed
 * {@link ComponentCatalog}.
 * <p>
 * This is the cross-site composition algorithm specified in
 * doc/maven-native-update-sites.md §4.1/§4.2, and must produce results
 * identical to the normative reference implementation ({@code dbxm.mvs}
 * in the db-xml-maven repository). Both are validated against the shared
 * golden vectors.
 * </p>
 * <ul>
 * <li>The installation is a synthetic root; user-selected components are
 * its direct dependencies ("roots").</li>
 * <li><b>Versions come from selections; edges provide reachability.</b>
 * Each offered release publishes its resolved constellation (what
 * {@code jgo g:a:v} resolves); a root contributes, for every component
 * its surviving paths reach, the version its selection ships. With one
 * site enabled, mediation is the identity on the release constellation.
 * Roots without selection coverage (legacy indexes) contribute their
 * edge-declared versions as fallback.</li>
 * <li>Cross-site composition selects the highest contribution per
 * {@link GA} (MVS). Precedence: user pin &gt; MVS; no site-level BOM
 * override.</li>
 * <li>Exclusions are per-path; root exclusions are global. Exclusions
 * never remove an explicitly selected root.</li>
 * <li>Reachability walks each component at its <em>winning</em> version,
 * so losing subtrees are pruned even when a selection lists them.</li>
 * </ul>
 */
public final class MVSResolver {

	/**
	 * Iteration cap for the fixpoint loop. Real dependency graphs converge
	 * in a handful of iterations; the cap guards against pathological
	 * oscillation.
	 */
	public static final int MAX_ITERATIONS = 1000;

	private static final Pattern MAJOR = Pattern.compile("^(\\d+)");

	/** A user-selected component: site current version, or a pinned one. */
	public static final class Root {

		private final GA ga;
		private final String version;
		private final boolean pinned;

		public Root(final GA ga, final String version, final boolean pinned) {
			this.ga = ga;
			this.version = version;
			this.pinned = pinned;
		}

		public GA ga() { return ga; }
		public String version() { return version; }
		public boolean pinned() { return pinned; }
	}

	public enum WarningType {
			HELD_BELOW("held-below"), //
			MAJOR_MISALIGNMENT("major-misalignment"), //
			MISSING_RELEASE("missing-release");

		private final String label;

		WarningType(final String label) {
			this.label = label;
		}

		public String label() { return label; }

		public static WarningType fromLabel(final String label) {
			for (final WarningType type : values()) {
				if (type.label.equals(label)) return type;
			}
			throw new IllegalArgumentException("Unknown warning type: " + label);
		}
	}

	/** A non-blocking mediation diagnostic. */
	public static final class MediationWarning {

		private final WarningType type;
		private final GA ga;

		public MediationWarning(final WarningType type, final GA ga) {
			this.type = type;
			this.ga = ga;
		}

		public WarningType type() { return type; }
		public GA ga() { return ga; }

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof MediationWarning)) return false;
			final MediationWarning other = (MediationWarning) o;
			return type == other.type && ga.equals(other.ga);
		}

		@Override
		public int hashCode() {
			return type.hashCode() * 31 + ga.hashCode();
		}

		@Override
		public String toString() {
			return type.label() + "(" + ga + ")";
		}
	}

	public static final class Resolution {

		private final Map<GA, String> selected;
		private final List<MediationWarning> warnings;
		private final Map<GA, Set<String>> contributions;

		Resolution(final Map<GA, String> selected,
			final List<MediationWarning> warnings,
			final Map<GA, Set<String>> contributions)
		{
			this.selected = Collections.unmodifiableMap(selected);
			this.warnings = Collections.unmodifiableList(warnings);
			this.contributions = Collections.unmodifiableMap(contributions);
		}

		/** Final selection: one version per reachable {@link GA}. */
		public Map<GA, String> selected() { return selected; }

		/** Deduplicated, deterministically ordered warnings. */
		public List<MediationWarning> warnings() { return warnings; }

		/** Surviving contributions from the stable walk, for diagnostics. */
		public Map<GA, Set<String>> contributions() { return contributions; }
	}

	public Resolution resolve(final ComponentCatalog catalog,
		final List<Root> roots, final Collection<Exclusion> rootExclusions)
	{
		final Map<GA, String> pins = new HashMap<>();
		for (final Root root : roots) {
			if (root.pinned()) pins.put(root.ga(), root.version());
		}

		Map<GA, String> selected = new HashMap<>();
		Map<GA, Set<String>> contributions = null;
		boolean converged = false;
		for (int i = 0; i < MAX_ITERATIONS; i++) {
			contributions = new HashMap<>();
			final Map<GA, Set<String>> fallback = new HashMap<>();
			for (final Root root : roots) {
				// An unpinned root's effective release follows the fixpoint:
				// another site's selection may have raised it.
				final String rootVersion = selected.containsKey(root.ga()) ? //
					selected.get(root.ga()) : root.version();
				final Release rootRelease = catalog.release(root.ga(), rootVersion);
				final Map<GA, String> selection = rootRelease == null ? //
					Collections.<GA, String> emptyMap() : rootRelease.selection();
				final RootWalk walk = walkRoot(catalog, root.ga(), rootVersion,
					selection, rootExclusions, selected);
				for (final GA ga : walk.reached) {
					Set<String> versions = contributions.get(ga);
					if (versions == null) {
						versions = new HashSet<>();
						contributions.put(ga, versions);
					}
					if (!selection.isEmpty()) {
						// A selection-bearing root's authoritative statement
						// is its selection; it never contributes edge versions.
						if (selection.containsKey(ga)) {
							versions.add(selection.get(ga));
						}
					}
					else if (walk.edgeRequests.containsKey(ga)) {
						versions.addAll(walk.edgeRequests.get(ga));
					}
					// Last-resort pool for components no contribution covers
					// (e.g. subtrees of pins to non-offered versions).
					if (walk.edgeRequests.containsKey(ga)) {
						Set<String> pool = fallback.get(ga);
						if (pool == null) {
							pool = new HashSet<>();
							fallback.put(ga, pool);
						}
						pool.addAll(walk.edgeRequests.get(ga));
					}
				}
			}
			for (final Map.Entry<GA, Set<String>> entry : contributions.entrySet()) {
				if (entry.getValue().isEmpty() &&
					fallback.containsKey(entry.getKey()))
				{
					entry.getValue().addAll(fallback.get(entry.getKey()));
				}
			}

			final Map<GA, String> newSelected = new HashMap<>();
			for (final Map.Entry<GA, Set<String>> entry : contributions.entrySet()) {
				final GA ga = entry.getKey();
				if (entry.getValue().isEmpty()) continue;
				String version = highest(entry.getValue());
				if (pins.containsKey(ga)) version = pins.get(ga);
				newSelected.put(ga, version);
			}
			if (newSelected.equals(selected)) {
				converged = true;
				break;
			}
			selected = newSelected;
		}
		if (!converged) {
			throw new IllegalStateException(
				"MVS did not converge within " + MAX_ITERATIONS + " iterations");
		}

		return new Resolution(selected,
			warnings(catalog, selected, contributions), contributions);
	}

	// -- Walk --

	private static final class Frame {

		final GA ga;
		final String requested;
		final Set<Exclusion> exclusions;

		Frame(final GA ga, final String requested,
			final Set<Exclusion> exclusions)
		{
			this.ga = ga;
			this.requested = requested;
			this.exclusions = exclusions;
		}
	}

	private static final class State {

		final GA ga;
		final String version;
		final Set<Exclusion> exclusions;

		State(final GA ga, final String version,
			final Set<Exclusion> exclusions)
		{
			this.ga = ga;
			this.version = version;
			this.exclusions = exclusions;
		}

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof State)) return false;
			final State other = (State) o;
			return ga.equals(other.ga) && version.equals(other.version) &&
				exclusions.equals(other.exclusions);
		}

		@Override
		public int hashCode() {
			return (ga.hashCode() * 31 + version.hashCode()) * 31 +
				exclusions.hashCode();
		}
	}

	private static final class RootWalk {

		final Set<GA> reached = new HashSet<>();
		final Map<GA, Set<String>> edgeRequests = new HashMap<>();
	}

	/**
	 * One root's traversal, honoring the current global selection.
	 * <p>
	 * Node versions resolve as: global winner, else this root's
	 * selection, else the edge-declared version. Returns the components
	 * reached via surviving paths plus the edge-declared versions per
	 * component (the fallback contributions for selection-less roots).
	 * </p>
	 */
	private RootWalk walkRoot(final ComponentCatalog catalog,
		final GA rootGA, final String rootVersion,
		final Map<GA, String> selection,
		final Collection<Exclusion> rootExclusions,
		final Map<GA, String> selected)
	{
		final RootWalk walk = new RootWalk();
		final Deque<Frame> stack = new ArrayDeque<>();
		final Set<Exclusion> globalExclusions = rootExclusions == null ? //
			Collections.<Exclusion> emptySet() : //
			Collections.unmodifiableSet(new HashSet<>(rootExclusions));

		// Roots are seeded unconditionally: exclusions never remove an
		// explicitly selected root.
		request(walk.edgeRequests, rootGA, rootVersion);
		stack.push(new Frame(rootGA, rootVersion, globalExclusions));

		// Visited keys include the exclusion set: the same node may be
		// pruned differently on different paths, and only surviving paths
		// contribute.
		final Set<State> visited = new HashSet<>();

		while (!stack.isEmpty()) {
			final Frame frame = stack.pop();
			walk.reached.add(frame.ga);
			// Traverse at the winning version (prunes losing subtrees).
			String version = selected.get(frame.ga);
			if (version == null) version = selection.get(frame.ga);
			if (version == null) version = frame.requested;
			final State state = new State(frame.ga, version, frame.exclusions);
			if (!visited.add(state)) continue;

			final Release release = catalog.release(frame.ga, version);
			if (release == null || release.slim()) {
				continue; // leaf: unknown or recognition-only entry
			}

			// A node traversed at its root-selection version only follows
			// edges into the selection's domain: anything outside was
			// pruned by the site's own resolution (root depMgmt
			// exclusions, scope overrides, platform), which the facts
			// edges cannot express.
			final boolean onSelection = !selection.isEmpty() &&
				version.equals(selection.get(frame.ga));

			final Set<Exclusion> nodeExclusions = frame.exclusions;
			for (final DependencyEdge edge : release.edges()) {
				if (edge.optional()) {
					continue; // optional deps of dependencies are not traversed
				}
				if (onSelection && !selection.containsKey(edge.ga())) {
					continue; // pruned by this site's own resolution
				}
				if (excluded(edge.ga(), nodeExclusions)) {
					continue; // pruned on this path
				}
				request(walk.edgeRequests, edge.ga(), edge.version());
				Set<Exclusion> childExclusions = nodeExclusions;
				if (!edge.exclusions().isEmpty()) {
					final Set<Exclusion> augmented = new HashSet<>(nodeExclusions);
					augmented.addAll(edge.exclusions());
					childExclusions = augmented;
				}
				stack.push(new Frame(edge.ga(), edge.version(), childExclusions));
			}
		}
		return walk;
	}

	private static void request(final Map<GA, Set<String>> requests,
		final GA ga, final String version)
	{
		Set<String> versions = requests.get(ga);
		if (versions == null) {
			versions = new HashSet<>();
			requests.put(ga, versions);
		}
		versions.add(version);
	}

	private static boolean excluded(final GA ga,
		final Set<Exclusion> exclusions)
	{
		for (final Exclusion exclusion : exclusions) {
			if (exclusion.matches(ga)) return true;
		}
		return false;
	}

	// -- Warnings --

	private static String highest(final Collection<String> versions) {
		String best = null;
		for (final String version : versions) {
			if (best == null || MavenVersion.compare(version, best) > 0) {
				best = version;
			}
		}
		return best;
	}

	private static Integer majorOf(final String version) {
		final Matcher m = MAJOR.matcher(version);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	private static List<MediationWarning> warnings(
		final ComponentCatalog catalog, final Map<GA, String> selected,
		final Map<GA, Set<String>> requests)
	{
		final Set<MediationWarning> found = new LinkedHashSet<>();
		for (final Map.Entry<GA, Set<String>> entry : requests.entrySet()) {
			final GA ga = entry.getKey();
			final Set<String> versions = entry.getValue();
			final String chosen = selected.get(ga);
			final String highest = highest(versions);
			if (MavenVersion.compare(chosen, highest) < 0) {
				found.add(new MediationWarning(WarningType.HELD_BELOW, ga));
			}
			final Integer chosenMajor = majorOf(chosen);
			if (chosenMajor != null) {
				for (final String version : versions) {
					final Integer major = majorOf(version);
					if (major != null && !major.equals(chosenMajor)) {
						found.add(
							new MediationWarning(WarningType.MAJOR_MISALIGNMENT, ga));
						break;
					}
				}
			}
			if (catalog.release(ga, chosen) == null) {
				found.add(new MediationWarning(WarningType.MISSING_RELEASE, ga));
			}
		}
		final List<MediationWarning> sorted = new ArrayList<>(found);
		Collections.sort(sorted, new Comparator<MediationWarning>() {

			@Override
			public int compare(final MediationWarning a,
				final MediationWarning b)
			{
				final int byType = a.type().label().compareTo(b.type().label());
				if (byType != 0) return byType;
				return a.ga().compareTo(b.ga());
			}
		});
		return sorted;
	}
}
