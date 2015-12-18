/*
 * Please run this Javascript via
 *
 *    Macros>Evaluate Javascript
 *
 * or by hitting Ctrl+J (on MacOSX, Apple+J).
 *
 * If this fails, please call Edit>Select All,
 * Edit>Copy, switch to the main window call
 * File>New>Script..., Edit>Paste, select
 * Language>JavaScript and then hit the Run
 * button.
 */

var isNashorn = true;
try {
	load("nashorn:mozilla_compat.js");
} catch (e) {
	isNashorn = false;
}

importClass(Packages.java.io.File);
importClass(Packages.java.lang.System);
importClass(Packages.java.lang.Throwable);
importClass(Packages.java.net.URL);
importClass(Packages.java.net.URLClassLoader);
importClass(Packages.java.util.regex.Pattern);

baseURL = 'http://update.imagej.net/jars/';
jars = [
	'imagej-ui-swing-0.11.2.jar-20150501184913',
	'imagej-plugins-uploader-webdav-0.2.0.jar-20141219193933',
	'imagej-updater-0.7.5.jar-20150522102918',
	'scijava-common-2.44.2.jar-20150720161756',
	'imagej-common-0.14.0.jar-20150415222444',
	'eventbus-1.4.jar-20120404210913',
	'gentyref-1.1.0.jar-20140516211031',
	'scijava-ui-swing-0.7.1.jar-20151122015629',
	'imglib2-2.4.1.jar-20151122015629'
];

isCommandLine = typeof arguments != 'undefined';

urls = [];
remoteCount = localCount = 0;
pattern = Pattern.compile("^(.*/)?([^/]*\\.jar)-[0-9]+$");
for (i = 0; i < jars.length; i++) {
	if (isCommandLine && (matcher = pattern.matcher(jars[i])).matches()) {
		file = new File("jars/" + matcher.group(2));
		if (file.exists()) {
			urls[i] = file.toURI().toURL();
			localCount++;
			continue;
		}
	}
	urls[i] = new URL(baseURL + jars[i]);
	remoteCount++;
}

importClass(Packages.java.lang.ClassLoader);
parent = ClassLoader.getSystemClassLoader().getParent();
loader = new URLClassLoader(urls, parent);

if (isCommandLine) {
	importClass(Packages.java.lang.System);

	var IJ = {
		debugMode: false,

		getDirectory: function(label) {
			// command-line: default to current directory
			return new File("").getAbsolutePath();
		},

		showStatus: function(message) {
			print(message + "\n");
		},

		error: function(message) {
			print(message + "\n");
		},

		handleException: function(exception) {
			exception.printStackTrace();
		}
	}

	var updaterClassName = "net.imagej.updater.CommandLine";
} else {
	try {
		importClass(Packages.ij.IJ);
	} catch (e) {
		// ignore; this is a funny PluginClassLoader problem
	}

	if (typeof IJ == 'undefined') try {
		importClass(Packages.java.awt.GraphicsEnvironment);
		importClass(Packages.java.lang.Thread);
		var loader2 = Thread.currentThread().getContextClassLoader();
		var IJ = loader2.loadClass('ij.IJ').newInstance();
		if (IJ.getInstance() == null && !GraphicsEnvironment.isHeadless()) {
			IJ = null;
		}
	} catch (e) {
		// ignore
	}

	if (typeof IJ == 'undefined' || IJ == null) {
		importClass(Packages.java.awt.BorderLayout);
		importClass(Packages.java.io.ByteArrayOutputStream);
		importClass(Packages.java.io.PrintStream);
		importClass(Packages.java.lang.System);
		importClass(Packages.javax.swing.JFrame);
		importClass(Packages.javax.swing.JScrollPane);
		importClass(Packages.javax.swing.JTextArea);

		var frame = new JFrame("Remote ImageJ updater");
		var text = new JTextArea(20, 50);
		text.setEditable(false);
		text.setLineWrap(true);
		frame.getContentPane().add(new JScrollPane(text), BorderLayout.NORTH);
		frame.pack();
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		print = function(message) {
			frame.setVisible(true);
			text.append(message + "\n");
		};

		var disposeTrigger = Pattern.compile("running .* updater");

		var IJ = {
			debugMode: "true".equalsIgnoreCase(System.getProperty("scijava.log.level")),

			getDirectory: function(label) {
				// default to current directory
				return new File("").getAbsolutePath();
			},

			showStatus: function(message) {
				print(message);
				if (disposeTrigger.matcher(message).matches()) {
					frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
					frame.dispose();
				}
			},

			error: function(message) {
				print(message);
			},

			handleException: function(exception) {
				disposeTrigger = Pattern.compile("\n");
				var buffer = new ByteArrayOutputStream();
				new Throwable().printStackTrace(new PrintStream(buffer));
				print(buffer.toString("UTF-8"));
			}
		}
	}

	var updaterClassName = "net.imagej.ui.swing.updater.ImageJUpdater";
}

if (typeof cause != 'undefined' && cause instanceof Throwable) {
	IJ.showStatus("This ImageJ installation is currently broken.\n" +
		"The ImageJ Updater will be launched so that it can be repaired!");
	IJ.handleException(cause);
}

// make sure that the system property 'imagej.dir' is set correctly
if (System.getProperty("imagej.dir") == null) {
	imagejDir = System.getProperty("ij.dir");
	if (imagejDir == null) {
		imagejDir = IJ.getDirectory("imagej");
	}
	if (imagejDir != null) {
		if (imagejDir.endsWith("/jars/") || imagejDir.endsWith("\\jars\\"))
			imagejDir = imagejDir.substring(0, imagejDir.length() - 5);
	} else {
		url = IJ.getClassLoader().loadClass("ij.IJ").getResource("/ij/IJ.class").toString();
		bang = url.indexOf(".jar!/");
		if (url.startsWith("jar:file:") && bang > 0) {
			imagejDir = new File(url.substring(9, bang)).getParent();
			if (imagejDir.endsWith("/target") || imagejDir.endsWith("\\target"))
				imagejDir = imagejDir.substring(0, imagejDir.length() - 7);
		}
		else if (url.startsWith("file:") && bang < 0 && url.endsWith("/ij/IJ.class")) {
			imagejDir = url.substring(5, url.length() - 12);
			if (imagejDir.endsWith("/classes"))
				imagejDir = imagejDir.substring(0, imagejDir.length() - 8);
			if (imagejDir.endsWith("/target"))
				imagejDir = imagejDir.substring(0, imagejDir.length() - 7);
		}
		else {
			IJ.error("Cannot set imagej.dir for " + url);
		}
	}
	System.setProperty("imagej.dir", imagejDir);
}
if (IJ.debugMode) print('ImageJ directory: ' + imagejDir);

// for backwards-compatibility, make sure that the system property 'ij.dir'
// is set correctly, too, just in case
if (System.getProperty("ij.dir") == null) {
	System.setProperty("ij.dir", System.getProperty("imagej.dir"));
}

imagejDir = new File(System.getProperty("imagej.dir"));
if (!new File(imagejDir, "db.xml.gz").exists()) {
	filesClass = loader.loadClass("net.imagej.updater.FilesCollection");
	files = filesClass.getConstructor([ loader.loadClass("java.io.File") ]).newInstance([ imagejDir ]);
	files.getUpdateSite("ImageJ").timestamp = -1;
	if (!"true".equalsIgnoreCase(System.getProperty("skip.fiji"))) {
		IJ.showStatus("adding the Fiji update site");
		files.addUpdateSite("Fiji", "http://update.fiji.sc/", null, null, -1);
	}
	files.write();
}

if (isCommandLine && arguments.length == 1 &&
		("jar-urls".equals(arguments[0]) ||
		 "update-jar-urls".equals(arguments[0]))) {
	IJ.showStatus("Loading the FilesCollection class");
	clazz = loader.loadClass("net.imagej.updater.FilesCollection");
	fileClazz = loader.loadClass("java.io.File");
	files = clazz.getConstructor([fileClazz]).newInstance([imagejDir]);

	IJ.showStatus("Updating from the update site");
	xmlClazz = loader.loadClass("net.imagej.updater.XMLFileDownloader");
	xml = xmlClazz.getConstructor([clazz]).newInstance([files]);
	xml.start(true);

	swingUI = files.get("jars/imagej-ui-swing.jar");
	cmdLine = files.get("jars/imagej-updater.jar");
	list = new Array();
	i = 0;
	list[i++] = swingUI;
	list[i++] = files.get("jars/imagej-plugins-uploader-webdav.jar");
	for (iter = cmdLine.getFileDependencies(files, true).iterator();
			iter.hasNext(); ) {
		f = iter.next();
		if (!f.getFilename(true).matches("jars/" +
				"(imglib2|scifio|mapdb|udunits|imagej-ops|javassist|jama|trove|scijava-expression-parser|tools).*")) {
			list[i++] = f;
		}
	}

	prefix = null;
	for (i = 0; i < list.length; i++) {
		url = files.getURL(list[i]);
		if (prefix == null) prefix = url;
		else while (!url.startsWith(prefix)) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		list[i] = url;
	}
	output = "baseURL = '" + prefix + "';\n";
	output += "jars = [\n";
	for (i = 0; i < list.length; i++) {
		output += "\t'" + list[i].substring(prefix.length()) + "',\n";
	}
	output = output.substring(0, output.length - 2);
	output += "\n];\n";

	if (!"update-jar-urls".equals(arguments[0])) {
		print(output);
	} else {
		var readFile = function(file) {
			importClass(Packages.java.io.BufferedReader);
			importClass(Packages.java.io.FileReader);
			var result = "";
			var reader = new BufferedReader(new FileReader(file));
			for (;;) {
				var line = reader.readLine();
				if (line == null) break;
				result += line + "\n";
			}
			reader.close();
			return result;
		}

		var writeFile = function(file, contents) {
			importClass(Packages.java.io.BufferedWriter);
			importClass(Packages.java.io.FileWriter);
			var writer = new BufferedWriter(new FileWriter(file));
			writer.write(contents);
			writer.close();
		}


		var file = new File(this["javax.script.filename"]);
		var contents = readFile(file);
		var begin = contents.indexOf("baseURL = ");
		var end = contents.indexOf("\n];\n", begin);
		if (begin < 0 || end < 0) {
			print("Could not find section to replace:\n\n"
				+ contents);
			System.exit(1);
		}
		contents = contents.substring(0, begin)
			+ output
			+ contents.substring(end + 4);
		writeFile(file, contents);
		print("Please run `git diff` now and commit if groovy");
	}
	System.exit(0);
}

if (remoteCount > 0) {
	suffix = (localCount > 0 ? "partially " : "") + "remote updater";
} else {
	suffix = "local updater";
}

IJ.showStatus("loading " + suffix);
updaterClass = loader.loadClass(updaterClassName);
IJ.showStatus("running " + suffix);
try {
	var i = updaterClass.newInstance();
	if (isCommandLine) {
		if (!isNashorn) {
			i.main(arguments);
		} else {
			var methods = updaterClass.getMethods();
			var main;
			for (var i = 0; i < methods.length; i++) {
				if (methods[i].toString().endsWith(".main(java.lang.String[])")) {
					main = methods[i];
				}
			}
			main.invoke(null, [arguments]);
		}
	} else {
		Thread.currentThread().setName("Updating the Updater itself!");
		i.run();
	}
} catch (e) {
	IJ.handleException(e.javaException || e);
}
